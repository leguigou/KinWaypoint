package fr.kinwaypoint.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.net.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saisie d'un code de configuration : un lien HTTPS collé ou scanné (QR).
 * Le lien est appelé, le JSON reçu est validé puis rendu via [onResult].
 *
 * @param expected mode attendu (null = n'importe lequel, ex. au premier lancement)
 * @param initialLink lien déjà connu (ex. QR scanné) : il est validé tout de suite
 */
@Composable
fun ConfigCodeDialog(
    actions: PlatformActions,
    expected: AppMode?,
    onResult: (RemoteConfig) -> Unit,
    onDismiss: () -> Unit,
    initialLink: String? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(value: String) {
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Un QR « kinwaypoint://setup » (généré par l'appli parent) vaut aussi configuration.
                    SetupPayload.decode(value)?.let { qr ->
                        RemoteConfig(
                            mode = AppMode.CHILD,
                            child = RemoteConfig.Child(qr.serverUrl, qr.deviceId, qr.intervalSeconds, qr.apiUrl, qr.viewerToken),
                            parent = null,
                            label = null,
                        )
                    } ?: if (RemoteConfig.looksLikeInviteCode(value)) {
                        // Code d'invitation à 8 chiffres : échangé contre la configuration.
                        RemoteConfig.redeemInvite(FAMILY_HUB, value)
                    } else {
                        RemoteConfig.fetch(value)
                    }
                }
            }
            busy = false
            result.onSuccess { cfg ->
                val problem = when {
                    expected == AppMode.CHILD && cfg.child == null -> "Ce code ne contient pas de configuration pour un téléphone enfant."
                    expected == AppMode.PARENT && cfg.parent == null -> "Ce code ne contient pas de configuration pour un téléphone parent."
                    expected != null && cfg.mode != null && cfg.mode != expected ->
                        if (cfg.mode == AppMode.CHILD) "Ce code est destiné à un téléphone enfant." else "Ce code est destiné à un téléphone parent."
                    else -> null
                }
                if (problem != null) error = problem else onResult(cfg)
            }.onFailure { error = it.message ?: "Configuration impossible à récupérer" }
        }
    }

    LaunchedEffect(initialLink) {
        if (!initialLink.isNullOrBlank()) submit(initialLink)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { IconBadge(Icons.Default.Link, MaterialTheme.colorScheme.primary) },
        title = { Text("Code d'invitation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Tape le code à 8 chiffres reçu d'un parent (valable 1 h), ou scanne un QR code. L'appli se remplit toute seule.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim(); error = null },
                    label = { Text("Code d'invitation") },
                    placeholder = { Text("8 chiffres (ou lien https://…)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    enabled = !busy,
                    // Pavé numérique : le cas courant est un code de 8 chiffres (un lien se colle).
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { link = it; error = null } }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Coller")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        actions.scanQr { text ->
                            if (text != null) {
                                link = text.trim()
                                submit(text.trim())
                            }
                        }
                    },
                    enabled = !busy,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Scanner un QR code")
                }
                error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Récupération de la configuration…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (RemoteConfig.looksLikeInviteCode(link) || RemoteConfig.looksLikeLink(link) || SetupPayload.decode(link) != null),
                onClick = { submit(link) },
            ) { Text("Valider") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") } },
    )
}
