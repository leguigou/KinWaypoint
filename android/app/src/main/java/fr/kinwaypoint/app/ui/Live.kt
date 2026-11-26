package fr.kinwaypoint.app.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fr.kinwaypoint.app.net.MemberPosition
import fr.kinwaypoint.app.net.TraccarApi
import fr.kinwaypoint.app.net.TracePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Mise à jour en direct : connexion permanente au serveur tant que l'écran est
 * visible, reconnexion automatique (5 s, 10 s… jusqu'à 2 min). Renvoie true
 * quand la connexion est établie ; l'appelant espace alors ses actualisations.
 */
@Composable
fun rememberLiveUpdates(api: TraccarApi, token: String, onMessage: (TraccarApi.LiveMessage) -> Unit): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handler = remember { Handler(Looper.getMainLooper()) }
    val currentOnMessage by rememberUpdatedState(onMessage)
    var connected by remember { mutableStateOf(false) }

    LaunchedEffect(api, token) {
        if (token.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoff = 5_000L
            while (true) {
                val closed = CompletableDeferred<Unit>()
                val listener = object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = api.parseLiveMessage(text)
                        handler.post { currentOnMessage(message) }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                }
                val socket = withContext(Dispatchers.IO) { runCatching { api.openLiveSocket(token, listener) }.getOrNull() }
                if (socket != null) {
                    connected = true
                    backoff = 5_000L
                    try {
                        closed.await()
                    } finally {
                        socket.close(1000, null)
                        connected = false
                    }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }
    return connected
}

/** Position reçue en direct → point de tracé. */
fun MemberPosition.toTracePoint() = TracePoint(
    latitude = latitude,
    longitude = longitude,
    time = fixTime,
    speedKmh = kmh ?: 0.0,
    accuracy = accuracy,
    battery = battery,
    steps = steps,
    activity = activity,
)
