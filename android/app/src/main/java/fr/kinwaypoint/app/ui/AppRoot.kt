package fr.kinwaypoint.app.ui

import androidx.compose.foundation.layout.Arrangement
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
    val status by TrackingStatus.state.collectAsState()

    var screen by remember { mutableStateOf<Screen?>(null) }

    fun home(m: AppMode): Screen = when (m) {
        AppMode.UNSET -> Screen.ModeSelect
        AppMode.CHILD -> Screen.ChildHome
        AppMode.PARENT -> Screen.PinCheck(AfterPin.Go(Screen.Parent), back = null)
    }

    // Premier écran, une fois les réglages chargés.
    LaunchedEffect(mode) {
        val m = mode ?: return@LaunchedEffect
        if (screen == null) screen = home(m)
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
                screen = if (action.mode == AppMode.CHILD) Screen.ChildSettings else Screen.Parent
            }
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    when (current) {
        Screen.ModeSelect -> ModeSelectScreen(
            onChoose = { m ->
                screen = if (pinState.isSet) {
                    Screen.PinCheck(AfterPin.ChooseMode(m), back = Screen.ModeSelect)
                } else {
                    Screen.PinSetup(AfterPin.ChooseMode(m), back = Screen.ModeSelect)
                }
            },
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
                onCancel = { screen = home(AppMode.PARENT) },
            )
        } else {
            ParentHomeScreen(
                repository = repository,
                config = parentCfg,
                acknowledgedAlarmId = acknowledged,
                actions = actions,
                onLock = { screen = home(AppMode.PARENT) },
                onChangePin = { screen = Screen.PinCheck(AfterPin.NewPin(Screen.Parent), back = Screen.Parent) },
                onChangeMode = { changeMode() },
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
        )

        is Screen.PinSetup -> PinSetupScreen(
            repository = repository,
            onDone = { afterPin(current.then) },
            onCancel = current.back?.let { back -> { screen = back } },
        )
    }
}

@Composable
private fun ModeSelectScreen(onChoose: (AppMode) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("KinWaypoint", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Green)
        Text(
            "À qui est ce téléphone ?",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { onChoose(AppMode.CHILD) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Enfant", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Partage sa position, bouton SOS", fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = { onChoose(AppMode.PARENT) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Parent", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Voit la famille, protégé par code PIN", fontSize = 12.sp)
            }
        }
        Text(
            "Le code PIN parent sera demandé pour ouvrir l'espace parent et pour modifier ou arrêter le suivi d'un enfant.",
            fontSize = 13.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}
