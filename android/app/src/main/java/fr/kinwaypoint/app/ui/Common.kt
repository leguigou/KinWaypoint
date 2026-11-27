package fr.kinwaypoint.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** État des autorisations Android utiles au suivi. */
data class PermissionSnapshot(
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val notifications: Boolean = false,
    val batteryOptimized: Boolean = true,
    /** Activité physique (podomètre), Android 10+. */
    val activityRecognition: Boolean = true,
    /** Micro (écoute en direct par un parent). */
    val microphone: Boolean = false,
    /** « Afficher par-dessus les autres applis » : ouvre l'écran d'écoute sans toucher le téléphone. */
    val overlay: Boolean = false,
    /** Accès « Ne pas déranger » : « Faire sonner » passe outre le mode Ne pas déranger. */
    val dndAccess: Boolean = true,
)

/** Actions qui demandent l'activité Android (autorisations, réglages, carte). */
class PlatformActions(
    val requestLocation: () -> Unit,
    val openAppSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val openMap: (latitude: Double, longitude: Double, label: String) -> Unit,
    /** Ouvre la caméra ; le texte lu (ou null si annulé) est rendu au callback. */
    val scanQr: (onResult: (String?) -> Unit) -> Unit,
    /** Une empreinte (biométrie forte) est enregistrée et utilisable sur ce téléphone. */
    val biometricAvailable: () -> Boolean,
    /** Affiche la demande d'empreinte ; onSuccess seulement si elle est reconnue. */
    val authenticate: (onSuccess: () -> Unit) -> Unit,
    /** Ouvre la galerie de photos ; l'image choisie (ou null) est rendue au callback. */
    val pickImage: (onResult: (android.net.Uri?) -> Unit) -> Unit,
    /** Demande l'accès au micro (écoute en direct par un parent). */
    val requestMicrophone: () -> Unit = {},
    /** Ouvre le réglage « Afficher par-dessus les autres applis ». */
    val openOverlaySettings: () -> Unit = {},
    /** Ouvre le réglage « Accès Ne pas déranger ». */
    val openDndSettings: () -> Unit = {},
    /** Demande la localisation « Toujours autoriser » (Android ouvre sa page dédiée). */
    val requestBackgroundLocation: () -> Unit = openAppSettings,
)

fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(millis))

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE).format(Date(millis))

/** « à l'instant », « il y a 5 min », « il y a 3 h », « il y a 2 j ». */
fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000L
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 48 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}

/** « 12 345 » (espace fine des milliers, à la française). */
fun formatSteps(steps: Int): String = String.format(Locale.FRANCE, "%,d", steps).replace(',', '\u202F')

/** Emoji de l'activité : 🚗 au-delà de 15 km/h, 🚲, 🚶. Null à l'arrêt ou inconnu. */
fun activityEmoji(activity: String?): String? = when (activity) {
    "vehicle" -> "🚗"
    "bike" -> "🚲"
    "walking" -> "🚶"
    else -> null
}

/** « 🚗 42 km/h », « 🚶 En marche »… (null à l'arrêt). */
fun activityLabel(activity: String?, kmh: Double?): String? {
    val speed = kmh?.takeIf { it >= 1 }?.let { " · ${it.toInt()} km/h" }.orEmpty()
    return when (activity) {
        "vehicle" -> "🚗 En véhicule$speed"
        "bike" -> "🚲 Vélo / trottinette$speed"
        "walking" -> "🚶 En marche$speed"
        else -> null
    }
}

/**
 * Serveur familial (dépôt d'APK, configuration) : il indique aussi les adresses
 * de Traccar. Fourni à la compilation (KW_FAMILY_HUB), jamais écrit dans le code ;
 * vide si la compilation ne l'a pas reçu.
 */
val FAMILY_HUB: String = fr.kinwaypoint.app.BuildConfig.FAMILY_HUB

/** Adresses du serveur Traccar annoncées par le serveur familial (/apk/server.json). */
data class ServerInfo(val apiUrl: String, val trackUrl: String)

/** Découverte : null si le serveur familial ne répond pas ou n'est pas configuré. */
suspend fun discoverServer(hub: String = FAMILY_HUB): ServerInfo? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (!hub.startsWith("https://")) return@withContext null
    runCatching {
        val http = okhttp3.OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        http.newCall(okhttp3.Request.Builder().url("${hub.trimEnd('/')}/apk/server.json").build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = org.json.JSONObject(response.body?.string().orEmpty())
            val api = json.optString("apiUrl").takeIf { it.startsWith("https://") } ?: return@use null
            ServerInfo(api, json.optString("trackUrl").takeIf { it.startsWith("https://") } ?: guessTrackUrl(api))
        }
    }.getOrNull()
}
