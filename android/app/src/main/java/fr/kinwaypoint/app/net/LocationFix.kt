package fr.kinwaypoint.app.net

/** Une position prête à être envoyée au serveur, au format OsmAnd. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val time: Long,
    val battery: Int,
    val charging: Boolean,
    val alarm: String? = null,
)
