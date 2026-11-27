package fr.kinwaypoint.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
    var parentsOpen by remember { mutableStateOf(false) }

    // Objectif de pas choisi par les parents (lu sur le serveur via l'accès lecteur), 8 000 sinon.
    var stepGoal by remember { mutableStateOf(fr.kinwaypoint.app.net.DEFAULT_STEP_GOAL) }
    LaunchedEffect(config.apiUrl, config.viewerToken, config.deviceId) {
        if (!config.canSeeParents) return@LaunchedEffect
        val api = fr.kinwaypoint.app.net.TraccarApi(config.apiUrl)
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { api.members(config.viewerToken).firstOrNull { it.uniqueId == config.deviceId }?.stepGoal }.getOrNull()
            }?.let { stepGoal = it }
            kotlinx.coroutines.delay(30 * 60_000L)
        }
    }

    if (parentsOpen && config.canSeeParents) {
        ChildParentsMap(config, permissions, actions, onBack = { parentsOpen = false })
        return
    }

    // Le suivi doit tourner dès qu'il est activé : si Android l'a arrêté, on le relance.
    LaunchedEffect(config, permissions.fineLocation, status.running) {
        if (config.enabled && config.isValid && permissions.fineLocation && !status.running) {
            TrackingService.start(context)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text("KinWaypoint", style = MaterialTheme.typography.titleLarge)
                    Text("Ta famille sait où tu es", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Lock, contentDescription = "Réglages parent", tint = Muted)
                }
            }

            if (!config.isValid || !config.enabled) {
                InfoCard(
                    title = "Suivi pas encore activé",
                    detail = "Un parent doit le régler avec le cadenas en haut à droite.",
                    container = SoftOrange,
                    icon = Icons.Default.Schedule,
                )
            } else {
                StatusCard(status = status, permissions = permissions)
            }

            // Écoute en direct en cours : l'enfant le voit toujours.
            val listening by fr.kinwaypoint.app.service.ListenService.active.collectAsState()
            if (listening) {
                InfoCard(
                    title = "🎧 Un parent t'écoute en direct",
                    detail = "Ton micro est partagé pour ta sécurité. Rien n'est enregistré.",
                    container = SoftGreen,
                    icon = androidx.compose.material.icons.Icons.Default.Hearing,
                    tint = AppTheme.colors.success,
                )
            }

            status.steps?.let { steps ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StepGoalRing(steps, stepGoal, MaterialTheme.colorScheme.primary, size = 110.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Mes pas aujourd'hui", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (steps >= stepGoal) {
                                    "Bravo, objectif atteint ! 🎉"
                                } else {
                                    "Encore ${formatSteps(stepGoal - steps)} pas pour atteindre ${formatSteps(stepGoal)}."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted,
                            )
                        }
                    }
                }
            }

            PermissionWarnings(permissions, actions)

            if (config.canSeeParents) {
                FilledTonalButton(
                    onClick = { parentsOpen = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Text("Ouvrir la carte")
                }
            }

            if (config.isValid && config.enabled) {
                OutlinedButton(
                    onClick = {
                        TrackingService.checkIn(context)
                        Toast.makeText(context, "Message envoyé : tu es bien arrivé·e ✅", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                ) { Text("✅ Je suis bien arrivé·e") }
            }

            if (config.isValid) {
                Spacer(Modifier.height(4.dp))
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
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Réglages parent")
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

    // Clé = config : si la configuration change (code reçu, enregistrement), les champs suivent.
    var serverUrl by remember(config) { mutableStateOf(config.serverUrl) }
    var deviceId by remember(config) { mutableStateOf(config.deviceId) }
    var interval by remember(config) { mutableStateOf(config.intervalSeconds.toString()) }
    // Accès « Voir mes parents » (rempli par QR ou code, jamais saisi à la main).
    var viewerApi by remember(config) { mutableStateOf(config.apiUrl) }
    var viewerToken by remember(config) { mutableStateOf(config.viewerToken) }
    var confirmModeChange by remember { mutableStateOf(false) }
    var showConfigCode by remember { mutableStateOf(false) }
    var scannedLink by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)

    fun save(enabled: Boolean, then: () -> Unit = {}) {
        scope.launch {
            repository.save(
                TrackingConfig(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    intervalSeconds = (interval.toIntOrNull() ?: 60).coerceIn(15, 3600),
                    enabled = enabled,
                    apiUrl = viewerApi,
                    viewerToken = viewerToken,
                ),
            )
            then()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Verrouiller") }
                Column {
                    Text("Réglages parent", style = MaterialTheme.typography.headlineSmall)
                    Text("Suivi de cet appareil", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }

            StatusCard(status = status, permissions = permissions)

            UpdateBanner()

            Button(
                onClick = {
                    actions.scanQr { text ->
                        val payload = SetupPayload.decode(text)
                        when {
                            text == null -> Unit
                            // QR contenant un lien de configuration : on va chercher la configuration.
                            fr.kinwaypoint.app.net.RemoteConfig.looksLikeLink(text) -> {
                                scannedLink = text.trim()
                                showConfigCode = true
                            }
                            payload == null -> Toast.makeText(context, "Ce QR code n'est pas un code KinWaypoint", Toast.LENGTH_LONG).show()
                            else -> {
                                serverUrl = payload.serverUrl
                                deviceId = payload.deviceId
                                payload.intervalSeconds?.let { interval = it.toString() }
                                if (payload.apiUrl != null && payload.viewerToken != null) {
                                    viewerApi = payload.apiUrl
                                    viewerToken = payload.viewerToken
                                }
                                Toast.makeText(context, "Configuration lue — vérifie puis enregistre", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Scanner le QR code de configuration")
            }

            OutlinedButton(
                onClick = { scannedLink = null; showConfigCode = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("J'ai un code de configuration")
            }

            SectionCard {
                Text("Configuration", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Serveur d'envoi") },
                    placeholder = { Text("https://track.exemple.fr") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("Identifiant de l'appareil") },
                    placeholder = { Text("long et aléatoire") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                    label = { Text("Intervalle en mouvement (secondes)") },
                    supportingText = { Text("15 à 3600. À l'arrêt : une position toutes les 10 min.") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.success, contentColor = Color.White),
            ) { Text(if (status.running) "Enregistrer et redémarrer le suivi" else "Enregistrer et démarrer le suivi") }

            if (status.running || config.enabled) {
                FilledTonalButton(
                    onClick = { save(enabled = false) { TrackingService.stop(context) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                ) { Text("Arrêter le suivi") }
            }

            // --- Écoute en direct par un parent ---------------------------------------
            val allowListen by repository.allowListen.collectAsState(initial = true)
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Écoute en direct par un parent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Un parent peut écouter le micro de ce téléphone. C'est toujours visible ici " +
                                "(écran « Un parent t'écoute » et notification permanente). Rien n'est enregistré. " +
                                "Deux autorisations sont indispensables : le micro, et « afficher par-dessus les autres applis ».",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = allowListen,
                        onCheckedChange = { on -> scope.launch { repository.setAllowListen(on) } },
                    )
                }
            }
            // Autorisations indispensables tant qu'elles manquent : l'avertissement
            // et le bouton restent affichés pour ne pas les oublier à la configuration.
            if (allowListen && !permissions.microphone) {
                PermissionRow(
                    "Micro à autoriser (indispensable)",
                    "Sans accès au micro, l'écoute en direct ne peut pas fonctionner.",
                    "Autoriser le micro",
                    actions.requestMicrophone,
                )
            }
            if (allowListen && !permissions.overlay) {
                PermissionRow(
                    "Affichage par-dessus à autoriser (indispensable)",
                    "Sans cette autorisation, l'écoute ne démarre pas toute seule : l'enfant devrait toucher une " +
                        "notification. Avec, l'écran « Un parent t'écoute » s'ouvre et le micro démarre sans qu'il touche le téléphone.",
                    "Autoriser l'affichage par-dessus",
                    actions.openOverlaySettings,
                )
            }

            SectionCard {
                Text("Sécurité", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Text("Changer le code PIN")
                }
                OutlinedButton(onClick = { confirmModeChange = true }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Text("Changer le mode de l'appareil")
                }
            }

            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Terminé — verrouiller")
            }
        }
    }

    if (showConfigCode) {
        ConfigCodeDialog(
            actions = actions,
            expected = fr.kinwaypoint.app.data.AppMode.CHILD,
            initialLink = scannedLink,
            onDismiss = { showConfigCode = false },
            onResult = { cfg ->
                showConfigCode = false
                cfg.child?.let { c ->
                    serverUrl = c.serverUrl
                    deviceId = c.deviceId
                    c.intervalSeconds?.let { interval = it.toString() }
                    if (c.apiUrl != null && c.viewerToken != null) {
                        viewerApi = c.apiUrl
                        viewerToken = c.viewerToken
                    }
                }
                Toast.makeText(
                    context,
                    (cfg.label?.let { "$it — " } ?: "") + "configuration reçue, vérifie puis enregistre",
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
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
 * Gros bouton SOS rond à maintenir 3 secondes : évite les déclenchements
 * accidentels (poche, petit frère…). Un anneau se remplit, vibration à l'envoi.
 * Au repos, un halo pulse doucement pour qu'on le repère tout de suite.
 */
@Composable
fun SosHoldButton(onTrigger: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val trigger by rememberUpdatedState(onTrigger)
    val colors = AppTheme.colors
    val ring = MaterialTheme.colorScheme.secondary

    val pulse = rememberInfiniteTransition(label = "pulse")
    val halo by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "halo",
    )

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(250.dp)) {
            // Halo pulsant
            Box(
                Modifier.size(200.dp).scale(if (progress.value > 0f) 1f else halo).clip(CircleShape)
                    .background(colors.danger.copy(alpha = 0.12f)),
            )
            // Anneau de progression de l'appui
            Canvas(Modifier.size(236.dp)) {
                val stroke = 10.dp.toPx()
                drawArc(
                    color = ring.copy(alpha = 0.15f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = ring,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // Bouton
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .scale(1f - 0.05f * progress.value)
                    .shadow(16.dp, CircleShape, ambientColor = colors.danger, spotColor = colors.danger)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colors.sosGradient))
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
                                scope.launch { progress.animateTo(0f, tween(durationMillis = 300)) }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SOS", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Text(
                        if (progress.value > 0f) "Continue d'appuyer…" else "Maintenir 3 s",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Text(
            "En cas de danger, maintiens le bouton : ta famille est prévenue avec ta position.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun SosFeedback(status: TrackingStatusState) {
    when {
        status.sosPending -> InfoCard(
            title = "Alerte SOS en cours d'envoi…",
            detail = "Elle partira dès que le réseau le permet. Garde le téléphone avec toi.",
            container = SoftRed,
            icon = Icons.Default.Warning,
            tint = Danger,
        )
        status.sosDeliveredAt != null -> InfoCard(
            title = "Alerte SOS reçue à ${formatTime(status.sosDeliveredAt)}",
            detail = "Ta famille a été prévenue par le serveur.",
            container = AppTheme.colors.softSuccess,
            icon = Icons.Default.CheckCircle,
            tint = Ok,
        )
    }
}

/** Bandeau d'état du suivi, en dégradé, avec des pastilles d'information. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatusCard(status: TrackingStatusState, permissions: PermissionSnapshot) {
    val (icon, title) = when {
        !status.running -> Icons.Default.PauseCircle to "Suivi arrêté"
        status.lastOk == false -> Icons.Default.CloudOff to "En attente de réseau"
        status.lastSentAt == null -> Icons.Default.MyLocation to "Recherche d'une position…"
        status.still -> Icons.Default.CheckCircle to "Suivi actif · à l'arrêt"
        else -> Icons.Default.CheckCircle to "Suivi actif"
    }
    val gradient = when {
        !status.running -> listOf(Color(0xFF6B6F86), Color(0xFF8A8EA6))
        status.lastOk == false -> listOf(Color(0xFFF59F00), Color(0xFFFF7A59))
        else -> AppTheme.colors.heroGradient
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, MaterialTheme.shapes.large, ambientColor = gradient.first(), spotColor = gradient.first())
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(gradient))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Color.White) }
            Column {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                status.lastSentAt?.let {
                    Text("Dernier envoi à ${formatTime(it)}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            status.battery?.let { HeroChip(Icons.Default.BatteryFull, "$it %") }
            status.steps?.let { HeroChip(Icons.AutoMirrored.Filled.DirectionsWalk, "${formatSteps(it)} pas") }
            activityLabel(status.activity, status.kmh)?.let { HeroChip(Icons.Default.Speed, it) }
            status.accuracy?.let { HeroChip(Icons.Default.GpsFixed, "± ${it.toInt()} m") }
            if (status.running && status.still) HeroChip(Icons.Default.Schedule, "toutes les 10 min")
            if (status.running && status.boosting) HeroChip(Icons.Default.MyLocation, "suivi rapproché")
        }
        if (status.queued > 0) {
            Text("${status.queued} position(s) gardée(s) en attendant le réseau", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        if (status.lastOk == false && status.lastError != null) {
            Text("Dernière erreur : ${status.lastError}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
        if (!permissions.notifications) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Notifications désactivées : le suivi peut être interrompu.", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HeroChip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
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
    if (permissions.fineLocation && !permissions.activityRecognition) {
        PermissionRow(
            "Activité physique (podomètre)",
            "Compte les pas du jour et détecte la marche pour un suivi plus précis.",
            "Autoriser",
            actions.requestLocation,
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
    SectionCard(container = SoftOrange) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.Warning, Warning, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.onSoftWarning)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSoftWarning.copy(alpha = 0.8f))
            }
        }
        FilledTonalButton(onClick = onClick, shape = CircleShape) { Text(action) }
    }
}

@Composable
fun InfoCard(
    title: String,
    detail: String,
    container: Color,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    SectionCard(container = container) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) IconBadge(icon, tint, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

/**
 * Carte de la famille vue depuis le téléphone d'un enfant (compte en lecture seule) :
 * les autres enfants, et les parents qui partagent leur position hors mode fantôme.
 * C'est le serveur qui filtre, pas l'appli.
 */
@Composable
private fun ChildParentsMap(
    config: TrackingConfig,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onBack: () -> Unit,
) {
    val api = remember(config.apiUrl) { fr.kinwaypoint.app.net.TraccarApi(config.apiUrl) }
    var parents by remember { mutableStateOf<List<fr.kinwaypoint.app.net.Member>>(emptyList()) }
    var places by remember { mutableStateOf<List<fr.kinwaypoint.app.net.Place>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { api.members(config.viewerToken) }
            }.onSuccess { parents = it; error = null }
                .onFailure { error = it.message ?: "Serveur injoignable" }
            // Lieux (maison, école…) partagés par les parents ; absents si non partagés.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { api.places(config.viewerToken) }
            }.onSuccess { places = it }
            kotlinx.coroutines.delay(30_000)
        }
    }
    val photos = rememberMemberPhotos(api, config.viewerToken, parents)

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        FamilyMapScreen(
            members = parents,
            photos = photos,
            focusId = null,
            permissions = permissions,
            actions = actions,
            onBack = onBack,
            title = "Ma famille",
            places = places,
            // Son propre appareil apparaît comme « Moi ».
            ownDeviceId = parents.firstOrNull { it.uniqueId == config.deviceId }?.id ?: 0L,
        )
        val message = error ?: if (parents.none { it.position != null && it.uniqueId != config.deviceId }) {
            "Personne d'autre ne partage sa position pour le moment."
        } else {
            null
        }
        message?.let {
            androidx.compose.foundation.layout.Box(
                Modifier.align(Alignment.Center).padding(32.dp),
            ) { InfoCard(title = "Ma famille", detail = it, container = CardSurface, icon = Icons.Default.People) }
        }
    }
}
