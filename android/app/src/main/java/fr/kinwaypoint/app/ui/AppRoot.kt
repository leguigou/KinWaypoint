package fr.kinwaypoint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import fr.kinwaypoint.app.net.RemoteConfig
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.service.TrackingStatus
import kotlinx.coroutines.launch

/** Écrans de l'application. Les écrans « protégés » exigent le PIN parent. */
sealed interface Screen {
    data object ModeSelect : Screen
    data object ChildHome : Screen
    data object ChildSettings : Screen // protégé
    data object Parent : Screen // protégé
    data class PinCheck(val then: AfterPin, val back: Screen?) : Screen
    data class PinSetup(val then: AfterPin, val back: Screen?) : Screen
}

/** Ce qui se passe après un PIN correct. */
sealed interface AfterPin {
    data class Go(val screen: Screen) : AfterPin
    data class ChooseMode(val mode: AppMode) : AfterPin
    data class NewPin(val back: Screen) : AfterPin
}

private fun Screen.isProtected() = this is Screen.ChildSettings || this is Screen.Parent

/**
 * Racine de l'application : un seul APK, deux modes.
 * - Enfant : écran simple (état + SOS) ; réglages derrière le PIN parent.
 * - Parent : tout l'espace est derrière le PIN.
 *
 * `lockSignal` est incrémenté par l'activité quand l'appli est restée en
 * arrière-plan trop longtemps : les écrans protégés se reverrouillent.
 */
@Composable
fun AppRoot(
    repository: SettingsRepository,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    lockSignal: Int,
) {
    val scope = rememberCoroutineScope()
    val mode by repository.mode.collectAsState(initial = null)
    val pin by repository.pin.collectAsState(initial = null)
    val config by repository.config.collectAsState(initial = null)
    val parent by repository.parent.collectAsState(initial = null)
    val acknowledged by repository.acknowledgedAlarm.collectAsState(initial = 0L)
    val biometric by repository.biometricEnabled.collectAsState(initial = false)
    val status by TrackingStatus.state.collectAsState()

    var screen by remember { mutableStateOf<Screen?>(null) }
    // Configuration reçue par code au premier lancement, appliquée une fois le mode choisi (après le PIN).
    var pendingRemote by remember { mutableStateOf<RemoteConfig?>(null) }
    var showConfigCode by remember { mutableStateOf(false) }

    fun home(m: AppMode): Screen = when (m) {
        AppMode.UNSET -> Screen.ModeSelect
        AppMode.CHILD -> Screen.ChildHome
        AppMode.PARENT -> Screen.PinCheck(AfterPin.Go(Screen.Parent), back = null)
    }

    // Premier écran, une fois les réglages chargés. Espace parent encore ouvert
    // (délai de verrouillage non écoulé, ou « jamais ») : on y va directement.
    LaunchedEffect(mode) {
        val m = mode ?: return@LaunchedEffect
        if (screen == null) {
            screen = if (m == AppMode.PARENT && repository.parentSessionValid()) Screen.Parent else home(m)
        }
    }

    // Mémorise si l'espace parent est ouvert (pour le prochain lancement).
    LaunchedEffect(screen) {
        when (screen) {
            Screen.Parent -> repository.setParentUnlocked(true)
            is Screen.PinCheck, Screen.ModeSelect -> repository.setParentUnlocked(false)
            else -> Unit
        }
    }

    // Reverrouillage après un passage prolongé en arrière-plan.
    LaunchedEffect(lockSignal) {
        val m = mode ?: return@LaunchedEffect
        val current = screen ?: return@LaunchedEffect
        if (lockSignal > 0 && (current.isProtected() || current is Screen.PinSetup && m != AppMode.UNSET)) {
            screen = home(m)
        }
    }

    fun afterPin(action: AfterPin) {
        when (action) {
            is AfterPin.Go -> screen = action.screen
            is AfterPin.NewPin -> screen = Screen.PinSetup(AfterPin.Go(action.back), back = action.back)
            is AfterPin.ChooseMode -> scope.launch {
                repository.setMode(action.mode)
                pendingRemote?.let { cfg ->
                    cfg.applyTo(repository)
                    pendingRemote = null
                }
                screen = if (action.mode == AppMode.CHILD) Screen.ChildSettings else Screen.Parent
            }
        }
    }

    fun chooseMode(m: AppMode) {
        screen = if (pin?.isSet == true) {
            Screen.PinCheck(AfterPin.ChooseMode(m), back = Screen.ModeSelect)
        } else {
            Screen.PinSetup(AfterPin.ChooseMode(m), back = Screen.ModeSelect)
        }
    }

    fun changeMode() {
        scope.launch {
            repository.resetMode()
            screen = Screen.ModeSelect
        }
    }

    val current = screen
    val pinState = pin
    val cfg = config
    val parentCfg = parent
    if (current == null || pinState == null || cfg == null || parentCfg == null) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) { BrandMark(size = 72.dp) }
        return
    }

    when (current) {
        Screen.ModeSelect -> ModeSelectScreen(
            onChoose = { m -> chooseMode(m) },
            onConfigCode = { showConfigCode = true },
        )

        Screen.ChildHome -> ChildHomeScreen(
            config = cfg,
            status = status,
            permissions = permissions,
            actions = actions,
            onOpenSettings = {
                screen = Screen.PinCheck(AfterPin.Go(Screen.ChildSettings), back = Screen.ChildHome)
            },
        )

        Screen.ChildSettings -> ChildSettingsScreen(
            repository = repository,
            config = cfg,
            status = status,
            permissions = permissions,
            actions = actions,
            onClose = { screen = Screen.ChildHome },
            onChangePin = { screen = Screen.PinCheck(AfterPin.NewPin(Screen.ChildSettings), back = Screen.ChildSettings) },
            onChangeMode = { changeMode() },
        )

        Screen.Parent -> if (!parentCfg.isConnected) {
            ParentLoginScreen(
                repository = repository,
                current = parentCfg,
                actions = actions,
                onCancel = { screen = home(AppMode.PARENT) },
            )
        } else {
            ParentHomeScreen(
                repository = repository,
                config = parentCfg,
                acknowledgedAlarmId = acknowledged,
                permissions = permissions,
                actions = actions,
                onLock = { screen = home(AppMode.PARENT) },
                onChangePin = { screen = Screen.PinCheck(AfterPin.NewPin(Screen.Parent), back = Screen.Parent) },
                onChangeMode = { changeMode() },
                tracking = cfg,
                status = status,
            )
        }

        is Screen.PinCheck -> PinCheckScreen(
            repository = repository,
            subtitle = when (current.then) {
                is AfterPin.NewPin -> "Saisis le code actuel avant d'en choisir un nouveau."
                is AfterPin.ChooseMode -> "Le code parent est requis pour changer le mode."
                is AfterPin.Go -> if (current.then.screen == Screen.Parent) {
                    "Saisis ton code pour ouvrir l'espace parent."
                } else {
                    "Réservé aux parents."
                }
            },
            onSuccess = { afterPin(current.then) },
            onCancel = current.back?.let { back -> { screen = back } },
            // Empreinte : uniquement pour ouvrir l'espace parent, sur un téléphone en mode parent.
            // Jamais côté enfant (son propre doigt peut être enregistré sur son téléphone),
            // ni pour changer de PIN ou de mode.
            onBiometric = if (
                biometric && mode == AppMode.PARENT &&
                current.then == AfterPin.Go(Screen.Parent) && actions.biometricAvailable()
            ) {
                actions.authenticate
            } else {
                null
            },
        )

        is Screen.PinSetup -> PinSetupScreen(
            repository = repository,
            onDone = { afterPin(current.then) },
            onCancel = current.back?.let { back -> { screen = back } },
        )
    }

    if (showConfigCode && current == Screen.ModeSelect) {
        ConfigCodeDialog(
            actions = actions,
            expected = null,
            onDismiss = { showConfigCode = false },
            onResult = { cfg ->
                showConfigCode = false
                pendingRemote = cfg
                // Mode imposé par le code, ou déduit de son contenu ; sinon l'utilisateur choisit.
                val mode = cfg.mode ?: when {
                    cfg.child != null && cfg.parent == null -> AppMode.CHILD
                    cfg.parent != null && cfg.child == null -> AppMode.PARENT
                    else -> null
                }
                if (mode != null) chooseMode(mode)
            },
        )
    }
}

@Composable
private fun ModeSelectScreen(onChoose: (AppMode) -> Unit, onConfigCode: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(AppTheme.colors.softPrimary, MaterialTheme.colorScheme.background),
                ),
            )
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 88.dp)
        Spacer(Modifier.height(8.dp))
        Text("KinWaypoint", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Le point de ralliement de ta famille",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "À qui est ce téléphone ?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        ModeCard(
            icon = Icons.Default.ChildCare,
            tint = MaterialTheme.colorScheme.secondary,
            title = "Enfant",
            detail = "Partage sa position, bouton SOS",
            onClick = { onChoose(AppMode.CHILD) },
        )
        ModeCard(
            icon = Icons.Default.Shield,
            tint = MaterialTheme.colorScheme.primary,
            title = "Parent",
            detail = "Voit la famille sur la carte, protégé par code",
            onClick = { onChoose(AppMode.PARENT) },
        )
        FilledTonalButton(onClick = onConfigCode, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("J'ai un code d'invitation")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Un code PIN parent sera créé. Il protège l'espace parent et empêche l'enfant de modifier ou d'arrêter son suivi.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ModeCard(icon: ImageVector, tint: Color, title: String, detail: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconBadge(icon, tint, size = 52.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
        }
    }
}
