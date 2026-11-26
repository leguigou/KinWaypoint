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
)

fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(millis))

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE).format(Date(millis))
