package fr.kinwaypoint.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kinwaypoint")

/** Cadence normale d'envoi des positions (s) : 5 min. */
const val DEFAULT_INTERVAL_SECONDS = 300

/** Cadence en mouvement (s) : 15 s. */
const val DEFAULT_MOVING_SECONDS = 15

/** Cadence à l'arrêt (s) : 10 min. */
const val DEFAULT_STILL_SECONDS = 600

/** Rôle de l'appareil, choisi au premier lancement. */
enum class AppMode { UNSET, CHILD, PARENT }

/**
 * Réglages du suivi de CE téléphone : où et à quelle cadence envoyer la position.
 * Sert au mode enfant, et au mode parent quand « Partager ma position » est activé.
 */
data class TrackingConfig(
    val serverUrl: String = "",
    val deviceId: String = "",
    /** Cadence normale : 5 min. En mouvement détecté : 15 s ; à l'arrêt : 10 min. */
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val enabled: Boolean = false,
    /** Mode enfant : adresse de l'API, pour voir les parents (« Voir mes parents »). */
    val apiUrl: String = "",
    /** Mode enfant : jeton du compte « Enfants (lecture seule) » ; vide = pas d'accès. */
    val viewerToken: String = "",
    /** Cadence quand un déplacement est détecté (s) : 15 s par défaut. */
    val movingSeconds: Int = DEFAULT_MOVING_SECONDS,
    /** Cadence à l'arrêt, GPS coupé (s) : 10 min par défaut. */
    val stillSeconds: Int = DEFAULT_STILL_SECONDS,
    /**
     * Économie d'énergie sévère sous ce niveau de batterie (%), réglée par un
     * parent pour toute la famille (attribut kwSaverBelow) ; 0 = jamais.
     */
    val saverBelow: Int = 0,
) {
    val canSeeParents: Boolean get() = apiUrl.isNotBlank() && viewerToken.isNotBlank()

    val isValid: Boolean get() = deviceId.isNotBlank() && serverUrl.isNotBlank()
}

/** Réglages du mode parent : accès à l'API Traccar (jeton, jamais le mot de passe). */
data class ParentConfig(
    val apiUrl: String = "",
    /** Adresse d'envoi des positions (port 5055), mise dans les QR codes de configuration. */
    val trackUrl: String = "",
    val email: String = "",
    val token: String = "",
) {
    val isConnected: Boolean get() = apiUrl.isNotBlank() && token.isNotBlank()
}

/** Code PIN parent, stocké uniquement sous forme d'empreinte salée. */
data class PinState(
    val hash: String = "",
    val salt: String = "",
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0L,
) {
    val isSet: Boolean get() = hash.isNotBlank() && salt.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("app_mode")

        val SERVER = stringPreferencesKey("server_url")
        val DEVICE = stringPreferencesKey("device_id")
        val INTERVAL = stringPreferencesKey("interval_seconds")
        val ENABLED = booleanPreferencesKey("enabled")

        val API_URL = stringPreferencesKey("parent_api_url")
        val EMAIL = stringPreferencesKey("parent_email")
        val TOKEN = stringPreferencesKey("parent_token")
        val TRACK_URL = stringPreferencesKey("parent_track_url")

        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_FAILS = intPreferencesKey("pin_failed_attempts")
        val PIN_LOCKED_UNTIL = longPreferencesKey("pin_locked_until")

        val ACK_ALARM = longPreferencesKey("parent_ack_alarm_id")
        /** Dernière ouverture de l'écran des notifications (pastille de la cloche). */
        val EVENTS_SEEN = longPreferencesKey("parent_events_seen_at")

        val BIOMETRIC = booleanPreferencesKey("parent_biometric")
        val BIOMETRIC_OFFER_DISMISSED = booleanPreferencesKey("parent_biometric_offer_dismissed")

        val VIEW_API = stringPreferencesKey("viewer_api_url")
        val VIEW_TOKEN = stringPreferencesKey("viewer_token")

        /** Mode parent : appareil Traccar de CE téléphone (« Partager ma position »). */
        val OWN_DEVICE_ID = longPreferencesKey("parent_own_device_id")
        /** Mode parent : jeton du compte lecteur des enfants, mis en cache pour les QR codes. */
        val CHILD_VIEWER_TOKEN = stringPreferencesKey("parent_child_viewer_token")
        val CHILD_VIEWER_EXPIRES = longPreferencesKey("parent_child_viewer_expires")

        /** Jeton FCM du téléphone, enregistré sur le compte Traccar. */
        val PUSH_TOKEN = stringPreferencesKey("push_token")

        val PROXIMITY_ON = booleanPreferencesKey("proximity_alert_on")
        val PROXIMITY_RADIUS = intPreferencesKey("proximity_alert_radius")

        val ALLOW_LISTEN = booleanPreferencesKey("allow_remote_listen")

        val DEFAULTS_VERSION = intPreferencesKey("defaults_version")

        val RELOCK_MINUTES = intPreferencesKey("parent_relock_minutes")
        val LAST_ACTIVE = longPreferencesKey("app_last_active_at")
        val PARENT_UNLOCKED = booleanPreferencesKey("parent_unlocked")

        val GHOST = booleanPreferencesKey("parent_ghost")

        val CAD_MOVING = intPreferencesKey("cadence_moving_seconds")
        val CAD_STILL = intPreferencesKey("cadence_still_seconds")
        val SAVER_BELOW = intPreferencesKey("saver_below_percent")
    }

    val mode: Flow<AppMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.UNSET
    }

    val config: Flow<TrackingConfig> = context.dataStore.data.map { prefs ->
        TrackingConfig(
            serverUrl = prefs[Keys.SERVER].orEmpty(),
            deviceId = prefs[Keys.DEVICE].orEmpty(),
            intervalSeconds = prefs[Keys.INTERVAL]?.toIntOrNull() ?: DEFAULT_INTERVAL_SECONDS,
            enabled = prefs[Keys.ENABLED] ?: false,
            apiUrl = prefs[Keys.VIEW_API].orEmpty(),
            viewerToken = prefs[Keys.VIEW_TOKEN].orEmpty(),
            movingSeconds = prefs[Keys.CAD_MOVING] ?: DEFAULT_MOVING_SECONDS,
            stillSeconds = prefs[Keys.CAD_STILL] ?: DEFAULT_STILL_SECONDS,
            saverBelow = prefs[Keys.SAVER_BELOW] ?: 0,
        )
    }

    /** Mode parent : id Traccar de l'appareil de ce téléphone (0 = pas encore créé). */
    val ownDeviceId: Flow<Long> = context.dataStore.data.map { prefs -> prefs[Keys.OWN_DEVICE_ID] ?: 0L }

    suspend fun setOwnDeviceId(id: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.OWN_DEVICE_ID] = id }
    }

    /** Jeton lecteur en cache s'il est encore valable au moins 30 jours. */
    suspend fun cachedChildViewerToken(): String? {
        val prefs = context.dataStore.data.first()
        val token = prefs[Keys.CHILD_VIEWER_TOKEN] ?: return null
        val expires = prefs[Keys.CHILD_VIEWER_EXPIRES] ?: 0L
        return token.takeIf { expires - System.currentTimeMillis() > 30L * 24 * 3600 * 1000 }
    }

    suspend fun cacheChildViewerToken(token: String, expires: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CHILD_VIEWER_TOKEN] = token
            prefs[Keys.CHILD_VIEWER_EXPIRES] = expires
        }
    }

    val parent: Flow<ParentConfig> = context.dataStore.data.map { prefs ->
        ParentConfig(
            apiUrl = prefs[Keys.API_URL].orEmpty(),
            trackUrl = prefs[Keys.TRACK_URL].orEmpty(),
            email = prefs[Keys.EMAIL].orEmpty(),
            token = prefs[Keys.TOKEN].orEmpty(),
        )
    }

    val pin: Flow<PinState> = context.dataStore.data.map { prefs ->
        PinState(
            hash = prefs[Keys.PIN_HASH].orEmpty(),
            salt = prefs[Keys.PIN_SALT].orEmpty(),
            failedAttempts = prefs[Keys.PIN_FAILS] ?: 0,
            lockedUntil = prefs[Keys.PIN_LOCKED_UNTIL] ?: 0L,
        )
    }

    /** Identifiant de la dernière alerte SOS marquée « vue » par le parent. */
    val acknowledgedAlarm: Flow<Long> = context.dataStore.data.map { prefs -> prefs[Keys.ACK_ALARM] ?: 0L }

    /** Alerte de proximité (parent) : prévenir quand un enfant redevient proche. */
    val proximityAlert: Flow<fr.kinwaypoint.app.ui.ProximityConfig> = context.dataStore.data.map { prefs ->
        fr.kinwaypoint.app.ui.ProximityConfig(
            enabled = prefs[Keys.PROXIMITY_ON] ?: false,
            radiusMeters = prefs[Keys.PROXIMITY_RADIUS] ?: 150,
        )
    }

    suspend fun setProximityAlert(config: fr.kinwaypoint.app.ui.ProximityConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROXIMITY_ON] = config.enabled
            prefs[Keys.PROXIMITY_RADIUS] = config.radiusMeters
        }
    }

    /**
     * Mise à niveau des valeurs par défaut, une seule fois : l'ancien intervalle par
     * défaut (60 s) devient 5 min. Une valeur choisie différente est conservée.
     */
    suspend fun migrateDefaults() {
        context.dataStore.edit { prefs ->
            if ((prefs[Keys.DEFAULTS_VERSION] ?: 0) < 1) {
                if (prefs[Keys.INTERVAL] == "60") prefs[Keys.INTERVAL] = DEFAULT_INTERVAL_SECONDS.toString()
                prefs[Keys.DEFAULTS_VERSION] = 1
            }
        }
    }

    /**
     * Délai d'absence avant de redemander le code / l'empreinte pour l'espace
     * parent, en minutes ; 0 = jamais (le verrouillage du téléphone suffit).
     */
    val relockMinutes: Flow<Int> = context.dataStore.data.map { prefs -> prefs[Keys.RELOCK_MINUTES] ?: 1 }

    suspend fun setRelockMinutes(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.RELOCK_MINUTES] = minutes }
    }

    /**
     * Mode fantôme voulu par le parent (null = jamais réglé ici). Mémorisé sur le
     * téléphone : quand le partage est coupé, le serveur masque toujours la
     * position, on ne peut donc plus y lire la préférence.
     */
    val ghostMode: Flow<Boolean?> = context.dataStore.data.map { prefs -> prefs[Keys.GHOST] }

    suspend fun setGhostMode(ghost: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.GHOST] = ghost }
    }

    /** Dernière utilisation de l'appli (heure murale), pour décider au redémarrage. */
    suspend fun markActive(now: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_ACTIVE] = now }
    }

    suspend fun setParentUnlocked(unlocked: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.PARENT_UNLOCKED] = unlocked }
    }

    /** L'espace parent était ouvert et le délai n'est pas écoulé : pas besoin du code. */
    suspend fun parentSessionValid(now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.PARENT_UNLOCKED] != true) return false
        val minutes = prefs[Keys.RELOCK_MINUTES] ?: 1
        if (minutes == 0) return true
        val last = prefs[Keys.LAST_ACTIVE] ?: return false
        return now - last in 0 until minutes * 60_000L
    }

    /** Mode enfant : un parent peut écouter le micro en direct (toujours visible sur ce téléphone). */
    val allowListen: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.ALLOW_LISTEN] ?: true }

    suspend fun setAllowListen(allowed: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ALLOW_LISTEN] = allowed }
    }

    /** Jeton FCM courant (null tant que Firebase n'a pas répondu). */
    val pushToken: Flow<String?> = context.dataStore.data.map { prefs -> prefs[Keys.PUSH_TOKEN] }

    suspend fun savePushToken(token: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PUSH_TOKEN] = token }
    }

    /** Heure de la dernière consultation des notifications (0 = jamais). */
    val eventsSeenAt: Flow<Long> = context.dataStore.data.map { prefs -> prefs[Keys.EVENTS_SEEN] ?: 0L }

    suspend fun markEventsSeen(time: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs -> prefs[Keys.EVENTS_SEEN] = time }
    }

    suspend fun acknowledgeAlarm(id: Long) {
        context.dataStore.edit { prefs ->
            if (id > (prefs[Keys.ACK_ALARM] ?: 0L)) prefs[Keys.ACK_ALARM] = id
        }
    }

    /** Déverrouillage de l'espace parent par empreinte (mode parent uniquement). */
    val biometricEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.BIOMETRIC] ?: false }
    val biometricOfferDismissed: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[Keys.BIOMETRIC_OFFER_DISMISSED] ?: false }

    suspend fun setBiometric(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BIOMETRIC] = enabled
            prefs[Keys.BIOMETRIC_OFFER_DISMISSED] = true
        }
    }

    suspend fun snapshot(): TrackingConfig = config.first()

    suspend fun save(config: TrackingConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER] = config.serverUrl.trim()
            prefs[Keys.DEVICE] = config.deviceId.trim()
            prefs[Keys.INTERVAL] = config.intervalSeconds.toString()
            prefs[Keys.ENABLED] = config.enabled
            prefs[Keys.VIEW_API] = config.apiUrl.trim()
            prefs[Keys.VIEW_TOKEN] = config.viewerToken
            prefs[Keys.CAD_MOVING] = config.movingSeconds
            prefs[Keys.CAD_STILL] = config.stillSeconds
            prefs[Keys.SAVER_BELOW] = config.saverBelow
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ENABLED] = enabled }
    }

    suspend fun setMode(mode: AppMode) {
        context.dataStore.edit { prefs -> prefs[Keys.MODE] = mode.name }
    }

    suspend fun saveParent(config: ParentConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.API_URL] = config.apiUrl.trim()
            prefs[Keys.TRACK_URL] = config.trackUrl.trim()
            prefs[Keys.EMAIL] = config.email.trim()
            prefs[Keys.TOKEN] = config.token
        }
    }

    suspend fun savePin(hash: String, salt: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PIN_HASH] = hash
            prefs[Keys.PIN_SALT] = salt
            prefs[Keys.PIN_FAILS] = 0
            prefs[Keys.PIN_LOCKED_UNTIL] = 0L
        }
    }

    suspend fun recordPinResult(failedAttempts: Int, lockedUntil: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PIN_FAILS] = failedAttempts
            prefs[Keys.PIN_LOCKED_UNTIL] = lockedUntil
        }
    }

    /** Retour à l'écran de choix du mode. Le PIN est conservé : il protège toujours l'appareil. */
    suspend fun resetMode() {
        context.dataStore.edit { prefs ->
            prefs[Keys.MODE] = AppMode.UNSET.name
            prefs[Keys.ENABLED] = false
            prefs.remove(Keys.TOKEN)
            prefs[Keys.BIOMETRIC] = false
            // La configuration de suivi appartient à l'ancien mode : on repart de zéro.
            prefs.remove(Keys.SERVER)
            prefs.remove(Keys.DEVICE)
            prefs.remove(Keys.VIEW_API)
            prefs.remove(Keys.VIEW_TOKEN)
            prefs.remove(Keys.OWN_DEVICE_ID)
        }
    }
}
