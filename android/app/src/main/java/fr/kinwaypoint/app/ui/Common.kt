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
