package fr.kinwaypoint.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.kinwaypoint.app.ListenActivity
import fr.kinwaypoint.app.R
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.net.AudioRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * Écoute en direct, côté enfant : le micro est diffusé vers le parent.
 *
 * Toujours visible : écran « Un parent t'écoute », notification permanente, et
 * le point vert d'Android. Rien n'est enregistré. L'écoute s'arrête quand le
 * parent raccroche, après 10 minutes, ou si la connexion est perdue.
 */
class ListenService : Service() {

    companion object {
        private const val TAG = "KinWaypoint"
        private const val CHANNEL_ID = "listen"

        /**
         * Prévenir l'enfant de façon PROACTIVE au démarrage d'une écoute :
         *  - true  : écran plein « Un parent t'écoute » (notification « appel »
         *            plein écran) qui s'ouvre tout seul, en plus du reste.
         *  - false : pas d'écran qui s'ouvre de force, mais l'écoute reste
         *            TOUJOURS signalée — notification permanente non
         *            supprimable, point vert d'Android, et bandeau dans l'appli.
         *
         * Point de réglage unique, pour la maintenance. NE JAMAIS tout masquer :
         * une écoute cachée est interdite (voir AGENTS.md « jamais d'écoute
         * cachée »). Au moins un indicateur visible reste affiché quoi qu'il arrive.
         */
        const val ALERT_CHILD_ON_START = true
        private const val NOTIFICATION_ID = 79
        private const val REQUEST_NOTIFICATION_ID = 80
        private const val MAX_MS = 10 * 60_000L
        const val EXTRA_ROOM = "room"
        const val EXTRA_SECRET = "secret"

        private val _active = MutableStateFlow(false)
        /** Vrai pendant qu'un parent écoute (bandeau dans l'appli de l'enfant). */
        val active: StateFlow<Boolean> = _active

        /**
         * Commande reçue du serveur. Android n'autorise le micro qu'à une appli
         * visible : on ouvre donc l'écran « Un parent t'écoute » (permis grâce à
         * l'autorisation « afficher par-dessus les autres applis »), qui démarre
         * l'écoute. Sans cette autorisation, une notification à toucher le fait.
         */
        fun request(context: Context, room: String, secret: String) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                if (!SettingsRepository(app).allowListen.first()) {
                    Log.i(TAG, "écoute demandée mais désactivée sur ce téléphone")
                    return@launch
                }
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "écoute demandée mais micro non autorisé")
                    return@launch
                }
                val screen = Intent(app, ListenActivity::class.java)
                    .putExtra(EXTRA_ROOM, room)
                    .putExtra(EXTRA_SECRET, secret)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Écran plein d'alerte seulement si ALERT_CHILD_ON_START ; sinon on va
                // directement au service, qui affichera la notification permanente visible.
                val opened = ALERT_CHILD_ON_START && Settings.canDrawOverlays(app) &&
                    runCatching { app.startActivity(screen) }.isSuccess
                when {
                    opened -> Unit
                    ALERT_CHILD_ON_START -> showRequestNotification(app, screen)
                    // Sans alerte proactive : le service démarre et pose sa notification permanente.
                    else -> start(app, room, secret)
                }
            }
        }

        fun start(context: Context, room: String, secret: String) {
            val intent = Intent(context, ListenService::class.java)
                .putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_SECRET, secret)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Écoute par un parent", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Affiché quand un parent écoute le micro en direct"
                    },
                )
            }
        }

        private fun showRequestNotification(context: Context, screen: Intent) {
            createChannel(context)
            val open = PendingIntent.getActivity(
                context, 6, screen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(NotificationManager::class.java).notify(
                REQUEST_NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Un parent veut t'écouter")
                    .setContentText("Touche ici pour activer le micro.")
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setAutoCancel(true)
                    .setTimeoutAfter(AudioRelay.COMMAND_MAX_AGE_S * 1000)
                    .build(),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var room: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newRoom = intent?.getStringExtra(EXTRA_ROOM)
        val secret = intent?.getStringExtra(EXTRA_SECRET)
        if (newRoom == null || secret == null) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (running && newRoom == room) return START_NOT_STICKY
        if (running) release()

        createChannel(this)
        getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION_ID)
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "écoute : démarrage refusé par Android", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        room = newRoom
        running = true
        _active.value = true
        main.postDelayed({ stopSelf() }, MAX_MS)
        scope.launch { connect(newRoom, secret) }
        return START_NOT_STICKY
    }

    private suspend fun connect(room: String, secret: String) {
        val relay = AudioRelay.relayUrl()
        if (relay == null) {
            Log.w(TAG, "écoute : relais audio non configuré sur le serveur familial")
            main.post { stopSelf() }
            return
        }
        val request = Request.Builder().url(AudioRelay.url(relay, room, "pub", secret)).build()
        socket = AudioRelay.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Thread({ capture(webSocket) }, "kw-listen").start()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { stopSelf() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "écoute : connexion perdue (${t.message})")
                main.post { stopSelf() }
            }
        })
    }

    @android.annotation.SuppressLint("MissingPermission") // vérifiée avant le démarrage
    private fun capture(webSocket: WebSocket) {
        val min = AudioRecord.getMinBufferSize(AudioRelay.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioRelay.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, AudioRelay.FRAME_BYTES * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "écoute : micro indisponible")
            record?.release()
            main.post { stopSelf() }
            return
        }
        if (!running) { record.release(); return }
        recorder = record
        runCatching { record.startRecording() }
        val buffer = ByteArray(AudioRelay.FRAME_BYTES)
        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            // Réseau trop lent : on saute des trames plutôt que d'accumuler du retard.
            if (webSocket.queueSize() > AudioRelay.FRAME_BYTES * 10) continue
            webSocket.send(buffer.toByteString(0, read))
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 7, Intent(this, ListenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🎧 Un parent t'écoute en direct")
            .setContentText("Le micro est partagé avec ta famille. Rien n'est enregistré.")
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun release() {
        running = false
        main.removeCallbacksAndMessages(null)
        recorder?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        recorder = null
        socket?.close(1000, "fin")
        socket = null
    }

    override fun onDestroy() {
        release()
        _active.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
