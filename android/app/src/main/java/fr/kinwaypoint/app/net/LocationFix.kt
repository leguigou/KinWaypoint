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
    /**
     * Âge réel de la mesure GPS, en secondes, quand il diffère de `time`.
     * Cas du SOS : l'alerte part tout de suite avec la dernière position connue,
     * horodatée « maintenant » pour que le serveur la traite comme la plus récente.
     */
    val fixAgeSeconds: Long? = null,
    /** Sonnerie du téléphone : "normal", "vibrate" ou "silent". */
    val ringer: String? = null,
    /** Nom du Wi-Fi connecté, si connu. */
    val wifi: String? = null,
    /** Pas du jour (podomètre), si disponibles. */
    val steps: Int? = null,
    /** Activité estimée : still, walking, bike, vehicle. */
    val activity: String? = null,
    /** Vitesse lissée sur les 2 dernières minutes, en km/h. */
    val kmh: Double? = null,
    /** Wi-Fi du téléphone activé (connecté ou non), ou null si inconnu. */
    val wifiOn: Boolean? = null,
)
