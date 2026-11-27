package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.AudioRelay
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private sealed interface ListenPhase {
    data object Connecting : ListenPhase
    data object Waiting : ListenPhase
    data object Live : ListenPhase
    data class Ended(val message: String) : ListenPhase
}

/**
 * Écoute en direct, côté parent : le micro du téléphone de l'enfant est diffusé
 * ici. L'enfant voit un écran « Un parent t'écoute » et une notification. Rien
 * n'est enregistré. Quitter cet écran coupe immédiatement le micro de l'enfant.
 */
@Composable
fun ListenScreen(api: TraccarApi, token: String, member: Member, photo: Bitmap?, onBack: () -> Unit) {
    var phase by remember { mutableStateOf<ListenPhase>(ListenPhase.Connecting) }
    var seconds by remember { mutableIntStateOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }
    fun set(p: ListenPhase) = main.post { if (phase !is ListenPhase.Ended) phase = p }

    BackHandler(onBack = onBack)

    // Salle à usage unique : le parent l'ouvre AVANT que l'enfant soit appelé.
    val room = remember { AudioRelay.randomId() }
    val secret = remember { AudioRelay.randomId() }
    var socket by remember { mutableStateOf<WebSocket?>(null) }

    val track = remember {
        val min = AudioTrack.getMinBufferSize(AudioRelay.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioRelay.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, AudioRelay.FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }

    LaunchedEffect(Unit) {
        val relay = AudioRelay.relayUrl()
        if (relay == null) {
            set(ListenPhase.Ended("Le serveur d'écoute n'est pas configuré (voir docs/ECOUTE-DIRECTE.md)."))
            return@LaunchedEffect
        }
        runCatching { track?.play() }
        val request = Request.Builder().url(AudioRelay.url(relay, room, "sub", secret)).build()
        socket = AudioRelay.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                set(ListenPhase.Waiting)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pub-joined") set(ListenPhase.Live)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (phase != ListenPhase.Live) set(ListenPhase.Live)
                val data = bytes.toByteArray()
                track?.write(data, 0, data.size)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                set(
                    ListenPhase.Ended(
                        when (code) {
                            4408 -> "Le téléphone de ${member.name} n'a pas répondu. Vérifie qu'il est allumé, connecté, et que l'écoute et le micro sont autorisés dans ses réglages."
                            4001 -> "Durée maximale atteinte (10 minutes)."
                            else -> "Écoute terminée."
                        },
                    ),
                )
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                set(ListenPhase.Ended("Connexion au serveur d'écoute impossible."))
            }
        })
        // Salle prête : on appelle le téléphone de l'enfant (commande + réveil Firebase).
        val sent = withContext(Dispatchers.IO) { runCatching { api.startListen(token, member.id, room, secret) } }
        if (sent.isFailure) set(ListenPhase.Ended("Impossible d'envoyer la demande : ${sent.exceptionOrNull()?.message}"))
    }

    // Chronomètre pendant l'écoute.
    LaunchedEffect(phase) {
        while (phase == ListenPhase.Live) { delay(1000); seconds++ }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Quitter l'écran = raccrocher : le relais coupe aussitôt le micro de l'enfant.
            socket?.close(1000, "fin")
            track?.let { runCatching { it.stop() }; it.release() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(AppTheme.colors.heroGradient)).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Avatar(member.name, size = 120.dp, photo = photo, colorHex = member.color)
            Text(member.name, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            val current = phase
            Text(
                when (current) {
                    ListenPhase.Connecting -> "Connexion…"
                    ListenPhase.Waiting -> "Appel du téléphone de ${member.name}…"
                    ListenPhase.Live -> "🎧 En écoute · %d:%02d".format(seconds / 60, seconds % 60)
                    is ListenPhase.Ended -> current.message
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "${member.name} voit un écran « Un parent t'écoute » et une notification pendant toute l'écoute. Rien n'est enregistré.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.height(12.dp))
            Button(
                onClick = onBack,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(22.dp))
                Box(Modifier.size(8.dp))
                Text(if (current is ListenPhase.Ended) "Fermer" else "Raccrocher")
            }
        }
    }
}
