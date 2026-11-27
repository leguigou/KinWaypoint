package fr.kinwaypoint.app

import fr.kinwaypoint.app.ui.normalizePhone
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneTest {
    @Test
    fun numerosFrancais() {
        assertEquals("+33612345678", normalizePhone("06 12 34 56 78"))
        assertEquals("+33612345678", normalizePhone("+33 6 12 34 56 78"))
        assertEquals("+33612345678", normalizePhone("0033612345678"))
    }

    @Test
    fun videEtAutres() {
        assertEquals("", normalizePhone("   "))
        assertEquals("+3225551234", normalizePhone("+32 2 555 12 34"))
    }
}
