package fr.kinwaypoint.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import fr.kinwaypoint.app.R

/**
 * « Faire sonner le téléphone » à la demande d'un parent, pour le retrouver.
 * Le son passe par le canal ALARME d'Android : il sonne même en mode
 * silencieux ou vibreur (pas toujours en « Ne pas déranger » strict).
 * Volume alarme mis au maximum pendant 60 s, puis rétabli.
 */
object RingAlarm {
    private const val CHANNEL_ID = "ring"
    private const val NOTIFICATION_ID = 77
    private const val DURATION_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var previousVolume = -1
    /** Session média « à volume distant » : une touche de volume arrête la sonnerie. */
    private var session: android.media.session.MediaSession? = null
    /** État « Ne pas déranger » et mode sonnerie d'avant, rétablis à l'arrêt (-1 = inchangé). */
    private var previousFilter = -1
    private var previousRingerMode = -1

    fun start(context: Context) = main.post { startOnMain(context.applicationContext) }

    fun stop(context: Context) = main.post { stopOnMain(context.applicationContext) }

    private fun startOnMain(context: Context) {
        if (player != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        // « Ne pas déranger » et mode silencieux : levés pendant la sonnerie si l'autorisation
        // « accès Ne pas déranger » a été donnée (sinon le canal alarme sonne déjà en
        // silencieux / vibreur, mais pas en « Ne pas déranger » total).
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notifications.isNotificationPolicyAccessGranted) {
            runCatching {
                previousFilter = notifications.currentInterruptionFilter
                if (previousFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } else {
                    previousFilter = -1
                }
            }
            runCatching {
                previousRingerMode = audio.ringerMode
                if (previousRingerMode != AudioManager.RINGER_MODE_NORMAL) {
                    audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } else {
                    previousRingerMode = -1
                }
            }
        }
        runCatching {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.e("KinWaypoint", "sonnerie impossible", it) }.getOrNull()
        showNotification(context)
        listenVolumeKeys(context)
        main.postDelayed({ stopOnMain(context) }, DURATION_MS)
    }

    private fun stopOnMain(context: Context) {
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        val audio = context.getSystemService(AudioManager::class.java)
        if (previousRingerMode >= 0) {
            runCatching { audio.ringerMode = previousRingerMode }
            previousRingerMode = -1
        }
        if (previousFilter >= 0) {
            runCatching { context.getSystemService(NotificationManager::class.java).setInterruptionFilter(previousFilter) }
            previousFilter = -1
        }
        if (previousVolume >= 0) {
            runCatching {
                context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            }
            previousVolume = -1
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /**
     * Touche de volume (haut ou bas) = arrêter la sonnerie, même écran verrouillé :
     * une session média active « à volume distant » reçoit les touches de volume
     * à la place du téléphone.
     */
    private fun listenVolumeKeys(context: Context) {
        session = runCatching {
            android.media.session.MediaSession(context, "KinWaypointRing").apply {
                setPlaybackToRemote(object : android.media.VolumeProvider(VOLUME_CONTROL_RELATIVE, 10, 5) {
                    override fun onAdjustVolume(direction: Int) {
                        if (direction != 0) stop(context)
                    }
                })
                setPlaybackState(
                    android.media.session.PlaybackState.Builder()
                        .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f)
                        .build(),
                )
                isActive = true
            }
        }.onFailure { Log.w("KinWaypoint", "touches de volume non captées", it) }.getOrNull()
    }

    private fun showNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Faire sonner le téléphone", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) }, // le son est joué à part, sur le canal alarme
            )
        }
        val stop = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StopRingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ta famille cherche ce téléphone")
            .setContentText("Appuie sur une touche de volume, ou sur « Arrêter », pour couper la sonnerie.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(0, "Arrêter", stop)
            .setDeleteIntent(stop)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** Bouton « Arrêter » de la notification de sonnerie. */
class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RingAlarm.stop(context)
    }
}
