package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Durée de la sonnerie sur le téléphone (RingAlarm.DURATION_MS). */
private const val RING_SECONDS = 60

private enum class RingState { IDLE, SENDING, RINGING, STOPPING, STOPPED, ENDED }

/**
 * Fenêtre « Faire sonner » : lancer la sonnerie à distance (fort volume, même en
 * silencieux), suivre le compte à rebours, et l'arrêter à tout moment.
 */
@Composable
fun RingDialog(member: Member, photo: Bitmap?, api: TraccarApi, token: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(RingState.IDLE) }
    var instant by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Compte à rebours pendant la sonnerie ; à la fin, le téléphone s'arrête seul.
    LaunchedEffect(state) {
        while (state == RingState.RINGING) {
            now = System.currentTimeMillis()
            if (now - sentAt >= RING_SECONDS * 1000L) state = RingState.ENDED
            delay(250)
        }
    }

    fun ring() {
        state = RingState.SENDING
        error = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { api.ring(token, member.id) } }
                .onSuccess { ok -> instant = ok; sentAt = System.currentTimeMillis(); state = RingState.RINGING }
                .onFailure { error = it.message ?: "Impossible de faire sonner"; state = RingState.IDLE }
        }
    }

    fun stop() {
        state = RingState.STOPPING
        error = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { api.stopRing(token, member.id) } }
                .onSuccess { state = RingState.STOPPED }
                .onFailure { error = it.message ?: "Impossible d'arrêter la sonnerie"; state = RingState.RINGING }
        }
    }

    val ringing = state == RingState.RINGING || state == RingState.STOPPING
    val accent = if (ringing) Warning else MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = { if (state != RingState.SENDING && state != RingState.STOPPING) onDismiss() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Avatar entouré d'ondes qui s'élargissent tant que ça sonne.
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    if (ringing) {
                        val waves = rememberInfiniteTransition(label = "ondes")
                        listOf(0, 600, 1200).forEach { offset ->
                            val progress by waves.animateFloat(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    tween(1800, delayMillis = offset, easing = LinearEasing),
                                    RepeatMode.Restart,
                                ),
                                label = "onde",
                            )
                            Box(
                                Modifier.size(84.dp).scale(1f + progress * 0.75f).clip(CircleShape)
                                    .background(accent.copy(alpha = 0.28f * (1f - progress))),
                            )
                        }
                    } else {
                        Box(Modifier.size(104.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)))
                    }
                    Avatar(member.name, size = 84.dp, photo = photo, colorHex = member.color)
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(14.dp).size(36.dp).clip(CircleShape).background(accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state == RingState.STOPPED) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Text("Faire sonner ${member.name}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    when (state) {
                        RingState.IDLE -> "Sonnerie forte pendant 1 min, même en silencieux. " +
                            "Tu peux l'arrêter ici, ou sur le téléphone avec une touche de volume."
                        RingState.SENDING -> "Envoi de la demande…"
                        RingState.RINGING, RingState.STOPPING -> if (instant) {
                            "Ça sonne sur son téléphone."
                        } else {
                            "Téléphone pas joignable tout de suite : il sonnera à son prochain envoi de position (10 min au plus)."
                        }
                        RingState.STOPPED -> "Sonnerie arrêtée. Elle s'arrête dans les secondes qui suivent sur son téléphone."
                        RingState.ENDED -> "La sonnerie s'est arrêtée toute seule après 1 min."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    textAlign = TextAlign.Center,
                )

                // Compte à rebours.
                if (ringing) {
                    val left = (RING_SECONDS - ((now - sentAt) / 1000L).toInt()).coerceIn(0, RING_SECONDS)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Sonne encore", style = MaterialTheme.typography.labelLarge, color = Muted)
                            Text("$left s", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)
                        }
                        LinearProgressIndicator(
                            progress = { left / RING_SECONDS.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = accent,
                            trackColor = accent.copy(alpha = 0.15f),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                }

                error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }

                Spacer(Modifier.height(2.dp))
                if (ringing) {
                    // Action principale pendant la sonnerie : l'arrêter.
                    Button(
                        onClick = { stop() },
                        enabled = state == RingState.RINGING,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
                    ) {
                        if (state == RingState.STOPPING) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NotificationsOff, contentDescription = null)
                        }
                        Spacer(Modifier.size(10.dp))
                        Text("Arrêter la sonnerie", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { ring() },
                        enabled = state != RingState.SENDING,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Warning, contentColor = Color.White),
                    ) {
                        if (state == RingState.SENDING) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null)
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(if (state == RingState.IDLE) "Faire sonner" else "Faire sonner à nouveau", fontWeight = FontWeight.SemiBold)
                    }
                    // Une sonnerie lancée plus tôt (fenêtre fermée entre-temps) peut encore être arrêtée.
                    if (state == RingState.IDLE || state == RingState.ENDED) {
                        OutlinedButton(
                            onClick = { stop() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Arrêter une sonnerie en cours")
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = state != RingState.SENDING && state != RingState.STOPPING,
                ) { Text("Fermer") }
            }
        }
    }
}
