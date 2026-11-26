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
import androidx.compose.material3.FilledTonalButton
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

/** Pavé numérique générique : points, chiffres, effacer, valider. */
@Composable
fun PinPad(
    title: String,
    subtitle: String,
    error: String?,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var pin by remember { mutableStateOf("") }

    onCancel?.let { BackHandler(onBack = it) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(maxOf(Pin.MIN_LENGTH, pin.length)) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .background(if (i < pin.length) Green else Color(0xFFD5DEDB)),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(error.orEmpty(), color = Danger, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.height(40.dp))

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { key ->
                        FilledTonalButton(
                            onClick = {
                                when (key) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "OK" -> if (Pin.isWellFormed(pin)) {
                                        val value = pin
                                        pin = ""
                                        onSubmit(value)
                                    }
                                    else -> if (pin.length < Pin.MAX_LENGTH) pin += key
                                }
                            },
                            enabled = enabled && (key != "OK" || Pin.isWellFormed(pin)),
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                        ) { Text(key, fontSize = if (key.length > 1) 16.sp else 24.sp) }
                    }
                }
            }
        }

        if (onCancel != null) {
            Spacer(Modifier.height(16.dp))
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
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        lockedUntil = repository.pin.first().lockedUntil
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
    )
}
