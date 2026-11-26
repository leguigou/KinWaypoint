package fr.kinwaypoint.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import fr.kinwaypoint.app.service.TrackingService
import fr.kinwaypoint.app.service.TrackingStatusState
import kotlinx.coroutines.launch

/** Écran que voit l'enfant : état du suivi et bouton SOS. Rien ne peut y être modifié. */
@Composable
fun ChildHomeScreen(
    config: TrackingConfig,
    status: TrackingStatusState,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current

    // Le suivi doit tourner dès qu'il est activé : si Android l'a arrêté, on le relance.
    LaunchedEffect(config, permissions.fineLocation, status.running) {
        if (config.enabled && config.isValid && permissions.fineLocation && !status.running) {
            TrackingService.start(context)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("KinWaypoint", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Green)
            Text("Cet appareil partage sa position avec ta famille.", fontSize = 14.sp, color = Muted)

            if (!config.isValid || !config.enabled) {
                InfoCard(
                    title = "Suivi pas encore activé",
                    detail = "Un parent doit le régler dans « Réglages parent ».",
                    container = SoftOrange,
                )
            } else {
                StatusCard(status = status, permissions = permissions)
            }

            PermissionWarnings(permissions, actions)

            if (config.isValid) {
                SosHoldButton(
                    onTrigger = {
                        if (!permissions.fineLocation) {
                            actions.requestLocation()
                        } else {
                            TrackingService.sos(context)
                            Toast.makeText(context, "Alerte SOS déclenchée", Toast.LENGTH_LONG).show()
                        }
                    },
                )
                SosFeedback(status)
            }

            TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("🔒 Réglages parent")
            }
        }
    }
}

/** Réglages du suivi, accessibles uniquement après saisie du PIN parent. */
@Composable
fun ChildSettingsScreen(
    repository: SettingsRepository,
    config: TrackingConfig,
    status: TrackingStatusState,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onClose: () -> Unit,
    onChangePin: () -> Unit,
    onChangeMode: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var deviceId by remember { mutableStateOf(config.deviceId) }
    var interval by remember { mutableStateOf(config.intervalSeconds.toString()) }
    var confirmModeChange by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    fun save(enabled: Boolean, then: () -> Unit = {}) {
        scope.launch {
            repository.save(
                TrackingConfig(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    intervalSeconds = (interval.toIntOrNull() ?: 60).coerceIn(15, 3600),
                    enabled = enabled,
                ),
            )
            then()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Réglages parent", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Green)
            Text("Mode enfant — réglages du suivi de cet appareil.", fontSize = 14.sp, color = Muted)

            StatusCard(status = status, permissions = permissions)

            Button(
                onClick = {
                    actions.scanQr { text ->
                        val payload = SetupPayload.decode(text)
                        when {
                            text == null -> Unit
                            payload == null -> Toast.makeText(context, "Ce QR code n'est pas un code KinWaypoint", Toast.LENGTH_LONG).show()
                            else -> {
                                serverUrl = payload.serverUrl
                                deviceId = payload.deviceId
                                payload.intervalSeconds?.let { interval = it.toString() }
                                Toast.makeText(context, "Configuration lue — vérifie puis enregistre", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Scanner le QR code de configuration") }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Configuration", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Serveur d'envoi") },
                        placeholder = { Text("https://track.exemple.fr") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("Identifiant de l'appareil") },
                        placeholder = { Text("long et aléatoire") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                        label = { Text("Intervalle (secondes, 15 à 3600)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            PermissionWarnings(permissions, actions)

            Button(
                onClick = {
                    when {
                        !permissions.fineLocation -> actions.requestLocation()
                        deviceId.isBlank() || serverUrl.isBlank() ->
                            Toast.makeText(context, "Renseigne le serveur et l'identifiant", Toast.LENGTH_SHORT).show()
                        !serverUrl.trim().startsWith("https://") && !serverUrl.trim().startsWith("http://") ->
                            Toast.makeText(context, "L'adresse doit commencer par https://", Toast.LENGTH_SHORT).show()
                        else -> save(enabled = true) {
                            // Redémarrage pour prendre en compte la nouvelle configuration.
                            TrackingService.stop(context)
                            TrackingService.start(context)
                            Toast.makeText(context, "Suivi actif", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (status.running) "Enregistrer et redémarrer le suivi" else "Enregistrer et démarrer le suivi") }

            if (status.running || config.enabled) {
                Button(
                    onClick = { save(enabled = false) { TrackingService.stop(context) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B7A75)),
                ) { Text("Arrêter le suivi") }
            }

            OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) { Text("Changer le code PIN") }
            OutlinedButton(onClick = { confirmModeChange = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Changer le mode de l'appareil")
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Terminé — verrouiller")
            }
        }
    }

    if (confirmModeChange) {
        AlertDialog(
            onDismissRequest = { confirmModeChange = false },
            title = { Text("Changer de mode ?") },
            text = { Text("Le suivi de cet appareil sera arrêté. Le code PIN est conservé.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmModeChange = false
                    TrackingService.stop(context)
                    onChangeMode()
                }) { Text("Changer") }
            },
            dismissButton = { TextButton(onClick = { confirmModeChange = false }) { Text("Annuler") } },
        )
    }
}

/**
 * Bouton SOS à maintenir 3 secondes : évite les déclenchements accidentels
 * (poche, petit frère…). Une jauge se remplit, puis vibration à l'envoi.
 */
@Composable
fun SosHoldButton(onTrigger: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val trigger by rememberUpdatedState(onTrigger)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Danger)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(durationMillis = 3000, easing = LinearEasing))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            trigger()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(durationMillis = 250)) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value)
                .background(Color(0xFF7F0000)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Maintenir appuyé 3 secondes", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SosFeedback(status: TrackingStatusState) {
    when {
        status.sosPending -> InfoCard(
            title = "Alerte SOS en cours d'envoi…",
            detail = "Elle partira dès que le réseau le permet. Garde le téléphone avec toi.",
            container = SoftRed,
        )
        status.sosDeliveredAt != null -> InfoCard(
            title = "✓ Alerte SOS reçue à ${formatTime(status.sosDeliveredAt)}",
            detail = "Ta famille a été prévenue par le serveur.",
            container = SoftGreen,
        )
    }
}

@Composable
fun StatusCard(status: TrackingStatusState, permissions: PermissionSnapshot) {
    val dot = when {
        !status.running -> Idle
        status.lastOk == false -> Warning
        else -> Ok
    }
    val title = when {
        !status.running -> "Suivi arrêté"
        status.lastOk == false -> "En attente de réseau"
        status.lastSentAt == null -> "Recherche d'une position…"
        else -> "Suivi actif"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SoftGreen),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = dot, fontSize = 16.sp)
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            status.lastSentAt?.let { Text("Dernier envoi : ${formatTime(it)}", fontSize = 13.sp, color = Muted) }
            if (status.running && status.still) {
                Text("À l'arrêt — GPS en pause, position toutes les 10 min", fontSize = 13.sp, color = Muted)
            }
            status.battery?.let { Text("Batterie : $it %", fontSize = 13.sp, color = Muted) }
            status.accuracy?.let { Text("Précision : ${it.toInt()} m", fontSize = 13.sp, color = Muted) }
            if (status.queued > 0) {
                Text("${status.queued} position(s) en attente de réseau", fontSize = 13.sp, color = Warning)
            }
            if (status.lastOk == false && status.lastError != null) {
                Text("Dernière erreur : ${status.lastError}", fontSize = 12.sp, color = Muted)
            }
            if (!permissions.notifications) {
                Text("Notifications désactivées : le suivi peut être interrompu.", fontSize = 13.sp, color = Warning)
            }
        }
    }
}

@Composable
fun PermissionWarnings(permissions: PermissionSnapshot, actions: PlatformActions) {
    if (!permissions.fineLocation) {
        PermissionRow("Localisation requise", "Sans elle, aucun suivi n'est possible.", "Autoriser", actions.requestLocation)
    } else if (!permissions.backgroundLocation) {
        PermissionRow(
            "Localisation en arrière-plan",
            "Choisir « Toujours autoriser » dans les réglages de l'app.",
            "Ouvrir",
            actions.openAppSettings,
        )
    }
    if (permissions.batteryOptimized) {
        PermissionRow(
            "Optimisation batterie active",
            "Android peut couper le suivi écran éteint.",
            "Désactiver",
            actions.openBatterySettings,
        )
    }
}

@Composable
private fun PermissionRow(label: String, detail: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SoftOrange),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 13.sp, color = Color(0xFF6B5A45))
            OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) { Text(action) }
        }
    }
}

@Composable
fun InfoCard(title: String, detail: String, container: Color) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 13.sp, color = Muted)
        }
    }
}
