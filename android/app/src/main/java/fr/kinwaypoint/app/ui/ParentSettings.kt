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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.OutlinedTextField
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
            // Nom affiché : la partie avant @ de l'e-mail (« camille » → « Camille »), modifiable ensuite.
            val name = config.email.substringBefore('@').replaceFirstChar { it.uppercase() }.ifBlank { "Parent" }
            uniqueId = generateDeviceId()
            ownId = api.createDevice(config.token, name, uniqueId!!)
        }
        api.markAsParent(config.token, ownId)
        api.setVisibleToChildren(config.token, ownId, visible = !ghost)
        runCatching { api.setGhost(config.token, ownId, ghost) }
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
    placesCount: Int = 0,
    /** Ouvre la fenêtre « Ajouter un enfant » (la même que sur l'accueil). */
    onAddChild: () -> Unit = {},
    proximity: fr.kinwaypoint.app.ui.ProximityConfig = fr.kinwaypoint.app.ui.ProximityConfig(),
    onProximityChange: (fr.kinwaypoint.app.ui.ProximityConfig) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownDeviceId by repository.ownDeviceId.collectAsState(initial = 0L)
    val biometricEnabled by repository.biometricEnabled.collectAsState(initial = false)
    val canUseBiometric = remember { actions.biometricAvailable() }

    var ghost by remember { mutableStateOf<Boolean?>(null) } // null = inconnu (chargement)
    var ghostWarning by remember { mutableStateOf<String?>(null) }
    val ghostPref by repository.ghostMode.collectAsState(initial = null)
    var busy by remember { mutableStateOf(false) }
    var colorFor by remember { mutableStateOf<Member?>(null) }
    var customColorFor by remember { mutableStateOf<Member?>(null) }
    var goalFor by remember { mutableStateOf<Member?>(null) }
    var addingParent by remember { mutableStateOf(false) }
    var cadenceFor by remember { mutableStateOf<Member?>(null) }
    var rules by remember { mutableStateOf<Set<String>?>(null) } // null = chargement
    val children = members.filter { !it.isParent }
    var speedLimit by remember(children.map { it.speedLimitKmh }) {
        mutableStateOf((children.firstNotNullOfOrNull { it.speedLimitKmh } ?: 50).toFloat())
    }

    LaunchedEffect(Unit) {
        rules = withContext(Dispatchers.IO) { runCatching { api.alertRules(config.token).keys }.getOrNull() } ?: emptySet()
    }
    val sharing = tracking.enabled && ownDeviceId != 0L

    // Catégorie ouverte (null = liste des catégories) ; retour = revenir à la liste.
    var category by remember { mutableStateOf<String?>(null) }
    val relockMinutes by repository.relockMinutes.collectAsState(initial = 1)
    BackHandler { if (category != null) category = null else onBack() }

    // Mode fantôme : préférence mémorisée sur ce téléphone ; à défaut (ancienne
    // version), lue sur le serveur pendant que le partage est actif.
    LaunchedEffect(ownDeviceId, ghostPref) {
        ghostPref?.let { ghost = it; return@LaunchedEffect }
        if (ownDeviceId == 0L || !tracking.enabled) { ghost = false; return@LaunchedEffect }
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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (category != null) category = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    when (category) {
                        "position" -> "Ma position"
                        "alerts" -> "Alertes"
                        "family" -> "Famille"
                        "security" -> "Sécurité"
                        "account" -> "Compte"
                        "about" -> "À propos"
                        else -> "Paramètres"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }

            // --- Liste des catégories (écran principal) ------------------------------
            if (category == null) {
                SectionCard {
                    CategoryRow(Icons.Default.MyLocation, MaterialTheme.colorScheme.primary, "Ma position",
                        when { !sharing -> "Non partagée"; ghost == true -> "Partagée · mode fantôme"; else -> "Partagée" }) { category = "position" }
                    CategoryRow(Icons.Default.NotificationsActive, Warning, "Alertes",
                        rules?.size?.let { n -> if (n == 0) "Aucune alerte active" else "$n alerte${if (n > 1) "s" else ""} active${if (n > 1) "s" else ""}" } ?: "…") { category = "alerts" }
                    CategoryRow(Icons.Default.Place, Ok, "Lieux",
                        if (placesCount == 0) "Aucun lieu" else "$placesCount lieu${if (placesCount > 1) "x" else ""}", onClick = onOpenPlaces)
                    CategoryRow(Icons.Default.People, androidx.compose.ui.graphics.Color(0xFF8B5CF6), "Famille",
                        "${members.size} membre${if (members.size > 1) "s" else ""} · pas et couleurs") { category = "family" }
                }
                SectionCard {
                    CategoryRow(Icons.Default.Lock, Danger, "Sécurité",
                        (if (biometricEnabled) "Empreinte" else "Code PIN") + " · " +
                            when (relockMinutes) { 0 -> "jamais reverrouillé"; 60 -> "verrouillage 1 h"; else -> "verrouillage $relockMinutes min" }) { category = "security" }
                    CategoryRow(Icons.Default.AccountCircle, MaterialTheme.colorScheme.primary, "Compte", config.email.ifBlank { "Connecté" }) { category = "account" }
                    CategoryRow(Icons.Default.Info, Muted, "À propos", "Version ${fr.kinwaypoint.app.BuildConfig.VERSION_NAME}") { category = "about" }
                }
            }

            if (category == "position") {
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
                            // Partage coupé : plus d'envois, ET les enfants perdent l'accès à la
                            // dernière position (sans passer par le mode fantôme).
                            else -> launchOp {
                                repository.setEnabled(false)
                                TrackingService.stop(context)
                                if (ownDeviceId != 0L) {
                                    withContext(Dispatchers.IO) { api.setVisibleToChildren(config.token, ownDeviceId, visible = false) }
                                }
                                onMembersChanged()
                            }
                        }
                    },
                )
                ToggleRow(
                    icon = Icons.Default.VisibilityOff,
                    title = "Mode fantôme",
                    detail = when {
                        ghost == null && ownDeviceId != 0L -> "Vérification auprès du serveur…"
                        !sharing -> "Partage coupé : personne ne voit ta position. Ce réglage s'appliquera quand tu le réactiveras."
                        else -> "Ni les enfants ni les autres parents ne voient ta position."
                    },
                    checked = ghost == true,
                    enabled = !busy && ghost != null,
                    onChange = { on ->
                        launchOp {
                            repository.setGhostMode(on)
                            // Enfants : appliqué tout de suite seulement si le partage est actif.
                            if (sharing) {
                                withContext(Dispatchers.IO) { api.setVisibleToChildren(config.token, ownDeviceId, visible = !on) }
                            }
                            // Autres parents : masqué dans leur appli, accès et lieux retirés sur le serveur.
                            if (ownDeviceId != 0L) {
                                val result = withContext(Dispatchers.IO) { api.setGhost(config.token, ownDeviceId, on) }
                                ghostWarning = if (on && result.adminsStillSeeing > 0) {
                                    "Masqué dans l'appli des autres parents. Attention : un compte administrateur " +
                                        "garde accès à ta position depuis l'interface web Traccar."
                                } else {
                                    null
                                }
                            }
                            ghost = on
                        }
                    },
                )

            }
            ghostWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warning) }
            if (sharing) {
                StatusCard(status = status, permissions = permissions)
                PermissionWarnings(permissions, actions)
            }

            }

            if (category == "alerts") {
            // --- Alertes -------------------------------------------------------------
            SectionCard {
                Text("Alertes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Envoyées en notification (et sur Telegram) par le serveur, même appli fermée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                fun toggle(rule: fr.kinwaypoint.app.net.AlertRule, on: Boolean) = launchOp {
                    withContext(Dispatchers.IO) { api.setAlertRule(config.token, rule, on) }
                    rules = (rules.orEmpty() - rule.key) + if (on) setOf(rule.key) else emptySet()
                    // L'excès de vitesse a besoin d'une limite sur chaque appareil d'enfant.
                    if (rule == fr.kinwaypoint.app.net.AlertRule.OVERSPEED) {
                        withContext(Dispatchers.IO) {
                            children.forEach { api.setSpeedLimit(config.token, it.id, if (on) speedLimit.toInt() else null) }
                        }
                        onMembersChanged()
                    }
                }
                val loaded = rules != null
                ToggleRow(
                    icon = Icons.Default.CloudOff,
                    title = "Pas de nouvelles",
                    detail = "Un téléphone n'envoie plus rien depuis 1 h : éteint, appli coupée ou désinstallée.",
                    checked = rules?.contains("noNews") == true,
                    enabled = loaded && !busy,
                    onChange = { toggle(fr.kinwaypoint.app.net.AlertRule.NO_NEWS, it) },
                )
                ToggleRow(
                    icon = Icons.Default.BatteryAlert,
                    title = "Batterie faible",
                    detail = "Sous 15 %, une seule fois par recharge.",
                    checked = rules?.contains("lowBattery") == true,
                    enabled = loaded && !busy,
                    onChange = { toggle(fr.kinwaypoint.app.net.AlertRule.LOW_BATTERY, it) },
                )
                ToggleRow(
                    icon = Icons.Default.CheckCircle,
                    title = "« Je suis bien arrivé·e »",
                    detail = "L'enfant te prévient d'un geste depuis son téléphone.",
                    checked = rules?.contains("checkIn") == true,
                    enabled = loaded && !busy,
                    onChange = { toggle(fr.kinwaypoint.app.net.AlertRule.CHECK_IN, it) },
                )
                ToggleRow(
                    icon = Icons.Default.Speed,
                    title = "Excès de vitesse",
                    detail = "Au-delà de ${speedLimit.toInt()} km/h (scooter, voiture d'un tiers…).",
                    checked = rules?.contains("overspeed") == true,
                    enabled = loaded && !busy,
                    onChange = { toggle(fr.kinwaypoint.app.net.AlertRule.OVERSPEED, it) },
                )
                if (rules?.contains("overspeed") == true) {
                    androidx.compose.material3.Slider(
                        value = speedLimit,
                        onValueChange = { speedLimit = (Math.round(it / 10f) * 10f) },
                        onValueChangeFinished = {
                            launchOp {
                                withContext(Dispatchers.IO) { children.forEach { api.setSpeedLimit(config.token, it.id, speedLimit.toInt()) } }
                                onMembersChanged()
                            }
                        },
                        valueRange = 20f..130f,
                    )
                }
            }

            // --- Alerte de proximité -------------------------------------------------
            // Calculée par le serveur (Traccar) : chaque enfant est lié au téléphone
            // du parent ; la notification arrive même appli fermée.
            var radius by remember(proximity.radiusMeters) { mutableStateOf(proximity.radiusMeters.toFloat()) }
            val proximityChildren = children.filter { it.id != ownDeviceId }.map { it.id }
            fun applyProximity(enabled: Boolean, meters: Int) = launchOp {
                withContext(Dispatchers.IO) {
                    api.setAlertRule(config.token, fr.kinwaypoint.app.net.AlertRule.PROXIMITY, enabled)
                    api.setProximity(config.token, proximityChildren, ownDeviceId, if (enabled) meters else 0)
                }
                onProximityChange(proximity.copy(enabled = enabled, radiusMeters = meters))
            }
            SectionCard {
                ToggleRow(
                    icon = Icons.Default.MyLocation,
                    title = "Alerte de proximité",
                    detail = if (sharing) {
                        "Prévenir quand un enfant revient près de toi après s'être éloigné."
                    } else {
                        "Active d'abord « Partager ma position » : le serveur compare la position de ton téléphone à celle des enfants."
                    },
                    checked = proximity.enabled,
                    enabled = !busy && (sharing || proximity.enabled),
                    onChange = { on -> applyProximity(on, proximity.radiusMeters) },
                )
                if (proximity.enabled) {
                    Text(
                        "Distance de déclenchement : ${radius.toInt()} m",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.material3.Slider(
                        value = radius,
                        onValueChange = { radius = (Math.round(it / 50f) * 50).toFloat() },
                        onValueChangeFinished = { applyProximity(true, radius.toInt()) },
                        valueRange = 100f..1000f,
                        enabled = !busy,
                    )
                    Text(
                        "Calculé par le serveur : la notification arrive même appli fermée et téléphone en veille. " +
                            "Elle part quand un enfant passe de plus loin à plus près que cette distance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }

            }

            if (category == "family") {
            Button(onClick = onAddChild, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Text("  Ajouter un enfant")
            }
            OutlinedButton(onClick = { addingParent = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Text("  Ajouter un parent")
            }
            // --- Ordre d'affichage (glisser-déposer) -------------------------------------
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("↕️", style = MaterialTheme.typography.titleMedium)
                    Text("Ordre d'affichage", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Maintiens un membre puis fais-le glisser. L'ordre vaut pour toute la famille : liste, carte, historique.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                ReorderableMembers(members, photos) { reordered ->
                    launchOp {
                        withContext(Dispatchers.IO) {
                            reordered.forEachIndexed { index, m -> if (m.order != index) api.setOrder(config.token, m.id, index) }
                        }
                        onMembersChanged()
                    }
                }
            }

            // --- Fréquence de suivi ------------------------------------------------------
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("⏱️", style = MaterialTheme.typography.titleMedium)
                    Text("Fréquence de suivi", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Toutes les combien le téléphone envoie sa position : en temps normal, en déplacement, à l'arrêt. " +
                        "Plus c'est fréquent, plus la batterie se vide vite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                members.forEach { m ->
                    val c = m.cadence ?: fr.kinwaypoint.app.net.Cadence.BALANCED
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { cadenceFor = m }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.name, modifier = Modifier.weight(1f))
                        Text(
                            (c.presetName() ?: "Personnalisé") + " · " + cadenceLabel(c),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // --- Objectif de pas -----------------------------------------------------
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("👟", style = MaterialTheme.typography.titleMedium)
                    Text("Objectif de pas quotidien", style = MaterialTheme.typography.titleMedium)
                }
                Text("Affiché en jauge sur la carte, dans la liste et sur le téléphone de l'enfant.", style = MaterialTheme.typography.bodySmall, color = Muted)
                members.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { goalFor = m }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(m.name, size = 36.dp, photo = photos[m.id], colorHex = m.color)
                        Text(if (m.id == ownDeviceId) "${m.name} (moi)" else m.name, modifier = Modifier.weight(1f))
                        Text("${formatSteps(m.stepGoal)} pas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
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

            }

            if (category == "security") {
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
                // Délai avant de redemander le code ou l'empreinte.
                val relock by repository.relockMinutes.collectAsState(initial = 1)
                Text("Verrouillage automatique", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (relock == 0) {
                        "L'espace parent reste ouvert : le verrouillage de ton téléphone suffit. « Verrouiller maintenant » reste possible."
                    } else {
                        "Code ou empreinte redemandés après cette durée hors de l'appli (même après un redémarrage)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 60 to "1 h", 0 to "Jamais").forEach { (minutes, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = relock == minutes,
                            onClick = { scope.launch { repository.setRelockMinutes(minutes) } },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text("Changer le code PIN") }
                OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Verrouiller maintenant")
                }
            }

            }

            if (category == "account") {
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

            // --- À propos ---------------------------------------------------------------
            if (category == "about") {
                var updateCheck by remember { mutableStateOf(0) }
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
                    OutlinedButton(
                        onClick = {
                            launchOp {
                                val found = checkForUpdate(context)
                                if (found == null) {
                                    Toast.makeText(context, "KinWaypoint est à jour.", Toast.LENGTH_SHORT).show()
                                } else {
                                    updateCheck++
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                    ) { Text("Rechercher une mise à jour") }
                }
                // Bandeau de mise à jour (téléchargement et installation), relancé après une recherche.
                androidx.compose.runtime.key(updateCheck) { UpdateBanner() }
            }

        }
    }

    cadenceFor?.let { member ->
        CadenceDialog(
            member = member,
            onDismiss = { cadenceFor = null },
            onSave = { cadence ->
                cadenceFor = null
                launchOp {
                    withContext(Dispatchers.IO) {
                        api.setCadence(config.token, member.id, cadence)
                        // Réveil du téléphone : il lit ses nouvelles cadences tout de suite.
                        api.locateNow(config.token, member.id)
                    }
                    onMembersChanged()
                }
            },
        )
    }

    if (addingParent) {
        AddParentDialog(api = api, config = config, members = members, onDismiss = { addingParent = false })
    }

    goalFor?.let { member ->
        var goal by remember(member.id) { mutableStateOf(member.stepGoal.toFloat()) }
        AlertDialog(
            onDismissRequest = { goalFor = null },
            title = { Text("Objectif de ${member.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${formatSteps(goal.toInt())} pas par jour", style = MaterialTheme.typography.headlineSmall)
                    androidx.compose.material3.Slider(
                        value = goal,
                        onValueChange = { goal = (Math.round(it / 500f) * 500f) },
                        valueRange = 2000f..20000f,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4000, 6000, 8000, 10000, 12000).forEach { preset ->
                            androidx.compose.material3.FilterChip(
                                selected = goal.toInt() == preset,
                                onClick = { goal = preset.toFloat() },
                                label = { Text(formatSteps(preset)) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    goalFor = null
                    launchOp {
                        withContext(Dispatchers.IO) { api.setStepGoal(config.token, member.id, goal.toInt()) }
                        onMembersChanged()
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { goalFor = null }) { Text("Annuler") } },
        )
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
                    // Couleur personnalisée : ouvre le sélecteur complet.
                    val custom = parseHex(member.color)?.takeIf { member.color?.uppercase() !in ProfilePalette }
                    CustomColorSwatch(custom = custom, size = 44.dp) {
                        colorFor = null
                        customColorFor = member
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorFor = null }) { Text("Fermer") } },
        )
    }
    customColorFor?.let { member ->
        ColorPickerDialog(
            initial = profileColor(member.name, member.color),
            title = "Couleur de ${member.name}",
            onDismiss = { customColorFor = null },
            onPick = { hex ->
                customColorFor = null
                launchOp {
                    withContext(Dispatchers.IO) { api.setColor(config.token, member.id, hex) }
                    onMembersChanged()
                }
            },
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

/** Ligne de catégorie (façon réglages Android) : icône teintée, titre, résumé, chevron. */
@Composable
private fun CategoryRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, summary: String, onClick: () -> Unit) {
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

/**
 * Ajout d'un autre parent, à distance : l'appli crée son compte (non
 * administrateur, adresse et mot de passe cachés que personne n'a besoin de
 * connaître), lui partage la famille, obtient sa clé de connexion (1 an) et la
 * dépose sur le serveur familial derrière un code d'invitation de 8 chiffres
 * (1 h, usage unique).
 */
@Composable
private fun AddParentDialog(api: TraccarApi, config: ParentConfig, members: List<Member>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var invite by remember { mutableStateOf<fr.kinwaypoint.app.net.RemoteConfig.Invite?>(null) }

    val current = invite
    if (current != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Invitation de $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Son compte est créé et voit déjà toute la famille.", style = MaterialTheme.typography.bodyMedium)
                    InviteCodeCard(current, name)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Ajouter un parent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Tu obtiens un code de 8 chiffres, valable 1 h, à lui transmettre. Sur son téléphone, il installe " +
                        "KinWaypoint puis touche « J'ai un code d'invitation ». Son compte voit toute la famille ; il n'est " +
                        "pas administrateur, ton mode fantôme peut donc te cacher de lui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); error = null },
                    label = { Text("Prénom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Danger) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                // Adresse interne : le parent ne s'en sert jamais (connexion par la clé).
                                val slug = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
                                    .replace(Regex("[^a-z0-9]"), "").take(20).ifBlank { "parent" }
                                val suffix = java.util.UUID.randomUUID().toString().take(6)
                                val account = api.createParentAccount(config.token, name, "$slug.$suffix@kinwaypoint.local")
                                runCatching {
                                    api.shareFamilyWithParents(config.token, members.filter { !it.ghost }.map { it.id }, api.places(config.token).map { it.id })
                                }
                                val parentKey = api.login(account.email, account.password)
                                val apiUrl = TraccarApi.normalize(config.apiUrl).toString().trimEnd('/')
                                val invitation = org.json.JSONObject()
                                    .put("mode", "parent")
                                    .put("label", "Compte de ${name.trim()}")
                                    .put(
                                        "parent",
                                        org.json.JSONObject()
                                            .put("apiUrl", apiUrl)
                                            .put("trackUrl", config.trackUrl.ifBlank { guessTrackUrl(apiUrl) })
                                            .put("email", account.email)
                                            .put("token", parentKey),
                                    )
                                fr.kinwaypoint.app.net.RemoteConfig.createInvite(FAMILY_HUB, config.token, invitation)
                            }
                        }.onSuccess { invite = it }
                            .onFailure { error = it.message ?: "Invitation impossible" }
                        busy = false
                    }
                },
            ) { Text("Créer l'invitation") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") } },
    )
}

/**
 * Liste de membres réordonnable : appui long sur une ligne, puis glisser vers le
 * haut ou le bas ; la ligne échange sa place avec sa voisine à mi-hauteur.
 * onReordered est appelé au lâcher, avec le nouvel ordre (s'il a changé).
 */
@Composable
private fun ReorderableMembers(members: List<Member>, photos: Map<Long, Bitmap>, onReordered: (List<Member>) -> Unit) {
    var items by remember(members.map { it.id to it.order }) { mutableStateOf(members) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column {
        items.forEach { m ->
            androidx.compose.runtime.key(m.id) {
                val dragging = m.id == draggingId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { if (rowHeight == 0f) rowHeight = it.height.toFloat() }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 8.dp.toPx() else 0f
                            shape = RoundedCornerShape(14.dp)
                            clip = dragging
                        }
                        .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                        .pointerInput(m.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = m.id
                                    dragOffset = 0f
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val h = rowHeight
                                    val index = items.indexOfFirst { it.id == m.id }
                                    if (h > 0f && index >= 0) {
                                        if (dragOffset > h / 2 && index < items.lastIndex) {
                                            items = items.toMutableList().apply { add(index + 1, removeAt(index)) }
                                            dragOffset -= h
                                        } else if (dragOffset < -h / 2 && index > 0) {
                                            items = items.toMutableList().apply { add(index - 1, removeAt(index)) }
                                            dragOffset += h
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragOffset = 0f
                                    if (items.map { it.id } != members.map { it.id }) onReordered(items)
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box {
                        Avatar(m.name, size = 38.dp, photo = photos[m.id], colorHex = m.color)
                        if (m.isParent) ParentShield(Modifier.align(Alignment.TopEnd), size = 15.dp)
                    }
                    Text(m.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.DragHandle, contentDescription = "Déplacer ${m.name}", tint = Muted)
                }
            }
        }
    }
}

/** « 5 min · 15 s · 10 min » (normal · en mouvement · à l'arrêt). */
private fun cadenceLabel(c: fr.kinwaypoint.app.net.Cadence): String {
    fun d(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"
    return "${d(c.normal)} · ${d(c.moving)} · ${d(c.still)}"
}

/** Réglage des cadences d'un membre : trois préréglages ou des valeurs sur mesure. */
@Composable
private fun CadenceDialog(member: Member, onDismiss: () -> Unit, onSave: (fr.kinwaypoint.app.net.Cadence) -> Unit) {
    val start = member.cadence ?: fr.kinwaypoint.app.net.Cadence.BALANCED
    var normalMin by remember { mutableFloatStateOf((start.normal / 60f).coerceIn(1f, 30f)) }
    var movingSec by remember { mutableFloatStateOf(start.moving.toFloat().coerceIn(5f, 120f)) }
    var stillMin by remember { mutableFloatStateOf((start.still / 60f).coerceIn(5f, 60f)) }
    val current = fr.kinwaypoint.app.net.Cadence(normalMin.toInt() * 60, movingSec.toInt(), stillMin.toInt() * 60)
    fun apply(c: fr.kinwaypoint.app.net.Cadence) {
        normalMin = c.normal / 60f; movingSec = c.moving.toFloat(); stillMin = c.still / 60f
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fréquence de ${member.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Économe" to fr.kinwaypoint.app.net.Cadence.ECO,
                        "Équilibré" to fr.kinwaypoint.app.net.Cadence.BALANCED,
                        "Précis" to fr.kinwaypoint.app.net.Cadence.PRECISE,
                    ).forEach { (label, preset) ->
                        androidx.compose.material3.FilterChip(selected = current == preset, onClick = { apply(preset) }, label = { Text(label) })
                    }
                }
                Text("En temps normal : toutes les ${normalMin.toInt()} min", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = normalMin, onValueChange = { normalMin = it.toInt().toFloat() }, valueRange = 1f..30f)
                Text("En déplacement : toutes les ${movingSec.toInt()} s", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = movingSec, onValueChange = { movingSec = (Math.round(it / 5f) * 5f).coerceAtLeast(5f) }, valueRange = 5f..120f)
                Text("À l'arrêt : toutes les ${stillMin.toInt()} min", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = stillMin, onValueChange = { stillMin = it.toInt().toFloat() }, valueRange = 5f..60f)
                Text(
                    "Appliqué dès que le téléphone se réveille (quelques secondes, ou à son prochain envoi). " +
                        "Il faut que son appli soit à jour et configurée avec l'accès famille.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
