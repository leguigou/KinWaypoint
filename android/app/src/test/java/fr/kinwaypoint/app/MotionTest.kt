package fr.kinwaypoint.app

import fr.kinwaypoint.app.motion.Activity
import fr.kinwaypoint.app.motion.DailySteps
import fr.kinwaypoint.app.motion.MotionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    @Test
    fun dailyStepsAcrossDayAndReboot() {
        var s = DailySteps(null, 0, 0, 0).update(10_000, "2026-11-26")
        assertEquals(0, s.today)                       // première mesure du jour = base
        s = s.update(10_500, "2026-11-26")
        assertEquals(500, s.today)
        s = s.update(120, "2026-11-26")                // redémarrage : le total repart de 0
        assertEquals(620, s.today)
        s = s.update(300, "2026-11-26")
        assertEquals(800, s.today)
        s = s.update(400, "2026-11-27")                // nouveau jour
        assertEquals(0, s.today)
        s = s.update(450, "2026-11-27")
        assertEquals(50, s.today)
    }

    @Test
    fun speedAndActivity() {
        val m = MotionAnalyzer()
        // ~0,0005° de latitude ≈ 55 m toutes les 10 s → ~20 km/h
        for (i in 0..6) m.addFix(MotionAnalyzer.Fix(i * 10_000L, 48.85 + i * 0.0005, 2.35, 10f))
        val kmh = m.speedKmh(60_000L)
        assertNotNull(kmh)
        assertTrue("vitesse $kmh", kmh!! in 18.0..22.0)
        assertEquals(Activity.VEHICLE, m.activity(60_000L))
    }

    @Test
    fun classification() {
        assertEquals(Activity.VEHICLE, MotionAnalyzer.classify(40.0, 0.0))
        assertEquals(Activity.WALKING, MotionAnalyzer.classify(4.0, 100.0))
        assertEquals(Activity.WALKING, MotionAnalyzer.classify(null, 90.0))
        assertEquals(Activity.BIKE, MotionAnalyzer.classify(12.0, 0.0))
        assertEquals(Activity.STILL, MotionAnalyzer.classify(0.5, 0.0))
        assertEquals(Activity.STILL, MotionAnalyzer.classify(null, 0.0))
    }
}
