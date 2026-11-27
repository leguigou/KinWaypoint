package fr.kinwaypoint.app.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.kinwaypoint.app.net.Member

/**
 * Réglage de l'alerte de proximité. La notification est envoyée par le serveur
 * (Traccar, événement proximityEnter), même appli fermée ; ce réglage local
 * mémorise seulement l'état et la distance affichés dans l'appli.
 */
data class ProximityConfig(val enabled: Boolean = false, val radiusMeters: Int = 150)

/** Un enfant redevenu proche après s'être éloigné : pour la bannière dans l'appli. */
data class ProximityArrival(val memberId: Long, val name: String, val meters: Int, val time: Long)

// Au-delà de cette ancienneté, une position est jugée trop vieille pour décider.
private const val STALE_MS = 5 * 60_000L

/**
 * Bannière dans l'appli quand un enfant **revient près de toi après s'être
 * éloigné**, pendant que l'appli est ouverte. La notification, elle, vient du
 * serveur (même appli fermée) : rien n'est notifié d'ici, pour éviter les doublons.
 *
 * Anti-rebond : il faut d'abord s'être nettement éloigné (au-delà du rayon + une
 * marge) pour qu'un retour dans le rayon affiche une nouvelle bannière.
 *
 * @return les arrivées récentes non fermées, et une fonction pour fermer l'une d'elles.
 */
@Composable
fun rememberProximityAlerts(
    config: ProximityConfig,
    parent: Location?,
    members: List<Member>,
    ownDeviceId: Long,
): Pair<List<ProximityArrival>, (Long) -> Unit> {
    // « armé » = l'enfant s'est assez éloigné ; la prochaine arrivée affichera la bannière.
    val armed = remember { mutableStateMapOf<Long, Boolean>() }
    var arrivals by remember { mutableStateOf<List<ProximityArrival>>(emptyList()) }

    LaunchedEffect(config, parent, members, ownDeviceId) {
        if (!config.enabled) { arrivals = emptyList(); return@LaunchedEffect }
        val now = System.currentTimeMillis()
        if (parent == null || now - parent.time > STALE_MS) return@LaunchedEffect

        val radius = config.radiusMeters.toFloat()
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
                    arrivals = arrivals.filter { it.memberId != m.id } + ProximityArrival(m.id, m.name, d.toInt(), now)
                }
            }
        }
    }

    return arrivals to { id: Long -> arrivals = arrivals.filter { it.memberId != id } }
}
