package fr.kinwaypoint.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.data.ParentConfig
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TraccarApi
import fr.kinwaypoint.app.service.TrackingService
import fr.kinwaypoint.app.service.TrackingStatusState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Active le partage de position de CE téléphone parent : crée son appareil
 * Traccar au besoin, le rend visible des enfants (sauf mode fantôme) et démarre
 * le même service de suivi que sur un téléphone enfant.
 */
suspend fun enableParentSharing(
    context: Context,
    repository: SettingsRepository,
    config: ParentConfig,
    api: TraccarApi,
    members: List<Member>,
    ghost: Boolean,
) {
    val trackUrl = config.trackUrl.ifBlank { guessTrackUrl(config.apiUrl) }
    var ownId = repository.ownDeviceId.first()
    var uniqueId = members.firstOrNull { it.id == ownId }?.uniqueId
    withContext(Dispatchers.IO) {
        if (ownId == 0L || uniqueId == null) {
            // Nom affiché : la partie avant @ de l'e-mail (« guillaume » → « Guillaume »), modifiable ensuite.
            val name = config.email.substringBefore('@').replaceFirstChar { it.uppercase() }.ifBlank { "Parent" }
            uniqueId = generateDeviceId()
            ownId = api.createDevice(config.token, name, uniqueId!!)
        }
        api.markAsParent(config.token, ownId)
        api.setVisibleToChildren(config.token, ownId, visible = !ghost)
    }
    repository.setOwnDeviceId(ownId)
    val current = repository.config.first()
    repository.save(current.copy(serverUrl = trackUrl, deviceId = uniqueId!!, enabled = true))
    TrackingService.stop(context)
    TrackingService.start(context)
}

/** Paramètres de l'espace parent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParentSettingsScreen(
    repository: SettingsRepository,
    config: ParentConfig,
    api: TraccarApi,
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    tracking: TrackingConfig,
    status: TrackingStatusState,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onBack: () -> Unit,
    onMembersChanged: () -> Unit,
    onLock: () -> Unit,
    onChangePin: () -> Unit,
    onChangeMode: () -> Unit,
    onOpenPlaces: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownDeviceId by repository.ownDeviceId.collectAsState(initial = 0L)
    val biometricEnabled by repository.biometricEnabled.collectAsState(initial = false)
    val canUseBiometric = remember { actions.biometricAvailable() }

    var ghost by remember { mutableStateOf<Boolean?>(null) } // null = inconnu (chargement)
    var busy by remember { mutableStateOf(false) }
    var colorFor by remember { mutableStateOf<Member?>(null) }
    val sharing = tracking.enabled && ownDeviceId != 0L

    BackHandler(onBack = onBack)

    // État fantôme : lu sur le serveur (c'est lui qui l'applique).
    LaunchedEffect(ownDeviceId) {
        if (ownDeviceId == 0L) { ghost = false; return@LaunchedEffect }
        ghost = withContext(Dispatchers.IO) {
            runCatching { !api.isVisibleToChildren(config.token, ownDeviceId) }.getOrNull()
        }
    }

    fun launchOp(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onFailure {
                Toast.makeText(context, it.message ?: "Opération impossible", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                Text("Paramètres", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }

            // --- Ma position --------------------------------------------------------
            SectionCard {
                Text("Ma position", style = MaterialTheme.typography.titleMedium)
                ToggleRow(
                    icon = Icons.Default.MyLocation,
                    title = "Partager ma position",
                    detail = "Ce téléphone est suivi comme celui d'un enfant : tu apparais sur la carte et dans l'historique.",
                    checked = sharing,
                    enabled = !busy,
                    onChange = { on ->
                        when {
                            on && !permissions.fineLocation -> actions.requestLocation()
                            on -> launchOp { enableParentSharing(context, repository, config, api, members, ghost == true) ; onMembersChanged() }
                            else -> launchOp {
                                repository.setEnabled(false)
                                TrackingService.stop(context)
                            }
                        }
                    },
                )
                ToggleRow(
                    icon = Icons.Default.VisibilityOff,
                    title = "Mode fantôme",
                    detail = if (ghost == null && ownDeviceId != 0L) {
                        "Vérification auprès du serveur…"
                    } else {
                        "Les enfants ne voient pas ta position. Les autres parents la voient toujours."
                    },
                    checked = ghost == true,
                    enabled = !busy && ownDeviceId != 0L && ghost != null,
                    onChange = { on ->
                        launchOp {
                            withContext(Dispatchers.IO) { api.setVisibleToChildren(config.token, ownDeviceId, visible = !on) }
                            ghost = on
                        }
                    },
                )
                if (ownDeviceId == 0L) {
                    Text("Active le partage une première fois pour pouvoir passer en mode fantôme.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            if (sharing) {
                StatusCard(status = status, permissions = permissions)
                PermissionWarnings(permissions, actions)
            }

            // --- Lieux ---------------------------------------------------------------
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(Icons.Default.Place, MaterialTheme.colorScheme.primary, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Lieux", style = MaterialTheme.typography.titleSmall)
                        Text("Maison, école… : alerte quand un enfant arrive ou part.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                OutlinedButton(onClick = onOpenPlaces, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text("Gérer les lieux") }
            }

            // --- Couleurs -----------------------------------------------------------
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Couleurs des profils", style = MaterialTheme.typography.titleMedium)
                }
                Text("Chaque profil a sa couleur : avatar, tracé sur la carte et historique.", style = MaterialTheme.typography.bodySmall, color = Muted)
                members.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { colorFor = m }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(m.name, size = 36.dp, photo = photos[m.id], colorHex = m.color)
                        Text(if (m.id == ownDeviceId) "${m.name} (moi)" else m.name, modifier = Modifier.weight(1f))
                        Box(Modifier.size(28.dp).clip(CircleShape).background(profileColor(m.name, m.color)))
                    }
                }
            }

            // --- Sécurité -----------------------------------------------------------
            SectionCard {
                Text("Sécurité", style = MaterialTheme.typography.titleMedium)
                ToggleRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Déverrouiller avec l'empreinte",
                    detail = if (canUseBiometric) "Le code PIN reste demandé pour changer de mode ou de code." else "Aucune empreinte enregistrée sur ce téléphone.",
                    checked = biometricEnabled,
                    enabled = canUseBiometric || biometricEnabled,
                    onChange = { on ->
                        if (on) actions.authenticate { scope.launch { repository.setBiometric(true) } }
                        else scope.launch { repository.setBiometric(false) }
                    },
                )
                OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text("Changer le code PIN") }
                OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Verrouiller maintenant")
                }
            }

            // --- Compte -------------------------------------------------------------
            SectionCard {
                Text("Compte", style = MaterialTheme.typography.titleMedium)
                Text("Connecté : ${config.email}", style = MaterialTheme.typography.bodyMedium)
                Text(config.apiUrl, style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(
                    onClick = { scope.launch { repository.saveParent(config.copy(token = "")) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                ) { Text("Se déconnecter du serveur") }
                OutlinedButton(onClick = onChangeMode, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Text("Changer le mode de l'appareil")
                }
            }
        }
    }

    colorFor?.let { member ->
        AlertDialog(
            onDismissRequest = { colorFor = null },
            title = { Text("Couleur de ${member.name}") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfilePalette.forEach { hex ->
                        val c = parseHex(hex)!!
                        val selected = profileColor(member.name, member.color) == c
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(3.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .clickable {
                                    colorFor = null
                                    launchOp {
                                        withContext(Dispatchers.IO) { api.setColor(config.token, member.id, hex) }
                                        onMembersChanged()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorFor = null }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
