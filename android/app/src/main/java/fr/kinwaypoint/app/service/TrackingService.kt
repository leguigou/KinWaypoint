package fr.kinwaypoint.app.service

import android.Manifest
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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.kinwaypoint.app.MainActivity
import fr.kinwaypoint.app.R
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import fr.kinwaypoint.app.net.LocationFix
import fr.kinwaypoint.app.net.TrackerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service de premier plan qui lit la position et la pousse sur le serveur.
 * Le type `location` est obligatoire depuis Android 10 : sans lui, le système
 * coupe le service dès que l'écran s'éteint.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "fr.kinwaypoint.app.action.START"
        const val ACTION_SOS = "fr.kinwaypoint.app.action.SOS"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "KinWaypoint"

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = TrackerClient()
    private lateinit var locationManager: LocationManager
    private var config: TrackingConfig? = null
    private var pendingAlarm: String? = null
    private var lastLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SOS) {
            pendingAlarm = "SOS"
            TrackingStatus.update { it.copy(sosPending = true) }
        }

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
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        scope.launch {
            val cfg = SettingsRepository(applicationContext).snapshot()
            config = cfg
            val intervalMs = cfg.intervalSeconds.coerceIn(15, 3600) * 1000L
            withContext(Dispatchers.Main) { register(intervalMs) }
        }
    }

    private fun register(intervalMs: Long) {
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
            providers.asSequence()
                .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "permission refusée pendant l'enregistrement", e)
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        send(location)
    }

    private fun send(location: Location) {
        val cfg = config ?: return
        val alarm = pendingAlarm
        val (battery, charging) = readBattery()

        val fix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = location.altitude,
            speed = location.speed,
            bearing = location.bearing,
            time = if (location.time > 0L) location.time else System.currentTimeMillis(),
            battery = battery,
            charging = charging,
            alarm = alarm,
        )

        scope.launch {
            var success = false
            for (attempt in 1..3) {
                if (client.send(cfg, fix).isSuccess) {
                    success = true
                    break
                }
                if (attempt < 3) delay(10_000L)
            }

            if (success && alarm != null) {
                pendingAlarm = null
            }

            TrackingStatus.update {
                it.copy(
                    lastSentAt = System.currentTimeMillis(),
                    lastOk = success,
                    battery = battery,
                    accuracy = location.accuracy,
                    sosPending = pendingAlarm != null,
                )
            }

            val text = when {
                !success -> "Hors ligne — nouvelle tentative à la prochaine position"
                alarm != null -> "Alerte SOS envoyée"
                else -> "Position envoyée · ${battery}%"
            }
            notify(text)
        }
    }

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
        scope.cancel()
        TrackingStatus.update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
