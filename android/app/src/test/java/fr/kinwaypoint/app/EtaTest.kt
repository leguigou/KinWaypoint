package fr.kinwaypoint.app

import fr.kinwaypoint.app.motion.Eta
import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.TracePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaTest {
    private val home = Place(1, "Maison", 48.8600, 2.3500, 100.0, "home")
    private val school = Place(2, "École", 48.8000, 2.3500, 150.0, "school")

    /** Trajet plein nord, de 48,840 à 48,845 en 5 min (~550 m, ~6,7 km/h). */
    private fun walkingNorth(): List<TracePoint> = (0..5).map { i ->
        TracePoint(48.840 + i * 0.001, 2.35, i * 60_000L, 6.7, 10.0, null)
    }

    @Test
    fun headingHome() {
        val trace = walkingNorth()
        val last = trace.last()
        val eta = Eta.estimate(trace, last.latitude, last.longitude, 6.7, listOf(home, school), last.time)
        assertNotNull(eta)
        assertEquals("Maison", eta!!.place.name)
        // ~1 570 m restants × 1,25 à 6,7 km/h ≈ 18 min
        assertTrue("minutes ${eta.minutes}", eta.minutes in 15..21)
    }

    @Test
    fun noEtaWhenStillOrMovingAway() {
        val trace = walkingNorth()
        val last = trace.last()
        assertNull(Eta.estimate(trace, last.latitude, last.longitude, 1.0, listOf(home), last.time)) // à l'arrêt
        assertNull(Eta.estimate(trace, last.latitude, last.longitude, 6.7, listOf(school), last.time)) // s'éloigne
        assertNull(Eta.estimate(trace.take(2), 48.841, 2.35, 6.7, listOf(home), 60_000L)) // historique trop court
    }
}
