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
)

fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(millis))

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE).format(Date(millis))
