package fr.kinwaypoint.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackingStatusState(
    val running: Boolean = false,
    val lastSentAt: Long? = null,
    val lastOk: Boolean? = null,
    val battery: Int? = null,
    val accuracy: Float? = null,
    val sosPending: Boolean = false,
)

/** État partagé entre le service de suivi et l'interface (volontairement simple). */
object TrackingStatus {
    private val _state = MutableStateFlow(TrackingStatusState())
    val state: StateFlow<TrackingStatusState> = _state.asStateFlow()

    fun update(transform: (TrackingStatusState) -> TrackingStatusState) {
        _state.value = transform(_state.value)
    }
}
