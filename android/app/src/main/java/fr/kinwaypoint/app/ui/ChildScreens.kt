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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
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
    /** Relier ce téléphone (QR ou code), après le PIN parent. */
    onLink: () -> Unit = onOpenSettings,
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

    if (parentsOpen) {
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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 40.dp),
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

            // Nouvelle version : bandeau visible aussi par l'enfant (téléchargement et installation).
            UpdateBanner()

            if (!config.isValid) {
                // Pas encore relié : un seul bouton, le parent scanne ou tape le code.
                SectionCard(container = SoftOrange) {
                    Text("Téléphone pas encore relié", style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.onSoftWarning)
                    Text(
                        "Un parent le relie à la famille avec un QR code ou un code d'invitation. Rien d'autre à régler.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSoftWarning.copy(alpha = 0.8f),
                    )
                    Button(onClick = onLink, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Relier ce téléphone")
                    }
                }
            } else if (!config.enabled) {
                InfoCard(
                    title = "Suivi arrêté",
                    detail = "Un parent peut le relancer avec le cadenas en haut à droite.",
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

            if (config.canSeeParents || (config.isValid && config.enabled)) {
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
    /** Relier de nouveau ce téléphone (QR ou code d'invitation). */
    onRelink: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var confirmModeChange by remember { mutableStateOf(false) }

    // Catégorie ouverte (null = liste) ; retour = revenir à la liste, puis verrouiller.
    var category by remember { mutableStateOf<String?>(null) }
    val allowListen by repository.allowListen.collectAsState(initial = true)
    BackHandler { if (category != null) category = null else onClose() }

    fun save(enabled: Boolean, then: () -> Unit = {}) {
        scope.launch {
            // copy : les cadences en mouvement / à l'arrêt réglées par les parents sont conservées.
            // Aucun champ technique ici : la configuration vient du QR code ou du code d'invitation.
            repository.save(config.copy(enabled = enabled))
            then()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (category != null) category = null else onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (category != null) "Retour" else "Verrouiller")
                }
                Column {
                    Text(
                        when (category) {
                            "tracking" -> "Suivi"
                            "listen" -> "Écoute en direct"
                            "security" -> "Sécurité"
                            "about" -> "À propos"
                            else -> "Réglages parent"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("Suivi de cet appareil", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }

            // --- Liste des catégories ------------------------------------------------
            if (category == null) {
                UpdateBanner()
                SectionCard {
                    ChildCategoryRow(Icons.Default.MyLocation, MaterialTheme.colorScheme.primary, "Suivi",
                        when {
                            !config.isValid -> "Non configuré"
                            status.running -> "Actif · envoi toutes les ${config.intervalSeconds / 60} min"
                            else -> "Arrêté"
                        }) { category = "tracking" }
                    ChildCategoryRow(Icons.Default.Hearing, androidx.compose.ui.graphics.Color(0xFF8B5CF6), "Écoute en direct",
                        when {
                            !allowListen -> "Désactivée"
                            !permissions.microphone || !permissions.overlay -> "Autorisations à donner"
                            else -> "Autorisée"
                        }) { category = "listen" }
                    ChildCategoryRow(Icons.Default.Lock, Danger, "Sécurité", "Code PIN parent, mode de l'appareil") { category = "security" }
                    ChildCategoryRow(Icons.Default.Info, Muted, "À propos", "Version ${fr.kinwaypoint.app.BuildConfig.VERSION_NAME}") { category = "about" }
                }
            }

            if (category == "tracking") {
            StatusCard(status = status, permissions = permissions)

            SectionCard {
                Text(if (config.isValid) "Téléphone relié à la famille" else "Téléphone pas encore relié", style = MaterialTheme.typography.titleMedium)
                Text(
                    "La liaison se fait avec le QR code ou le code d'invitation d'un parent : aucune adresse ni clé à saisir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                OutlinedButton(onClick = onRelink, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (config.isValid) "Relier de nouveau" else "Relier ce téléphone")
                }
            }

            PermissionWarnings(permissions, actions)

            Button(
                onClick = {
                    when {
                        !config.isValid -> onRelink()
                        !permissions.fineLocation -> actions.requestLocation()
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
            ) { Text(if (status.running) "Redémarrer le suivi" else "Démarrer le suivi") }

            if (status.running || config.enabled) {
                FilledTonalButton(
                    onClick = { save(enabled = false) { TrackingService.stop(context) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                ) { Text("Arrêter le suivi") }
            }

            }

            if (category == "listen") {
            // --- Écoute en direct par un parent ---------------------------------------
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

            }

            if (category == "security") {
            SectionCard {
                Text("Sécurité", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Text("Modifier le code PIN parent")
                }
                OutlinedButton(onClick = { confirmModeChange = true }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Text("Changer le mode de l'appareil")
                }
            }

            }

            if (category == "about") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BrandMark(size = 52.dp)
                        Column(Modifier.weight(1f)) {
                            Text("KinWaypoint", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Version ${fr.kinwaypoint.app.BuildConfig.VERSION_NAME} (${fr.kinwaypoint.app.BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                    }
                }
                UpdateBanner()
            }

            if (category == null) {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Terminé — verrouiller")
            }
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
    if (permissions.fineLocation && !permissions.dndAccess) {
        PermissionRow(
            "Sonner même en « Ne pas déranger »",
            "Pour que « Faire sonner » s'entende aussi en mode Ne pas déranger : autorise KinWaypoint dans « Accès Ne pas déranger ».",
            "Autoriser",
            actions.openDndSettings,
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
    // Adresse de secours sans accès « Ma famille » : aucun appel n'est fait dans ce cas.
    val api = remember(config.apiUrl) { fr.kinwaypoint.app.net.TraccarApi(config.apiUrl.ifBlank { "https://localhost" }) }
    var parents by remember { mutableStateOf<List<fr.kinwaypoint.app.net.Member>>(emptyList()) }
    var places by remember { mutableStateOf<List<fr.kinwaypoint.app.net.Place>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        // Sans accès « Ma famille » (ancien QR code), la carte montre seulement « Moi ».
        if (!config.canSeeParents) return@LaunchedEffect
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
        val message = when {
            !config.canSeeParents -> "Tu te vois sur la carte. Pour voir ta famille, un parent doit rescanner ton QR code."
            error != null -> error
            parents.none { it.position != null && it.uniqueId != config.deviceId } -> "Personne d'autre ne partage sa position pour le moment."
            else -> null
        }
        // En haut, sous la barre : la carte (et « Moi ») reste visible.
        message?.let {
            androidx.compose.foundation.layout.Box(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 72.dp),
            ) { InfoCard(title = "Ma famille", detail = it, container = CardSurface, icon = Icons.Default.People) }
        }
    }
}

/** Ligne de catégorie des réglages enfant : icône teintée, titre, résumé, chevron. */
@Composable
private fun ChildCategoryRow(icon: ImageVector, tint: Color, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, tint, size = 42.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
    }
}
