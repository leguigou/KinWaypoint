package fr.kinwaypoint.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.kinwaypoint.app.MainActivity
import fr.kinwaypoint.app.R
import fr.kinwaypoint.app.data.FixQueue
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import fr.kinwaypoint.app.net.LocationFix
import fr.kinwaypoint.app.net.TrackerClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Service de premier plan qui lit la position et la pousse sur le serveur.
 * Le type `location` est obligatoire depuis Android 10 : sans lui, le système
 * coupe le service dès que l'écran s'éteint.
 *
 * Principes :
 * - GPS et réseau sont écoutés tous les deux, mais **une seule** position part
 *   par intervalle : la meilleure des deux (plus de doublons ni de « sauts »).
 * - Toute position passe par une file persistante : sans réseau, rien n'est
 *   perdu, l'historique repart au retour de la connexion.
 * - Le SOS part immédiatement avec la dernière position connue, puis la
 *   prochaine mesure fraîche est envoyée sans attendre l'intervalle.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "fr.kinwaypoint.app.action.START"
        const val ACTION_SOS = "fr.kinwaypoint.app.action.SOS"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "KinWaypoint"
        private const val TWO_MINUTES = 2 * 60 * 1000L
        private const val MAX_RETRY_DELAY_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun sos(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_SOS)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }

        /**
         * Choix entre deux mesures (algorithme recommandé par la doc Android) :
         * une mesure nettement plus récente gagne, sinon la plus précise.
         */
        internal fun isBetter(candidate: Location, current: Location?): Boolean {
            if (current == null) return true
            val timeDelta = candidate.time - current.time
            if (timeDelta > TWO_MINUTES) return true
            if (timeDelta < -TWO_MINUTES) return false
            val isNewer = timeDelta > 0

            val accuracyDelta = candidate.accuracy - current.accuracy
            val moreAccurate = accuracyDelta < 0
            val lessAccurate = accuracyDelta > 0
            val muchLessAccurate = accuracyDelta > 200
            val sameProvider = candidate.provider == current.provider

            return when {
                moreAccurate -> true
                isNewer && !lessAccurate -> true
                isNewer && !muchLessAccurate && sameProvider -> true
                else -> false
            }
        }
    }

    // Une erreur imprévue dans un envoi est journalisée, elle ne doit jamais tuer le suivi.
    private val errors = CoroutineExceptionHandler { _, e -> Log.e(TAG, "erreur dans le suivi", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val client = TrackerClient()
    private lateinit var queue: FixQueue
    private lateinit var locationManager: LocationManager
    private var connectivity: ConnectivityManager? = null

    @Volatile private var config: TrackingConfig? = null
    @Volatile private var intervalMs: Long = 60_000L
    @Volatile private var started = false

    // Sélection des positions (thread principal uniquement).
    private var best: Location? = null
    private var lastQueuedElapsed = 0L
    private var lastQueuedFixTime = 0L
    private var sendNextImmediately = false
    @Volatile private var sosWaitingForFix = false

    // Envoi : une seule boucle à la fois, relancée si besoin.
    private val flushLock = Mutex()
    @Volatile private var flushRequested = false
    private var retryJob: Job? = null
    private var retryDelayMs = 30_000L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            flush()
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        queue = FixQueue.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasFineLocation()) {
            Log.w(TAG, "permission de localisation absente, arrêt du service")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification("En attente d'une position…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "démarrage au premier plan refusé", e)
            stopSelf()
            return START_NOT_STICKY
        }

        TrackingStatus.update { it.copy(running = true) }

        if (!started) {
            started = true
            startTracking()
        }

        if (intent?.action == ACTION_SOS) {
            triggerSos()
        }
        return START_STICKY
    }

    private fun startTracking() {
        scope.launch {
            val cfg = SettingsRepository(applicationContext).snapshot()
            config = cfg
            intervalMs = cfg.intervalSeconds.coerceIn(15, 3600) * 1000L
            refreshQueueStatus()
            withContext(Dispatchers.Main) {
                registerLocation()
                registerNetwork()
            }
            flush()
        }
    }

    @SuppressLint("MissingPermission") // vérifiée dans onStartCommand, SecurityException rattrapée
    private fun registerLocation() {
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

            if (providers.isEmpty()) {
                Log.w(TAG, "aucun fournisseur de position actif")
            }

            providers.forEach { provider ->
                locationManager.requestLocationUpdates(provider, intervalMs, 0f, this, Looper.getMainLooper())
            }

            // Une dernière position connue permet d'afficher quelque chose tout de suite.
            lastKnownLocation()?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "permission refusée pendant l'enregistrement", e)
            stopSelf()
        }
    }

    private fun registerNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { connectivity = manager }
            .onFailure { Log.w(TAG, "suivi du réseau indisponible", it) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    // --- Sélection : une seule position par intervalle -----------------------

    override fun onLocationChanged(location: Location) {
        if (isBetter(location, best)) best = location
        val candidate = best ?: return

        TrackingStatus.update { it.copy(accuracy = candidate.accuracy) }

        if (sosWaitingForFix) {
            sosWaitingForFix = false
            sendNextImmediately = false
            enqueue(candidate, alarm = "SOS")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val tolerance = maxOf(5_000L, intervalMs / 10)
        val due = sendNextImmediately || lastQueuedElapsed == 0L || now - lastQueuedElapsed >= intervalMs - tolerance
        if (!due) return
        if (candidate.time <= lastQueuedFixTime) return // déjà envoyée, rien de neuf

        sendNextImmediately = false
        enqueue(candidate, alarm = null)
    }

    // Jusqu'à Android 10, ces méthodes sont abstraites dans LocationListener :
    // sans implémentation explicite, l'appli plante quand le GPS est (dés)activé.
    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Requis jusqu'à Android 10")
    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun enqueue(location: Location, alarm: String?, sosNow: Long? = null) {
        lastQueuedElapsed = SystemClock.elapsedRealtime()
        if (sosNow == null) lastQueuedFixTime = location.time

        val (battery, charging) = readBattery()
        val fixTime = if (location.time > 0L) location.time else System.currentTimeMillis()
        val fix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = location.altitude,
            speed = location.speed,
            bearing = location.bearing,
            // Le SOS est horodaté « maintenant » : le serveur le traite comme la
            // position la plus récente, et l'âge réel de la mesure part à côté.
            time = sosNow ?: fixTime,
            battery = battery,
            charging = charging,
            alarm = alarm,
            fixAgeSeconds = sosNow?.let { ((it - fixTime) / 1000L).coerceAtLeast(0L) },
        )

        TrackingStatus.update { it.copy(battery = battery) }
        scope.launch {
            queue.add(fix)
            refreshQueueStatus()
            flush()
        }
    }

    // --- SOS ------------------------------------------------------------------

    private fun triggerSos() {
        val now = System.currentTimeMillis()
        TrackingStatus.update { it.copy(sosPending = true, sosDeliveredAt = null) }

        val known = best ?: lastKnownLocation()
        if (known != null) {
            enqueue(known, alarm = "SOS", sosNow = now)
            // Puis la prochaine mesure fraîche part sans attendre l'intervalle.
            sendNextImmediately = true
        } else {
            // Aucune position du tout : l'alerte partira avec la première mesure.
            sosWaitingForFix = true
        }
        notify("Alerte SOS en cours d'envoi…")
    }

    // --- Envoi ----------------------------------------------------------------

    private fun flush() {
        scope.launch {
            if (!flushLock.tryLock()) {
                flushRequested = true
                return@launch
            }
            try {
                do {
                    flushRequested = false
                    drain()
                } while (flushRequested)
            } finally {
                flushLock.unlock()
            }
        }
    }

    private suspend fun drain() {
        val cfg = config ?: return
        while (true) {
            val batch = queue.peek(50)
            if (batch.isEmpty()) break

            for (entry in batch) {
                val result = client.send(cfg.serverUrl, cfg.deviceId, entry.fix)
                if (result.isFailure) {
                    onSendFailure(result.exceptionOrNull())
                    return
                }
                queue.remove(entry.id)
                onSendSuccess(entry.fix)
            }
        }
        retryJob?.cancel()
        retryDelayMs = 30_000L
    }

    private fun onSendSuccess(fix: LocationFix) {
        val now = System.currentTimeMillis()
        val pending = queue.hasPendingAlarm() || sosWaitingForFix
        TrackingStatus.update {
            it.copy(
                lastSentAt = now,
                lastOk = true,
                lastError = null,
                queued = queue.size(),
                sosPending = pending,
                sosDeliveredAt = if (fix.alarm != null) now else it.sosDeliveredAt,
            )
        }
        notify(
            when {
                fix.alarm != null -> "Alerte SOS reçue par le serveur"
                else -> "Position envoyée · ${fix.battery}%"
            },
        )
    }

    private fun onSendFailure(error: Throwable?) {
        val message = error?.message ?: error?.javaClass?.simpleName ?: "erreur inconnue"
        Log.w(TAG, "envoi impossible : $message")
        val waiting = queue.size()
        TrackingStatus.update { it.copy(lastOk = false, lastError = message, queued = waiting) }
        notify("Hors ligne — $waiting position(s) en attente")
        scheduleRetry()
    }

    /** Nouvel essai après 30 s, 1 min, 2 min… plafonné à 5 min (et dès le retour du réseau). */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        retryJob = scope.launch {
            delay(wait)
            flush()
        }
    }

    private fun refreshQueueStatus() {
        val waiting = queue.size()
        val pending = queue.hasPendingAlarm()
        TrackingStatus.update { it.copy(queued = waiting, sosPending = pending || it.sosPending && sosWaitingForFix) }
    }

    // --- Divers ---------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun readBattery(): Pair<Int, Boolean> {
        val manager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level <= 0 && sticky != null) {
            val raw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) level = raw * 100 / scale
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return level.coerceIn(0, 100) to charging
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Position envoyée au serveur familial"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        connectivity?.let { manager -> runCatching { manager.unregisterNetworkCallback(networkCallback) } }
        scope.cancel()
        TrackingStatus.update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
