package fr.kinwaypoint.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import fr.kinwaypoint.app.MainActivity
import fr.kinwaypoint.app.R
import fr.kinwaypoint.app.net.Member

/** Réglage de l'alerte de proximité (calculée sur le téléphone du parent). */
data class ProximityConfig(val enabled: Boolean = false, val radiusMeters: Int = 150)

/** Un enfant redevenu proche après s'être éloigné : pour la bannière dans l'appli. */
data class ProximityArrival(val memberId: Long, val name: String, val meters: Int, val time: Long)

// Au-delà de cette ancienneté, une position est jugée trop vieille pour décider.
private const val STALE_MS = 5 * 60_000L

/**
 * Surveille la distance entre le parent et chaque enfant, et signale (notification
 * + bannière) quand un enfant **redevient proche après s'être éloigné**.
 *
 * Calcul fait ici, sur le téléphone du parent : il faut donc que l'appli tourne
 * (ouverte ou en tâche de fond récente). Ce n'est pas un suivi permanent en
 * arrière-plan.
 *
 * Anti-rebond : il faut d'abord s'être nettement éloigné (au-delà du rayon + une
 * marge) pour que le retour dans le rayon déclenche une nouvelle alerte. Un
 * enfant qui reste au bord du rayon ne déclenche donc pas d'alertes en rafale.
 *
 * @return les arrivées récentes non fermées, à afficher dans l'appli, et une
 *   fonction pour fermer l'une d'elles.
 */
@Composable
fun rememberProximityAlerts(
    config: ProximityConfig,
    parent: Location?,
    members: List<Member>,
    ownDeviceId: Long,
): Pair<List<ProximityArrival>, (Long) -> Unit> {
    val context = LocalContext.current
    // « armé » = l'enfant s'est assez éloigné ; la prochaine arrivée déclenchera l'alerte.
    val armed = remember { mutableStateMapOfLong() }
    var arrivals by remember { mutableStateOf<List<ProximityArrival>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(config, parent, members, ownDeviceId) {
        if (!config.enabled) { arrivals = emptyList(); return@LaunchedEffect }
        val now = System.currentTimeMillis()
        if (parent == null || now - parent.time > STALE_MS) return@LaunchedEffect

        val radius = config.radiusMeters.toFloat()
        // Marge pour l'anti-rebond : s'éloigner d'au moins ça au-delà du rayon.
        val rearmAt = radius + radius.coerceAtMost(150f)

        members.filter { it.id != ownDeviceId && !it.isParent }.forEach { m ->
            val pos = m.position ?: return@forEach
            if (now - pos.fixTime > STALE_MS) return@forEach
            val result = FloatArray(1)
            Location.distanceBetween(parent.latitude, parent.longitude, pos.latitude, pos.longitude, result)
            val d = result[0]
            when {
                d >= rearmAt -> armed[m.id] = true
                d <= radius && armed[m.id] == true -> {
                    armed[m.id] = false
                    val arrival = ProximityArrival(m.id, m.name, d.toInt(), now)
                    arrivals = arrivals.filter { it.memberId != m.id } + arrival
                    notifyProximity(context, m.id, m.name, d.toInt())
                }
            }
        }
    }

    return arrivals to { id: Long -> arrivals = arrivals.filter { it.memberId != id } }
}

private fun mutableStateMapOfLong() = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()

/** Notification « X est de nouveau proche ». */
private fun notifyProximity(context: Context, memberId: Long, name: String, meters: Int) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel("proximity", "Proximité", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Un enfant est de nouveau proche de toi"
            },
        )
    }
    // Android 13+ : sans l'autorisation de notifications, on n'affiche rien (la bannière reste).
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val open = PendingIntent.getActivity(
        context, 4, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val distance = if (meters < 1000) "$meters m" else String.format(java.util.Locale.FRANCE, "%.1f km", meters / 1000f)
    manager.notify(
        2000 + memberId.toInt(),
        NotificationCompat.Builder(context, "proximity")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name est proche de toi")
            .setContentText("À environ $distance")
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build(),
    )
}
