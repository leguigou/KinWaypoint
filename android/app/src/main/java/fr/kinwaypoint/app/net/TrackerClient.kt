package fr.kinwaypoint.app.net

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Envoi des positions sur l'endpoint d'ingestion Traccar (protocole OsmAnd).
 * POST de formulaire : Traccar accepte aussi bien les paramètres en query que
 * dans le corps, et le POST évite de mettre la charge utile dans les logs proxy.
 */
class TrackerClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun send(serverUrl: String, deviceId: String, fix: LocationFix): Result<Unit> = runCatching {
        val body = FormBody.Builder()
            .add("id", deviceId)
            .add("timestamp", (fix.time / 1000L).toString())
            .add("lat", String.format(Locale.US, "%.6f", fix.latitude))
            .add("lon", String.format(Locale.US, "%.6f", fix.longitude))
            .add("accuracy", String.format(Locale.US, "%.1f", fix.accuracy))
            .add("altitude", String.format(Locale.US, "%.1f", fix.altitude))
            // OsmAnd / Traccar attendent la vitesse en nœuds (Android la donne en m/s).
            .add("speed", String.format(Locale.US, "%.2f", fix.speed * 1.943844))
            .add("bearing", String.format(Locale.US, "%.1f", fix.bearing))
            .add("batt", fix.battery.toString())
            .add("charge", fix.charging.toString())
            .apply {
                fix.alarm?.let { add("alarm", it) }
                fix.fixAgeSeconds?.let { add("fixAge", it.toString()) }
                fix.ringer?.let { add("ringer", it) }
                fix.wifi?.let { add("wifi", it) }
                fix.steps?.let { add("steps", it.toString()) }
                fix.activity?.let { add("activity", it) }
                fix.kmh?.let { add("kmh", String.format(Locale.US, "%.1f", it)) }
            }
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
        }
    }
}
