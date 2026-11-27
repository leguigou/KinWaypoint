package fr.kinwaypoint.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import fr.kinwaypoint.app.net.RemoteConfig
import fr.kinwaypoint.app.service.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Liaison d'un téléphone enfant, sans rien de technique à voir ni à saisir :
 * 1. scanner le QR code affiché par un parent, ou taper le code d'invitation ;
 * 2. accorder les autorisations, une par une, avec un seul bouton ;
 * 3. le suivi démarre tout seul.
 *
 * @param relink true pour relier de nouveau un téléphone déjà configuré
 *   (sinon, un téléphone déjà relié passe directement aux autorisations).
 */
@Composable
fun ChildLinkScreen(
    repository: SettingsRepository,
    config: TrackingConfig,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    relink: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(!relink && config.isValid) }
    var label by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Nombre d'appuis par autorisation : au 2e appui sans effet (refus définitif),
    // on ouvre la page de l'appli dans les réglages Android.
    var tries by remember { mutableStateOf(mapOf<String, Int>()) }

    BackHandler { if (!busy) onCancel() }

    fun link(value: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { resolveConfigCode(value.trim()) } }
            result.onSuccess { cfg ->
                if (cfg.child == null) {
                    error = "Ce code est destiné à un téléphone parent."
                } else {
                    // Configuration appliquée et suivi activé d'office : rien à vérifier ni à enregistrer.
                    cfg.applyTo(repository)
                    repository.save(repository.config.first().copy(enabled = true))
                    label = cfg.label
                    linked = true
                }
            }.onFailure { error = it.message ?: "Liaison impossible, réessaie." }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AppTheme.colors.softPrimary, MaterialTheme.colorScheme.background)))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconButton(onClick = onCancel, enabled = !busy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
        }

        if (!linked) {
            // --- Étape 1 : QR code ou code d'invitation --------------------------------
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandMark(size = 72.dp)
                Text("Relier ce téléphone", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    "Sur le téléphone d'un parent : Paramètres → Famille → « Ajouter un enfant », " +
                        "ou un membre → ⋮ → QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    textAlign = TextAlign.Center,
                )
            }

            SectionCard {
                Text("Le parent est à côté", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { actions.scanQr { text -> if (text != null) link(text) } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Text("Scanner le QR code")
                }
            }

            SectionCard {
                Text("Le parent est à distance", style = MaterialTheme.typography.titleMedium)
                Text("Tape le code à 8 chiffres qu'il t'a donné (valable 1 h).", style = MaterialTheme.typography.bodySmall, color = Muted)
                // 8 cases ; un collage les remplit, la liaison part dès le 8e chiffre.
                CodeBoxes(
                    value = code,
                    onValueChange = { code = it; error = null },
                    onComplete = { link(it) },
                    enabled = !busy,
                )
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Liaison en cours…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            error?.let {
                InfoCard(title = "Ça n'a pas marché", detail = it, container = SoftRed, tint = Danger)
            }
            return@Column
        }

        // --- Étape 2 : autorisations, une à la fois ------------------------------------
        val steps = permissionSteps(permissions, actions)
        val next = steps.firstOrNull { it.required && !it.done }
        val optionalNext = steps.firstOrNull { !it.required && !it.done }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(AppTheme.colors.success.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppTheme.colors.success, modifier = Modifier.size(40.dp)) }
            Text(
                label?.let { "Téléphone relié : $it" } ?: "Téléphone relié",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                if (next != null) {
                    "Dernière étape : quelques autorisations pour que le suivi marche même écran éteint. Touche le bouton, Android te guide."
                } else {
                    "Tout est prêt. Le suivi est actif."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }

        SectionCard {
            steps.forEach { PermissionStepRow(it) }
        }

        Button(
            onClick = {
                val step = next ?: optionalNext
                if (step == null) {
                    TrackingService.start(context)
                    onDone()
                } else {
                    val n = tries[step.title] ?: 0
                    tries = tries + (step.title to n + 1)
                    if (n >= 1 && step.askedAtRuntime) actions.openAppSettings() else step.action()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = if (next == null && optionalNext == null) {
                ButtonDefaults.buttonColors(containerColor = AppTheme.colors.success, contentColor = Color.White)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                when {
                    next != null -> next.button
                    optionalNext != null -> optionalNext.button
                    else -> "Terminer"
                },
            )
        }
        // On peut finir plus tard : l'accueil rappellera ce qui manque.
        if (next != null || optionalNext != null) {
            TextButton(
                onClick = {
                    if (permissions.fineLocation) TrackingService.start(context)
                    onDone()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(if (next != null) "Plus tard" else "Passer, terminer maintenant") }
        }
    }
}

/** Une autorisation du parcours : faite ou non, indispensable ou facultative. */
internal class PermissionStep(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val done: Boolean,
    val required: Boolean,
    val button: String,
    val action: () -> Unit,
    /** Autorisation demandée par une fenêtre Android (sinon : page de réglage). */
    val askedAtRuntime: Boolean = false,
)

internal fun permissionSteps(p: PermissionSnapshot, a: PlatformActions): List<PermissionStep> = listOf(
    PermissionStep(Icons.Default.LocationOn, "Localisation", "Indispensable au suivi.", p.fineLocation, true, "Autoriser la localisation", a.requestLocation, askedAtRuntime = true),
    PermissionStep(
        Icons.Default.MyLocation, "Localisation « Toujours »", "Choisis « Toujours autoriser » : le suivi continue écran éteint.",
        p.backgroundLocation, true, "Autoriser « Toujours »",
        // La demande « Toujours » n'est possible qu'une fois la localisation simple accordée.
        if (p.fineLocation) a.requestBackgroundLocation else a.requestLocation,
        askedAtRuntime = true,
    ),
    PermissionStep(Icons.AutoMirrored.Filled.DirectionsWalk, "Activité physique", "Compte les pas et repère la marche.", p.activityRecognition, true, "Autoriser l'activité physique", a.requestLocation, askedAtRuntime = true),
    PermissionStep(Icons.Default.Notifications, "Notifications", "Pour voir que le suivi est actif.", p.notifications, true, "Autoriser les notifications", a.requestLocation, askedAtRuntime = true),
    PermissionStep(
        Icons.Default.Sms, "SMS de secours", "En cas de SOS, un SMS avec la position part vers les parents, même sans Internet.",
        p.sms, true, "Autoriser les SMS", a.requestSms, askedAtRuntime = true,
    ),
    PermissionStep(Icons.Default.BatteryChargingFull, "Batterie sans restriction", "Sinon Android coupe le suivi écran éteint.", !p.batteryOptimized, true, "Retirer la restriction batterie", a.openBatterySettings),
    PermissionStep(Icons.Default.Hearing, "Micro (facultatif)", "Pour l'écoute en direct, toujours visible ici.", p.microphone, false, "Autoriser le micro", a.requestMicrophone, askedAtRuntime = true),
    PermissionStep(Icons.Default.Hearing, "Affichage par-dessus (facultatif)", "Ouvre l'écran d'écoute sans toucher le téléphone.", p.overlay, false, "Autoriser l'affichage par-dessus", a.openOverlaySettings),
    PermissionStep(Icons.Default.NotificationsOff, "Ne pas déranger (facultatif)", "« Faire sonner » s'entend même en Ne pas déranger.", p.dndAccess, false, "Autoriser « Ne pas déranger »", a.openDndSettings),
)

@Composable
private fun PermissionStepRow(step: PermissionStep) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tint = when {
            step.done -> AppTheme.colors.success
            step.required -> MaterialTheme.colorScheme.primary
            else -> Muted
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(if (step.done) Icons.Default.CheckCircle else step.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.titleSmall)
            Text(step.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
