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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
import kotlinx.coroutines.flow.first

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
 * - Fréquence adaptative : « à l'arrêt » → GPS coupé, une position toutes les
 *   10 min via Wi-Fi / réseau mobile. On s'y met dès que le podomètre ne compte
 *   plus de pas ET que le téléphone ne bouge plus (accéléromètre) depuis 3 min,
 *   même si le GPS « tremble » ; sinon après 5 min au même endroit selon le GPS.
 *   On en sort aux premiers pas, au capteur « mouvement significatif » ou sur un
 *   déplacement vu par le réseau : le GPS repart aussitôt. Sans Play Services.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "fr.kinwaypoint.app.action.START"
        const val ACTION_SOS = "fr.kinwaypoint.app.action.SOS"
        /** Les cadences ont changé (réglées par un parent) : on les recharge. */
        const val ACTION_RELOAD = "fr.kinwaypoint.app.action.RELOAD"
        /** Un parent demande une position tout de suite (réveil Firebase). */
        const val ACTION_PING = "fr.kinwaypoint.app.action.PING"
        /** L'enfant signale « je suis bien arrivé·e ». */
        const val ACTION_CHECKIN = "fr.kinwaypoint.app.action.CHECKIN"
        /** Notification balayée par l'utilisateur : on la réaffiche (le suivi continue). */
        const val ACTION_RESHOW = "fr.kinwaypoint.app.action.RESHOW"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "KinWaypoint"
        private const val TWO_MINUTES = 2 * 60 * 1000L
        private const val MAX_RETRY_DELAY_MS = 5 * 60 * 1000L

        /** Durée sans déplacement (selon le GPS) avant de passer en mode « à l'arrêt ». */
        private const val STILL_AFTER_MS = 5 * 60 * 1000L
        /** Aucun pas et téléphone immobile depuis ce temps → « à l'arrêt », sans attendre le GPS. */
        private const val CALM_STILL_MS = 3 * 60 * 1000L
        /** Pas comptés depuis la mise « à l'arrêt » qui suffisent à en sortir. */
        private const val STEPS_TO_WAKE = 15
        /** Écart d'accélération (m/s²) sur quelques secondes au-delà duquel le téléphone bouge. */
        private const val SHAKE_THRESHOLD = 0.6f
        /** Cadence d'envoi à l'arrêt (sert aussi de signe de vie pour les parents). */
        private const val STILL_INTERVAL_MS = 10 * 60 * 1000L
        /** Suivi rapproché après un mouvement détecté : une position toutes les 15 s… */
        private const val BOOST_INTERVAL_MS = 15_000L
        /** Au-delà de cette marge d'erreur (m), une position est « grossière » (antennes, Wi-Fi lointain). */
        private const val COARSE_ACCURACY_M = 100f
        /** Une position précise sert de référence pendant 6 h au plus. */
        private const val RELIABLE_MAX_AGE_MS = 6 * 3600 * 1000L
        /** … pendant 5 min après la dernière preuve de mouvement (pas ou vitesse). */
        private const val BOOST_DURATION_MS = 5 * 60 * 1000L

        /** Rayon dans lequel on considère l'appareil immobile (m), selon la précision. */
        internal fun stillRadius(accuracy: Float) = 75f + accuracy.coerceIn(0f, 150f)

        /** Distance à partir de laquelle on sort du mode « à l'arrêt » (m). */
        internal fun moveRadius(accuracy: Float) = 200f + accuracy.coerceIn(0f, 300f)

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun sos(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_SOS)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Réveil à distance : envoie une position immédiatement. La réponse du
         * serveur contient les commandes en attente (ex. « ring »).
         */
        fun ping(context: Context) = deliver(context, ACTION_PING)

        fun reload(context: Context) = deliver(context, ACTION_RELOAD)

        fun checkIn(context: Context) = deliver(context, ACTION_CHECKIN)

        private fun deliver(context: Context, action: String) {
            val intent = Intent(context, TrackingService::class.java).setAction(action)
            // Service déjà au premier plan : simple startService (autorisé depuis l'arrière-plan).
            runCatching {
                if (TrackingStatus.state.value.running) context.startService(intent)
                else ContextCompat.startForegroundService(context, intent)
            }.onFailure { Log.w(TAG, "réveil du suivi impossible", it) }
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
    /** Cadences en mouvement et à l'arrêt (réglables par les parents). */
    @Volatile private var movingMs: Long = BOOST_INTERVAL_MS
    @Volatile private var stillMs: Long = STILL_INTERVAL_MS
    @Volatile private var started = false

    // Sélection des positions (thread principal uniquement).
    private var best: Location? = null
    private var lastQueuedElapsed = 0L
    private var lastQueuedFixTime = 0L
    private var sendNextImmediately = false
    @Volatile private var sosWaitingForFix = false

    // Fréquence adaptative (thread principal uniquement).
    private var still = false
    /** À l'arrêt, on renvoie cette position (la dernière avant l'arrêt) au lieu du bruit GPS. */
    private var snapTo: Location? = null
    /** Point précédent, pour détecter un déplacement entre deux mesures espacées. */
    private var lastSpeedFix: Location? = null
    private var lastQueuedLocation: Location? = null
    /** Dernière position précise (≤ COARSE_ACCURACY_M) : référence contre les fausses sorties de zone. */
    private var lastReliable: Location? = null
    private var anchor: Location? = null
    private var anchorSinceElapsed = 0L
    private var sensorManager: SensorManager? = null
    private var motionSensor: Sensor? = null
    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.i(TAG, "mouvement détecté par le capteur")
            if (still) enterMoving()
        }
    }

    // Indices d'immobilité : derniers pas et dernier mouvement du téléphone.
    private var accelerometer: Sensor? = null
    private var accelWatching = false
    private var accelSinceElapsed = 0L
    private var lastShakeElapsed = 0L
    private var lastStepElapsed = 0L
    private var lastStepTotal = -1L
    private var stepsAtStill = -1L
    private var bucketStart = 0L
    private var bucketMin = Float.MAX_VALUE
    private var bucketMax = -Float.MAX_VALUE
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
            val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
            val now = SystemClock.elapsedRealtime()
            bucketMin = minOf(bucketMin, magnitude)
            bucketMax = maxOf(bucketMax, magnitude)
            // Par tranches de 5 s : posé sur une table, l'écart reste sous 0,1 m/s².
            if (now - bucketStart >= 5_000) {
                if (bucketMax - bucketMin > SHAKE_THRESHOLD) lastShakeElapsed = now
                bucketStart = now
                bucketMin = Float.MAX_VALUE
                bucketMax = -Float.MAX_VALUE
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Accéléromètre écouté seulement hors mode « à l'arrêt » (là, le capteur de mouvement suffit). */
    private fun watchPhoneMotion(on: Boolean) {
        val sensor = accelerometer ?: return
        if (on == accelWatching) return
        accelWatching = on
        if (on) {
            accelSinceElapsed = SystemClock.elapsedRealtime()
            lastShakeElapsed = 0L
            bucketStart = accelSinceElapsed
            // Mesures groupées par lots de 10 s : le processeur n'est pas réveillé à chaque mesure.
            runCatching { sensorManager?.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000) }
        } else {
            runCatching { sensorManager?.unregisterListener(accelListener) }
        }
    }

    // Podomètre et analyse du mouvement.
    private lateinit var stepCounter: fr.kinwaypoint.app.motion.StepCounter
    private val analyzer = fr.kinwaypoint.app.motion.MotionAnalyzer()
    private var boostUntilElapsed = 0L
    private var boosting = false

    /** Intervalle effectif : 10 min à l'arrêt, 15 s en suivi rapproché, sinon celui réglé. */
    private val currentIntervalMs: Long
        get() = when {
            still -> maxOf(intervalMs, stillMs)
            boosting -> minOf(intervalMs, movingMs)
            else -> intervalMs
        }

    /**
     * Mouvement détecté (pas réguliers ou vitesse) : on sort du mode « à l'arrêt »
     * et on resserre le suivi pendant 5 min pour mesurer la vitesse.
     */
    private fun movementDetected() {
        boostUntilElapsed = SystemClock.elapsedRealtime() + BOOST_DURATION_MS
        if (still) enterMoving()
        if (!boosting && intervalMs > movingMs) {
            boosting = true
            Log.i(TAG, "mouvement : suivi rapproché (15 s) pendant 5 min")
            TrackingStatus.update { it.copy(boosting = true) }
            registerLocation()
        }
    }

    private fun checkBoostExpired() {
        if (boosting && SystemClock.elapsedRealtime() > boostUntilElapsed) {
            boosting = false
            Log.i(TAG, "fin du suivi rapproché")
            TrackingStatus.update { it.copy(boosting = false) }
            registerLocation()
        }
    }

    private var stepsStarted = false

    /** Podomètre : autorisation « activité physique » requise depuis Android 10. */
    private fun startStepsIfAllowed() {
        if (stepsStarted) return
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        stepsStarted = stepCounter.start()
        if (!stepsStarted) Log.i(TAG, "pas de podomètre sur ce téléphone")
        // Pas du jour connus tout de suite (écran enfant), sans attendre le prochain pas.
        stepCounter.stepsToday?.let { today -> TrackingStatus.update { it.copy(steps = today) } }
    }

    private fun onSteps(today: Int, total: Long) {
        val now = System.currentTimeMillis()
        analyzer.addSteps(now, total)
        TrackingStatus.update { it.copy(steps = today) }
        if (lastStepTotal in 0 until total) lastStepElapsed = SystemClock.elapsedRealtime()
        lastStepTotal = total
        // « À l'arrêt » et la personne se remet à marcher : on repart tout de suite.
        if (still && stepsAtStill >= 0 && total - stepsAtStill >= STEPS_TO_WAKE) {
            Log.i(TAG, "pas détectés : fin du mode à l'arrêt")
            enterMoving()
        }
        // Plus de 40 pas / min : la personne marche.
        if (analyzer.cadence(now) >= 40) movementDetected()
    }

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
        fr.kinwaypoint.app.CrashReport.install(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        queue = FixQueue.get(this)
        sensorManager = getSystemService(SensorManager::class.java)
        motionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepCounter = fr.kinwaypoint.app.motion.StepCounter(this) { today, total -> onSteps(today, total) }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasFineLocation()) {
            Log.w(TAG, "permission de localisation absente, arrêt du service")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Texte courant gardé : une notification réaffichée (ACTION_RESHOW) ne repart pas à zéro.
            val notification = buildNotification(notificationText)
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
        if (runningSince == 0L) runningSince = System.currentTimeMillis()

        if (!started) {
            started = true
            startTracking()
        } else {
            // Autorisation « activité physique » accordée après coup : le podomètre démarre.
            startStepsIfAllowed()
        }

        when (intent?.action) {
            ACTION_RELOAD -> scope.launch {
                applyCadence(SettingsRepository(applicationContext).snapshot())
                withContext(Dispatchers.Main) { registerLocation() }
            }
            ACTION_SOS -> triggerSos()
            ACTION_PING -> sendNow(alarm = null)
            ACTION_CHECKIN -> sendNow(alarm = "general")
        }
        return START_STICKY
    }

    /** Cadences : normale, en mouvement, à l'arrêt (bornées pour rester raisonnables). */
    private fun applyCadence(cfg: fr.kinwaypoint.app.data.TrackingConfig) {
        config = cfg
        intervalMs = cfg.intervalSeconds.coerceIn(15, 3600) * 1000L
        movingMs = cfg.movingSeconds.coerceIn(5, 300) * 1000L
        stillMs = cfg.stillSeconds.coerceIn(120, 3600) * 1000L
        Log.i(TAG, "cadences : ${intervalMs / 1000} s / en mouvement ${movingMs / 1000} s / à l'arrêt ${stillMs / 1000} s")
    }

    private fun startTracking() {
        scope.launch {
            val repository = SettingsRepository(applicationContext)
            repository.migrateDefaults()
            val cfg = repository.snapshot()
            config = cfg
            applyCadence(cfg)
            refreshQueueStatus()
            // Mises à jour : tâche Android planifiée (aussi relancée ici après une mise à jour du téléphone).
            runCatching { fr.kinwaypoint.app.ui.scheduleUpdateChecks(applicationContext) }
            // Chien de garde : relance le suivi s'il est arrêté par Android ou depuis le volet des notifications.
            runCatching { TrackingWatchdog.schedule(applicationContext) }
            withContext(Dispatchers.Main) {
                registerLocation()
                registerNetwork()
                startStepsIfAllowed()
                watchPhoneMotion(true)
            }
            flush()
        }
    }

    /**
     * En mouvement : GPS + réseau à l'intervalle réglé.
     * À l'arrêt : réseau seul toutes les 10 min (GPS seulement s'il n'y a pas
     * de localisation réseau), plus le capteur de mouvement significatif.
     */
    @SuppressLint("MissingPermission") // vérifiée dans onStartCommand, SecurityException rattrapée
    private fun registerLocation() {
        try {
            runCatching { locationManager.removeUpdates(this) }

            val enabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

            val providers = when {
                !still -> enabled
                LocationManager.NETWORK_PROVIDER in enabled -> listOf(LocationManager.NETWORK_PROVIDER)
                else -> enabled
            }

            if (providers.isEmpty()) {
                Log.w(TAG, "aucun fournisseur de position actif")
            }

            providers.forEach { provider ->
                locationManager.requestLocationUpdates(provider, currentIntervalMs, 0f, this, Looper.getMainLooper())
            }

            // Une dernière position connue permet d'afficher quelque chose tout de suite.
            if (best == null) lastKnownLocation()?.let { onLocationChanged(it) }
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

        // Vitesse lissée : au-delà de 5 km/h, la personne se déplace.
        val nowMs = System.currentTimeMillis()
        analyzer.addFix(fr.kinwaypoint.app.motion.MotionAnalyzer.Fix(location.time, location.latitude, location.longitude, location.accuracy))
        val kmh = analyzer.speedKmh(nowMs)
        TrackingStatus.update { it.copy(kmh = kmh, activity = analyzer.activity(nowMs).code) }
        // Avec un point toutes les 5 min, l'analyse sur 2 min n'a pas assez de mesures :
        // on compare aussi au point précédent (déplacement net, au-delà du bruit GPS).
        val movedSincePrevious = lastSpeedFix?.let { prev ->
            val seconds = (location.time - prev.time) / 1000.0
            val meters = location.distanceTo(prev)
            val noise = maxOf(40f, maxOf(prev.accuracy, location.accuracy) * 2.5f)
            seconds in 20.0..900.0 && meters > noise && meters / seconds * 3.6 > 5
        } == true
        lastSpeedFix = location
        if ((kmh != null && kmh > 5) || movedSincePrevious) movementDetected() else checkBoostExpired()

        updateMotion(location)

        if (sosWaitingForFix) {
            sosWaitingForFix = false
            sendNextImmediately = false
            enqueue(candidate, alarm = "SOS")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val interval = currentIntervalMs
        val tolerance = maxOf(5_000L, interval / 10)
        val due = sendNextImmediately || lastQueuedElapsed == 0L || now - lastQueuedElapsed >= interval - tolerance
        if (!due) return
        if (candidate.time <= lastQueuedFixTime) return // déjà envoyée, rien de neuf

        sendNextImmediately = false
        // À l'arrêt : même position à chaque envoi (heure, batterie… à jour). Le
        // tremblement GPS ne crée ni faux trajet, ni faux kilomètres, ni fausse
        // alerte de proximité. Un vrai départ fait sortir du mode « à l'arrêt » avant.
        val toSend = snapTo?.takeIf { still }?.let { anchor ->
            Location(anchor).apply {
                time = candidate.time
                elapsedRealtimeNanos = candidate.elapsedRealtimeNanos
                speed = 0f
            }
        } ?: candidate
        enqueue(guardCoarse(toSend), alarm = null)
    }

    /**
     * GPS coupé puis rallumé, ou intérieur sans GPS : Android ne donne plus qu'une
     * position grossière (antennes, ±500 m à 2 km), souvent décalée. Envoyée
     * telle quelle, elle « sortait » la personne de sa zone (maison, école) puis
     * l'y faisait « rentrer » au retour du GPS : fausses alertes.
     *
     * Si la position grossière reste compatible avec la dernière position précise
     * (distance ≤ somme des deux marges d'erreur), rien ne prouve un déplacement :
     * on renvoie les coordonnées précises, avec la précision réelle (grossière).
     * Une position grossière clairement ailleurs passe telle quelle : vrai départ.
     */
    private fun guardCoarse(location: Location): Location {
        val precise = !location.hasAccuracy() || location.accuracy <= COARSE_ACCURACY_M
        val reference = lastReliable?.takeIf { location.time - it.time in 0..RELIABLE_MAX_AGE_MS }
        if (precise) {
            lastReliable = location
            return location
        }
        if (reference == null) return location
        val compatible = location.distanceTo(reference) <= location.accuracy + reference.accuracy
        if (!compatible) return location
        Log.i(TAG, "position grossière (±${location.accuracy.toInt()} m) compatible avec la dernière précise : coordonnées conservées")
        return Location(reference).apply {
            time = location.time
            elapsedRealtimeNanos = location.elapsedRealtimeNanos
            accuracy = location.accuracy
            speed = 0f
        }
    }

    // --- Fréquence adaptative ------------------------------------------------

    /** Met à jour l'état mouvement / arrêt à partir de chaque mesure reçue. */
    private fun updateMotion(location: Location) {
        // Inutile si l'intervalle réglé est déjà aussi long que celui « à l'arrêt ».
        if (intervalMs >= stillMs) return
        // Mouvement détecté récemment (pas, vitesse) : on ne passe pas « à l'arrêt ».
        if (boosting) return
        val now = SystemClock.elapsedRealtime()
        val ref = anchor

        if (still) {
            if (ref != null && location.distanceTo(ref) > moveRadius(location.accuracy)) {
                Log.i(TAG, "déplacement détecté par le réseau")
                enterMoving()
            }
            return
        }

        // Indices du téléphone : ni pas ni mouvement depuis 3 min → à l'arrêt, sans
        // attendre le GPS (dont la position « tremble » même immobile).
        if (accelWatching && now - accelSinceElapsed >= CALM_STILL_MS) {
            val lastSign = maxOf(lastShakeElapsed, lastStepElapsed)
            if (now - lastSign >= CALM_STILL_MS) {
                Log.i(TAG, "aucun pas et téléphone immobile depuis 3 min")
                enterStill()
                return
            }
        }

        if (ref == null || location.distanceTo(ref) > stillRadius(location.accuracy)) {
            anchor = location
            anchorSinceElapsed = now
        } else if (now - anchorSinceElapsed >= STILL_AFTER_MS) {
            enterStill()
        }
    }

    private fun enterStill() {
        if (still) return
        still = true
        snapTo = lastQueuedLocation ?: best
        stepsAtStill = lastStepTotal
        watchPhoneMotion(false)
        Log.i(TAG, "immobile : passage en mode économie")
        TrackingStatus.update { it.copy(still = true) }
        registerLocation()
        motionSensor?.let { sensor ->
            runCatching { sensorManager?.requestTriggerSensor(motionListener, sensor) }
        }
    }

    private fun enterMoving() {
        val wasStill = still
        still = false
        anchor = null
        anchorSinceElapsed = SystemClock.elapsedRealtime()
        motionSensor?.let { sensor -> runCatching { sensorManager?.cancelTriggerSensor(motionListener, sensor) } }
        stepsAtStill = -1L
        snapTo = null
        watchPhoneMotion(true)
        TrackingStatus.update { it.copy(still = false) }
        if (wasStill) {
            Log.i(TAG, "en mouvement : retour au GPS")
            sendNextImmediately = true
            registerLocation()
        }
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
        if (alarm == null && sosNow == null && !still) lastQueuedLocation = location

        val (battery, charging) = readBattery()
        // Batterie faible : une seule alerte sous 15 % ; réarmée après recharge (≥ 25 % ou en charge).
        var alarm = alarm
        if (charging || battery >= 25) pushPrefs.edit().putBoolean("lowBatterySent", false).apply()
        if (alarm == null && !charging && battery in 1..15 && !pushPrefs.getBoolean("lowBatterySent", false)) {
            alarm = "lowBattery"
            pushPrefs.edit().putBoolean("lowBatterySent", true).apply()
        }
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
            ringer = readRinger(),
            steps = stepCounter.stepsToday,
            activity = analyzer.activity(System.currentTimeMillis()).code,
            kmh = analyzer.speedKmh(System.currentTimeMillis())?.let { Math.round(it * 10) / 10.0 },
            wifi = readWifiName(),
            wifiOn = runCatching {
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
            }.getOrNull(),
        )

        TrackingStatus.update { it.copy(battery = battery) }
        scope.launch {
            queue.add(fix)
            refreshQueueStatus()
            flush()
        }
    }

    /**
     * Envoie tout de suite la dernière position connue (horodatée maintenant),
     * puis la prochaine mesure fraîche sans attendre l'intervalle.
     * alarm = "general" : « je suis bien arrivé·e ».
     */
    private fun sendNow(alarm: String?) {
        if (still) enterMoving()
        sendNextImmediately = true
        val known = best ?: lastKnownLocation()
        if (known != null) {
            enqueue(known, alarm = alarm, sosNow = System.currentTimeMillis())
        } else {
            flush()
        }
    }

    // --- SOS ------------------------------------------------------------------

    private fun triggerSos() {
        val now = System.currentTimeMillis()
        TrackingStatus.update { it.copy(sosPending = true, sosDeliveredAt = null) }

        // Un SOS rallume le GPS pour obtenir au plus vite une position fraîche.
        enterMoving()
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
                val token = pushTokenToSend()
                val result = client.send(cfg.serverUrl, cfg.deviceId, entry.fix, token)
                if (result.isFailure) {
                    // Garde-fou : une position refusée 10 fois de suite alors que le
                    // réseau fonctionne est écartée, sinon elle bloquerait toute la file.
                    if (entry.id == lastFailedId) failedCount++ else { lastFailedId = entry.id; failedCount = 1 }
                    if (failedCount >= 10 && networkValidated()) {
                        Log.w(TAG, "position ${entry.id} refusée 10 fois : écartée")
                        queue.remove(entry.id)
                        failedCount = 0
                        continue
                    }
                    onSendFailure(result.exceptionOrNull())
                    return
                }
                failedCount = 0
                token?.let { markPushTokenSent(it) }
                queue.remove(entry.id)
                onSendSuccess(entry.fix)
                handleServerCommands(result.getOrNull().orEmpty())
            }
        }
        retryJob?.cancel()
        retryDelayMs = 30_000L
    }

    private var lastFailedId = -1L
    private var failedCount = 0

    private fun networkValidated(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    // --- Commandes à distance -----------------------------------------------------

    private val pushPrefs by lazy { getSharedPreferences("push", Context.MODE_PRIVATE) }

    /** Jeton Firebase à transmettre au serveur s'il ne l'a pas encore été (sinon null). */
    private suspend fun pushTokenToSend(): String? {
        val token = SettingsRepository(applicationContext).pushToken.first() ?: return null
        return token.takeIf { pushPrefs.getString("sent", null) != it }
    }

    private fun markPushTokenSent(token: String) {
        pushPrefs.edit().putString("sent", token).apply()
    }

    /** Traccar renvoie les commandes en attente dans la réponse : « ring » = faire sonner. */
    private fun handleServerCommands(body: String) {
        if (body.isBlank()) return
        val lines = body.lines().map { it.trim() }
        // « ringStop » après « ring » dans la même réponse : l'arrêt l'emporte.
        if (lines.any { it.equals("ringStop", ignoreCase = true) }) {
            Log.i(TAG, "commande reçue : arrêter la sonnerie")
            RingAlarm.stop(applicationContext)
        } else if (lines.any { it.equals("ring", ignoreCase = true) }) {
            Log.i(TAG, "commande reçue : faire sonner")
            RingAlarm.start(applicationContext)
        }
        // Écoute en direct demandée par un parent (toujours visible sur ce téléphone).
        body.lines().firstNotNullOfOrNull { fr.kinwaypoint.app.net.AudioRelay.parse(it) }?.let { request ->
            Log.i(TAG, "commande reçue : écoute en direct")
            ListenService.request(applicationContext, request.room, request.secret)
        }
    }

    private fun onSendSuccess(fix: LocationFix) {
        // Secours si le réveil Firebase n'est pas arrivé : demandes relues (au plus une fois par minute).
        scope.launch { runCatching { RemoteActions.check(applicationContext) } }
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

    /** Sonnerie : normal / vibreur / silencieux (affiché aux parents). */
    private fun readRinger(): String? = runCatching {
        when ((getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).ringerMode) {
            android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    }.getOrNull()

    /** Nom du Wi-Fi connecté (null si pas de Wi-Fi ou nom masqué par Android). */
    @Suppress("DEPRECATION")
    private fun readWifiName(): String? = runCatching {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

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
            // Android 14+ laisse balayer la notification d'un service au premier plan :
            // le suivi continue, et la notification revient aussitôt pour rester visible.
            .setDeleteIntent(
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, TrackingService::class.java).setAction(ACTION_RESHOW),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private var notificationText = "En attente d'une position…"
    /** Début du fonctionnement au premier plan (0 = jamais démarré correctement). */
    private var runningSince = 0L

    private fun notify(text: String) {
        notificationText = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Appli balayée des applis récentes : le service continue normalement, mais
     * certaines surcouches (Xiaomi, Huawei, Samsung…) le tuent quand même. On
     * programme un contrôle dans 30 s qui le relance si besoin.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { TrackingWatchdog.checkSoon(applicationContext) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Arrêt non demandé par l'appli (Android, volet « Applications actives ») :
        // le chien de garde vérifiera dans 30 s. S'il a été arrêté volontairement
        // (suivi désactivé), il ne fera rien.
        // Seulement après un vrai fonctionnement (> 1 min) : un démarrage refusé par
        // Android ne doit pas boucler toutes les 30 s.
        if (runningSince > 0L && System.currentTimeMillis() - runningSince > 60_000L) {
            runCatching { TrackingWatchdog.checkSoon(applicationContext) }
        }
        runCatching { locationManager.removeUpdates(this) }
        motionSensor?.let { sensor -> runCatching { sensorManager?.cancelTriggerSensor(motionListener, sensor) } }
        stepCounter.stop()
        watchPhoneMotion(false)
        connectivity?.let { manager -> runCatching { manager.unregisterNetworkCallback(networkCallback) } }
        scope.cancel()
        TrackingStatus.update { it.copy(running = false, still = false, boosting = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
