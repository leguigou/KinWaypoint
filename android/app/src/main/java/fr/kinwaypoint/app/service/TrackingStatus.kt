package fr.kinwaypoint.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackingStatusState(
    val running: Boolean = false,
    /** Dernière position acceptée par le serveur. */
    val lastSentAt: Long? = null,
    /** Résultat du dernier essai d'envoi (null = pas encore d'essai). */
    val lastOk: Boolean? = null,
    val lastError: String? = null,
    /** Positions en attente de réseau, conservées sur le téléphone. */
    val queued: Int = 0,
    val battery: Int? = null,
    val accuracy: Float? = null,
    val sosPending: Boolean = false,
    /** Heure à laquelle le serveur a accepté la dernière alerte SOS. */
    val sosDeliveredAt: Long? = null,
)

/** État partagé entre le service de suivi et l'interface (volontairement simple). */
object TrackingStatus {
    private val _state = MutableStateFlow(TrackingStatusState())
    val state: StateFlow<TrackingStatusState> = _state.asStateFlow()

    @Synchronized
    fun update(transform: (TrackingStatusState) -> TrackingStatusState) {
        _state.value = transform(_state.value)
    }
}
