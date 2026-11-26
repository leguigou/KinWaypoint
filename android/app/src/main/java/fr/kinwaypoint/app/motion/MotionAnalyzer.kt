package fr.kinwaypoint.app.motion

/**
 * Estime le déplacement à partir des dernières positions et des pas :
 * vitesse lissée (distance / temps sur ~2 min, plus fiable que la vitesse
 * instantanée du GPS), cadence de pas, et activité probable.
 */
class MotionAnalyzer(private val windowMs: Long = 120_000L) {

    data class Fix(val time: Long, val latitude: Double, val longitude: Double, val accuracy: Float)
    private data class StepSample(val time: Long, val total: Long)

    private val fixes = ArrayDeque<Fix>()
    private val steps = ArrayDeque<StepSample>()

    @Synchronized
    fun addFix(fix: Fix) {
        // Mesures trop imprécises (antenne relais) : elles fausseraient la vitesse.
        if (fix.accuracy > 100f) return
        fixes.addLast(fix)
        while (fixes.size > 2 && fix.time - fixes.first().time > windowMs) fixes.removeFirst()
    }

    @Synchronized
    fun addSteps(time: Long, total: Long) {
        steps.addLast(StepSample(time, total))
        while (steps.size > 2 && time - steps.first().time > windowMs) steps.removeFirst()
    }

    /** Vitesse moyenne récente en km/h (null si pas assez de mesures). */
    @Synchronized
    fun speedKmh(now: Long): Double? {
        val recent = fixes.filter { now - it.time <= windowMs }
        if (recent.size < 2) return null
        val seconds = (recent.last().time - recent.first().time) / 1000.0
        if (seconds < 20) return null
        val meters = recent.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        return meters / seconds * 3.6
    }

    /** Pas par minute sur la fenêtre récente. */
    @Synchronized
    fun cadence(now: Long): Double {
        val recent = steps.filter { now - it.time <= windowMs }
        if (recent.size < 2) return 0.0
        val minutes = (recent.last().time - recent.first().time) / 60_000.0
        if (minutes < 0.25) return 0.0
        return (recent.last().total - recent.first().total) / minutes
    }

    fun activity(now: Long): Activity = classify(speedKmh(now), cadence(now))

    companion object {
        /** Seuil « voiture » demandé : au-delà de 15 km/h. */
        const val VEHICLE_KMH = 15.0

        fun classify(speedKmh: Double?, cadence: Double): Activity = when {
            speedKmh != null && speedKmh > VEHICLE_KMH -> Activity.VEHICLE
            cadence >= 40 -> Activity.WALKING
            speedKmh != null && speedKmh >= 7 -> Activity.BIKE
            speedKmh != null && speedKmh >= 2 -> Activity.WALKING
            else -> Activity.STILL
        }

        private fun distance(a: Fix, b: Fix): Double {
            // Haversine : pas de dépendance à Android, donc testable sur la JVM.
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * r * Math.asin(Math.sqrt(h))
        }
    }
}

/** Activité estimée, envoyée au serveur (attribut « activity »). */
enum class Activity(val code: String) {
    STILL("still"),
    WALKING("walking"),
    BIKE("bike"),
    VEHICLE("vehicle"),
}
