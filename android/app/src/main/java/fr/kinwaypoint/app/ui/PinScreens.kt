package fr.kinwaypoint.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.security.Pin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Pavé numérique générique : logo, points animés, touches rondes, empreinte. */
@Composable
fun PinPad(
    title: String,
    subtitle: String,
    error: String?,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)?,
    onBiometric: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

    onCancel?.let { BackHandler(onBack = it) }

    fun press(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (key) {
            "⌫" -> pin = pin.dropLast(1)
            "OK" -> if (Pin.isWellFormed(pin)) {
                val value = pin
                pin = ""
                onSubmit(value)
            }
            else -> if (pin.length < Pin.MAX_LENGTH) pin += key
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))

        // Points : un par chiffre saisi (au moins 4), qui grossissent quand ils se remplissent.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(maxOf(Pin.MIN_LENGTH, pin.length)) { i ->
                val filled = i < pin.length
                val dot by animateDpAsState(if (filled) 16.dp else 12.dp, label = "dot")
                Box(
                    Modifier.size(dot).clip(CircleShape).background(
                        if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            error.orEmpty(),
            color = Danger,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(44.dp),
        )

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { key ->
                        val isOk = key == "OK"
                        val keyEnabled = enabled && (!isOk || Pin.isWellFormed(pin)) && (key != "⌫" || pin.isNotEmpty())
                        Surface(
                            onClick = { press(key) },
                            enabled = keyEnabled,
                            shape = CircleShape,
                            color = when {
                                isOk && keyEnabled -> MaterialTheme.colorScheme.primary
                                key == "⌫" -> Color.Transparent
                                else -> AppTheme.colors.keypad
                            },
                            contentColor = if (isOk && keyEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(76.dp).alpha(if (keyEnabled || key.length == 1) 1f else 0.4f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                when (key) {
                                    "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Effacer")
                                    "OK" -> Icon(Icons.Default.Check, contentDescription = "Valider", modifier = Modifier.size(30.dp))
                                    else -> Text(key, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (onBiometric != null) {
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onBiometric, shape = CircleShape) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Utiliser l'empreinte")
            }
        }

        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

/** Création (ou changement) du PIN parent : saisie puis confirmation. */
@Composable
fun PinSetupScreen(
    repository: SettingsRepository,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    PinPad(
        title = if (first == null) "Choisis le code PIN parent" else "Confirme le code PIN",
        subtitle = if (first == null) {
            "De ${Pin.MIN_LENGTH} à ${Pin.MAX_LENGTH} chiffres. Il protège l'espace parent et les réglages du suivi."
        } else {
            "Saisis le même code une seconde fois."
        },
        error = error,
        enabled = !busy,
        onSubmit = { value ->
            val previous = first
            when {
                previous == null -> {
                    first = value
                    error = null
                }
                previous != value -> {
                    first = null
                    error = "Les deux codes ne correspondent pas, recommence."
                }
                else -> {
                    busy = true
                    scope.launch {
                        Pin.set(repository, value)
                        busy = false
                        onDone()
                    }
                }
            }
        },
        onCancel = onCancel,
    )
}

/** Vérification du PIN parent, avec blocage temporaire après trop d'erreurs. */
@Composable
fun PinCheckScreen(
    repository: SettingsRepository,
    subtitle: String,
    onSuccess: () -> Unit,
    onCancel: (() -> Unit)?,
    /** Non nul quand l'empreinte est autorisée pour cette vérification. */
    onBiometric: ((onOk: () -> Unit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun biometricSuccess() {
        scope.launch {
            // L'empreinte reconnue vaut un PIN correct : le compteur d'erreurs repart à zéro.
            repository.recordPinResult(0, 0L)
            onSuccess()
        }
    }

    LaunchedEffect(Unit) {
        lockedUntil = repository.pin.first().lockedUntil
        // Demande d'empreinte proposée d'emblée ; « Utiliser le code PIN » la ferme.
        onBiometric?.invoke { biometricSuccess() }
    }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }

    val locked = lockedUntil > now
    PinPad(
        title = "Code PIN parent",
        subtitle = subtitle,
        error = if (locked) "Trop d'erreurs. Réessaie dans ${((lockedUntil - now) / 1000) + 1} s." else error,
        enabled = !busy && !locked,
        onSubmit = { value ->
            busy = true
            scope.launch {
                when (val result = Pin.verify(repository, value)) {
                    Pin.Result.Ok -> onSuccess()
                    is Pin.Result.Wrong -> error = "Code incorrect (encore ${result.remainingBeforeLock} essai(s) avant blocage)."
                    is Pin.Result.Locked -> lockedUntil = result.untilMillis
                }
                busy = false
            }
        },
        onCancel = onCancel,
        onBiometric = onBiometric?.let { ask -> { ask { biometricSuccess() } } },
    )
}
