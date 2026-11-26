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

/** Rôle de l'appareil, choisi au premier lancement. */
enum class AppMode { UNSET, CHILD, PARENT }

/** Réglages du mode enfant : où et à quelle cadence envoyer la position. */
data class TrackingConfig(
    val serverUrl: String = "",
    val deviceId: String = "",
    val intervalSeconds: Int = 60,
    val enabled: Boolean = false,
) {
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

        val BIOMETRIC = booleanPreferencesKey("parent_biometric")
        val BIOMETRIC_OFFER_DISMISSED = booleanPreferencesKey("parent_biometric_offer_dismissed")
    }

    val mode: Flow<AppMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.UNSET
    }

    val config: Flow<TrackingConfig> = context.dataStore.data.map { prefs ->
        TrackingConfig(
            serverUrl = prefs[Keys.SERVER].orEmpty(),
            deviceId = prefs[Keys.DEVICE].orEmpty(),
            intervalSeconds = prefs[Keys.INTERVAL]?.toIntOrNull() ?: 60,
            enabled = prefs[Keys.ENABLED] ?: false,
        )
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
        }
    }
}
