package fr.kinwaypoint.app.service

import android.content.Context
import android.util.Log
import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.net.AudioRelay
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.flow.first

/**
 * Demandes d'un parent pour CE téléphone (faire sonner, écoute en direct),
 * posées en attributs sur son appareil Traccar (kwRing, kwListen).
 *
 * Pourquoi pas une commande Traccar ? Dès qu'un téléphone a un jeton Firebase,
 * Traccar transmet toutes les commandes par Firebase avec leur seul type, sans
 * leur contenu : « ring » ou la salle d'écoute se perdaient. Le message Firebase
 * sert donc de réveil, et le téléphone vient lire la demande ici.
 *
 * Lecture avec le jeton parent (téléphone parent qui partage sa position) ou le
 * jeton lecteur des enfants (téléphone enfant configuré avec l'accès famille).
 */
object RemoteActions {
    private const val TAG = "KinWaypoint"
    private const val MAX_AGE_MS = 3 * 60_000L
    @Volatile private var lastCheck = 0L

    /** À appeler au réveil Firebase (force = true) et après chaque envoi de position. */
    suspend fun check(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000L) return
        lastCheck = now
        val repository = SettingsRepository(context.applicationContext)
        val tracking = repository.config.first()
        if (tracking.deviceId.isBlank()) return
        val parent = repository.parent.first()
        val (apiUrl, token) = when {
            repository.mode.first() == AppMode.PARENT && parent.isConnected -> parent.apiUrl to parent.token
            tracking.canSeeParents -> tracking.apiUrl to tracking.viewerToken
            else -> return
        }
        val attrs = runCatching { TraccarApi(apiUrl).pendingActions(token, tracking.deviceId) }
            .onFailure { Log.w(TAG, "demandes à distance illisibles : ${it.message}") }
            .getOrNull() ?: return
        val prefs = context.getSharedPreferences("remote_actions", Context.MODE_PRIVATE)

        // Cadences réglées par un parent (kwIntNormal / kwIntMoving / kwIntStill).
        val normal = attrs.optInt("kwIntNormal", 0)
        val moving = attrs.optInt("kwIntMoving", 0)
        val still = attrs.optInt("kwIntStill", 0)
        if (normal > 0 && moving > 0 && still > 0) {
            val current = repository.config.first()
            if (current.intervalSeconds != normal || current.movingSeconds != moving || current.stillSeconds != still) {
                repository.save(current.copy(intervalSeconds = normal, movingSeconds = moving, stillSeconds = still))
                Log.i(TAG, "cadences reçues des parents : $normal s / $moving s / $still s")
                TrackingService.reload(context.applicationContext)
            }
        }

        // Arrêt de la sonnerie demandé par un parent (plus récent que la demande de sonner).
        val ring = attrs.optLong("kwRing", 0L)
        val ringStop = attrs.optLong("kwRingStop", 0L)
        if (ringStop > prefs.getLong("ringStop", 0L) && now - ringStop in -60_000L..MAX_AGE_MS) {
            prefs.edit().putLong("ringStop", ringStop).apply()
            Log.i(TAG, "demande à distance : arrêter la sonnerie")
            RingAlarm.stop(context.applicationContext)
        }

        // Sonnerie : demande récente, pas encore traitée, et pas annulée depuis.
        if (ring > ringStop && ring > prefs.getLong("ring", 0L) && ring > RingAlarm.lastStoppedAt &&
            now - ring in -60_000L..MAX_AGE_MS
        ) {
            prefs.edit().putLong("ring", ring).apply()
            Log.i(TAG, "demande à distance : faire sonner")
            RingAlarm.start(context.applicationContext)
        }

        // Écoute en direct : commande horodatée (2 min), une seule fois par salle.
        AudioRelay.parse(attrs.optString("kwListen"))?.let { request ->
            if (prefs.getString("listenRoom", null) != request.room) {
                prefs.edit().putString("listenRoom", request.room).apply()
                Log.i(TAG, "demande à distance : écoute en direct")
                ListenService.request(context.applicationContext, request.room, request.secret)
            }
        }
    }
}
