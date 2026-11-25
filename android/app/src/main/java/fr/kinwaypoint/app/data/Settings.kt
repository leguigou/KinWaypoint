package fr.kinwaypoint.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Serveur d'ingestion par défaut (protocole OsmAnd, HTTPS). */
const val DEFAULT_SERVER = "https://track.deloffre.fr"

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kinwaypoint")

data class TrackingConfig(
    val serverUrl: String = DEFAULT_SERVER,
    val deviceId: String = "",
    val intervalSeconds: Int = 60,
    val enabled: Boolean = false,
) {
    val isValid: Boolean get() = deviceId.isNotBlank() && serverUrl.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER = stringPreferencesKey("server_url")
        val DEVICE = stringPreferencesKey("device_id")
        val INTERVAL = stringPreferencesKey("interval_seconds")
        val ENABLED = booleanPreferencesKey("enabled")
    }

    val config: Flow<TrackingConfig> = context.dataStore.data.map { prefs ->
        TrackingConfig(
            serverUrl = prefs[Keys.SERVER] ?: DEFAULT_SERVER,
            deviceId = prefs[Keys.DEVICE].orEmpty(),
            intervalSeconds = prefs[Keys.INTERVAL]?.toIntOrNull() ?: 60,
            enabled = prefs[Keys.ENABLED] ?: false,
        )
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
}
