package fr.kinwaypoint.app

import fr.kinwaypoint.app.security.Pin
import fr.kinwaypoint.app.service.TrackingService
import fr.kinwaypoint.app.ui.generateDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun pinFormat() {
        assertTrue(Pin.isWellFormed("1234"))
        assertTrue(Pin.isWellFormed("12345678"))
        assertFalse(Pin.isWellFormed("123"))
        assertFalse(Pin.isWellFormed("123456789"))
        assertFalse(Pin.isWellFormed("12a4"))
        assertFalse(Pin.isWellFormed(""))
    }

    @Test
    fun deviceIdIsLongRandomAndUnambiguous() {
        val ids = (1..500).map { generateDeviceId() }
        assertEquals("identifiants uniques", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertEquals(28, id.length)
            assertTrue(id.startsWith("kw"))
            assertTrue("caractères ambigus dans $id", id.drop(2).none { it in "01lo" })
        }
    }

    @Test
    fun motionThresholds() {
        // GPS précis : immobile tant qu'on reste dans ~80 m, mouvement au-delà de ~205 m.
        assertEquals(80f, TrackingService.stillRadius(5f), 0.01f)
        assertEquals(205f, TrackingService.moveRadius(5f), 0.01f)
        // Antenne relais très imprécise : seuils plafonnés pour ne pas rester bloqué.
        assertEquals(225f, TrackingService.stillRadius(2000f), 0.01f)
        assertEquals(500f, TrackingService.moveRadius(2000f), 0.01f)
        // Le seuil de sortie est toujours plus large que celui d'entrée (pas d'oscillation).
        listOf(0f, 20f, 100f, 500f).forEach { acc ->
            assertTrue(TrackingService.moveRadius(acc) > TrackingService.stillRadius(acc))
        }
    }
}
