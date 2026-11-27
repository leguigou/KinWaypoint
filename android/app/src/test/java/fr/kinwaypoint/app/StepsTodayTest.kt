package fr.kinwaypoint.app

import fr.kinwaypoint.app.net.MemberPosition
import fr.kinwaypoint.app.ui.stepsToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StepsTodayTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun pos(time: Long, steps: Int?) = MemberPosition(0.0, 0.0, time, 10.0, 50, false, steps = steps)

    @Test
    fun memeJourGardeLesPas() {
        val today = LocalDate.of(2026, 11, 28)
        assertEquals(1892, pos(at(today, 9), 1892).stepsToday(at(today, 10)))
    }

    @Test
    fun positionDHierRepartAZeroApresMinuit() {
        val today = LocalDate.of(2026, 11, 28)
        assertEquals(0, pos(at(today.minusDays(1), 21), 1892).stepsToday(at(today, 0) + 11 * 60_000))
    }

    @Test
    fun podometreInconnu() {
        val today = LocalDate.of(2026, 11, 28)
        assertNull(pos(at(today, 9), null).stepsToday(at(today, 10)))
    }
}
