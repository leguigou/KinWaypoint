package fr.kinwaypoint.app

import fr.kinwaypoint.app.net.TracePoint
import fr.kinwaypoint.app.ui.batteryStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryStatsTest {
    private val h = 3_600_000L

    private fun p(hour: Double, battery: Int?, charging: Boolean? = false) =
        TracePoint(0.0, 0.0, (hour * h).toLong(), 0.0, 10.0, battery, charging = charging)

    @Test
    fun aucuneMesure() {
        assertNull(batteryStats(listOf(p(1.0, null))))
    }

    @Test
    fun dechargeEtRecharge() {
        val stats = batteryStats(
            listOf(
                p(8.0, 90), p(9.0, 85), p(10.0, 80), // −10 % en 2 h
                p(10.5, 79, charging = true), p(11.5, 95, charging = true), // 1 h en charge
                p(12.0, 96), p(13.0, 92), // −4 % en 1 h
            ),
        )!!
        assertEquals(79, stats.min)
        assertEquals(96, stats.max)
        assertEquals(92, stats.last)
        assertEquals(1, stats.chargeSessions)
        assertEquals(60L, stats.chargingMinutes)
        // (10 + 1 + 4) % sur 3,5 h hors charge (8 h→10 h 30 et 12 h→13 h).
        assertNotNull(stats.dischargePerHour)
        assertEquals(15.0 / 3.5, stats.dischargePerHour!!, 0.01)
    }

    @Test
    fun trouDePlusDeDeuxHeuresIgnore() {
        val stats = batteryStats(listOf(p(8.0, 90), p(14.0, 40), p(15.0, 38)))!!
        // Seul 14 h → 15 h compte : 2 % en 1 h.
        assertEquals(2.0, stats.dischargePerHour!!, 0.01)
    }
}
