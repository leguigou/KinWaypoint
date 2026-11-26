package fr.kinwaypoint.app.motion

import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.TracePoint
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Temps d'arrivée estimé vers un lieu de la famille (« Maison dans ~15 min »).
 *
 * Principe : on compare la position actuelle à celle d'il y a ~5 min. Si la
 * personne s'est nettement rapprochée d'un lieu (et pas seulement de passage
 * à côté), on estime le temps restant : distance × 1,25 (les rues ne sont pas
 * en ligne droite) ÷ vitesse lissée.
 */
object Eta {
    data class Arrival(val place: Place, val minutes: Int, val meters: Double)

    private const val LOOKBACK_MS = 5 * 60_000L
    private const val DETOUR = 1.25

    fun estimate(trace: List<TracePoint>, latitude: Double, longitude: Double, kmh: Double?, places: List<Place>, now: Long): Arrival? {
        val speed = kmh?.takeIf { it >= 3 } ?: return null
        val past = trace.lastOrNull { now - it.time >= LOOKBACK_MS - 60_000 } ?: return null
        if (now - past.time > 20 * 60_000L) return null // trop ancien pour juger de la direction
        val travelled = distance(past.latitude, past.longitude, latitude, longitude)
        if (travelled < 150) return null

        return places.mapNotNull { p ->
            val dNow = distance(latitude, longitude, p.latitude, p.longitude) - p.radius
            val dPast = distance(past.latitude, past.longitude, p.latitude, p.longitude) - p.radius
            val approach = dPast - dNow
            // Déjà sur place, trop loin, ou on ne s'en rapproche pas franchement.
            if (dNow <= 0 || dNow > 30_000 || approach < 150 || approach < 0.5 * travelled) return@mapNotNull null
            Triple(p, dNow, approach / travelled)
        }.maxByOrNull { it.third }?.let { (place, meters, _) ->
            val minutes = ceil(meters * DETOUR / (speed / 3.6) / 60).toInt().coerceAtLeast(1)
            Arrival(place, minutes, meters)
        }
    }

    internal fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }
}
