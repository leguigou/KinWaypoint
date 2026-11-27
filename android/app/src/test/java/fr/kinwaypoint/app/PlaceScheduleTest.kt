package fr.kinwaypoint.app

import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.PlaceSchedule
import fr.kinwaypoint.app.net.targets
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceScheduleTest {

    /** Même texte que le calendrier validé avec ical4j 4.4.0 (logique de Traccar 6.17). */
    @Test
    fun schoolWeekCalendar() {
        val ics = PlaceSchedule(setOf(5, 1, 4, 2), 8 * 60, 17 * 60).toICalendar(12, "Ecole", "Europe/Paris")
        val expected = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//KinWaypoint//Horaires//FR\r\nBEGIN:VEVENT\r\n" +
            "UID:kw-place-12@kinwaypoint\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART;TZID=Europe/Paris:20260105T080000\r\nDTEND;TZID=Europe/Paris:20260105T170000\r\n" +
            "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,TH,FR\r\nSUMMARY:Ecole\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals(expected, ics)
    }

    @Test
    fun overnightEndsNextDay() {
        val ics = PlaceSchedule(setOf(6, 7), 20 * 60 + 30, 7 * 60).toICalendar(3, "Maison; nuit", "Europe/Paris")
        assert(ics.contains("DTSTART;TZID=Europe/Paris:20260105T203000\r\n"))
        assert(ics.contains("DTEND;TZID=Europe/Paris:20260106T070000\r\n"))
        assert(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=SA,SU\r\n"))
        assert(ics.contains("SUMMARY:Maison  nuit\r\n"))
    }

    @Test
    fun placeTargets() {
        val all = listOf(1L, 2L, 3L)
        assertEquals(all, Place(1, "x", 0.0, 0.0, 100.0, "home").targets(all))
        assertEquals(listOf(2L), Place(1, "x", 0.0, 0.0, 100.0, "home", childIds = listOf(2L, 9L)).targets(all))
    }
}
