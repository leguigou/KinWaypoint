package fr.kinwaypoint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fr.kinwaypoint.app.data.ParentConfig
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.net.Alarm
import fr.kinwaypoint.app.net.AuthException
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REFRESH_MS = 30_000L

/** Connexion au serveur Traccar avec le compte parent (une seule fois). */
@Composable
fun ParentLoginScreen(
    repository: SettingsRepository,
    current: ParentConfig,
    actions: PlatformActions,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiUrl by remember(current.apiUrl) { mutableStateOf(current.apiUrl) }
    var trackUrl by remember(current.trackUrl) { mutableStateOf(current.trackUrl) }
    var email by remember(current.email) { mutableStateOf(current.email) }
    var showConfigCode by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandMark(size = 56.dp)
            Text("Espace parent", style = MaterialTheme.typography.headlineMedium)
            FilledTonalButton(onClick = { showConfigCode = true }, shape = CircleShape) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("J'ai un code de configuration")
            }
            Text(
                "Connecte-toi avec ton compte du serveur familial. Le mot de passe n'est pas conservé : " +
                    "le téléphone garde seulement un jeton d'accès valable un an.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("Adresse du serveur (interface)") },
                placeholder = { Text("https://api.exemple.fr") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = trackUrl,
                onValueChange = { trackUrl = it },
                label = { Text("Adresse d'envoi des positions (facultatif)") },
                placeholder = { Text(guessTrackUrl(apiUrl).ifBlank { "https://track.exemple.fr" }) },
                supportingText = { Text("Utilisée dans les QR codes de configuration des enfants") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            error?.let { Text(it, color = Danger, fontSize = 14.sp) }

            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val api = TraccarApi(apiUrl)
                                api.login(email, password)
                            }
                        }
                        result.onSuccess { token ->
                            password = ""
                            val normalized = TraccarApi.normalize(apiUrl).toString()
                            repository.saveParent(
                                current.copy(
                                    apiUrl = normalized,
                                    trackUrl = trackUrl.trim().ifBlank { guessTrackUrl(normalized) },
                                    email = email,
                                    token = token,
                                ),
                            )
                        }.onFailure {
                            error = it.message ?: "Connexion impossible"
                        }
                        busy = false
                    }
                },
                enabled = !busy && apiUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp), color = Color.White) else Text("Se connecter")
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Verrouiller")
            }
        }
    }

    if (showConfigCode) {
        ParentConfigCode(
            actions = actions,
            onDismiss = { showConfigCode = false },
            onFilled = { a, t, e ->
                showConfigCode = false
                apiUrl = a
                t?.let { trackUrl = it }
                e?.let { email = it }
            },
        )
    }
}

/** Champs de connexion pré-remplis par un code de configuration (le mot de passe reste à saisir). */
@Composable
private fun ParentConfigCode(actions: PlatformActions, onFilled: (apiUrl: String, trackUrl: String?, email: String?) -> Unit, onDismiss: () -> Unit) {
    ConfigCodeDialog(
        actions = actions,
        expected = fr.kinwaypoint.app.data.AppMode.PARENT,
        onDismiss = onDismiss,
        onResult = { cfg -> cfg.parent?.let { onFilled(it.apiUrl, it.trackUrl, it.email) } },
    )
}

/** Tableau de bord parent : chaque membre, sa dernière position, sa batterie, les SOS. */
@Composable
fun ParentHomeScreen(
    repository: SettingsRepository,
    config: ParentConfig,
    acknowledgedAlarmId: Long,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onLock: () -> Unit,
    onChangePin: () -> Unit,
    onChangeMode: () -> Unit,
    tracking: fr.kinwaypoint.app.data.TrackingConfig = fr.kinwaypoint.app.data.TrackingConfig(),
    status: fr.kinwaypoint.app.service.TrackingStatusState = fr.kinwaypoint.app.service.TrackingStatusState(),
) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val api = remember(config.apiUrl) { TraccarApi(config.apiUrl) }

    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var alarms by remember { mutableStateOf<List<Alarm>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshedAt by remember { mutableLongStateOf(0L) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyFocus by remember { mutableStateOf<Long?>(null) }
    var todayTraces by remember { mutableStateOf<Map<Long, List<fr.kinwaypoint.app.net.TracePoint>>>(emptyMap()) }
    val ownDeviceId by repository.ownDeviceId.collectAsState(initial = 0L)
    val trackingContext = LocalContext.current

    // « Partager ma position » : si Android a arrêté le suivi, on le relance.
    LaunchedEffect(tracking, permissions.fineLocation, status.running) {
        if (tracking.enabled && tracking.isValid && permissions.fineLocation && !status.running) {
            fr.kinwaypoint.app.service.TrackingService.start(trackingContext)
        }
    }
    val biometricEnabled by repository.biometricEnabled.collectAsState(initial = false)
    val biometricOfferDismissed by repository.biometricOfferDismissed.collectAsState(initial = true)
    val canUseBiometric = remember { actions.biometricAvailable() }
    var showAddChild by remember { mutableStateOf(false) }
    var qrFor by remember { mutableStateOf<Pair<String, SetupPayload>?>(null) }
    val trackUrl = config.trackUrl.ifBlank { guessTrackUrl(config.apiUrl) }
    var mapOpen by remember { mutableStateOf(false) }
    var mapFocus by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Member?>(null) }
    var deleting by remember { mutableStateOf<Member?>(null) }

    fun openMap(focus: Long?) {
        mapFocus = focus
        mapOpen = true
    }

    /** Opération serveur sur un membre, puis actualisation de la liste. */
    fun memberOperation(block: suspend () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { refreshTick++ }
                .onFailure { error = it.message ?: "Opération impossible" }
        }
    }

    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val list = api.members(config.token)
                list to api.recentAlarms(config.token, list.map { it.id })
            }
        }
        result.onSuccess { (list, recent) ->
            members = list
            alarms = recent
            error = null
            refreshedAt = System.currentTimeMillis()
        }.onFailure {
            if (it is AuthException) {
                // Jeton expiré ou révoqué : retour à l'écran de connexion.
                repository.saveParent(config.copy(token = ""))
            }
            error = it.message ?: "Serveur injoignable"
        }
        loading = false
    }

    // Actualisation toutes les 30 s, uniquement quand l'écran est visible.
    LaunchedEffect(api, refreshTick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    val photos = rememberMemberPhotos(api, config.token, members)
    val context = LocalContext.current

    /** Choix d'une photo dans la galerie, puis envoi au serveur (visible par tous les parents). */
    fun changePhoto(member: Member) {
        actions.pickImage { uri ->
            if (uri != null) {
                memberOperation { api.uploadPhoto(config.token, member.id, prepareAvatarJpeg(context, uri)) }
            }
        }
    }

    // Les enfants se voient entre eux : appareils d'enfants liés au compte lecteur
    // (une fois par ouverture, sans effet si c'est déjà fait).
    LaunchedEffect(members.filter { !it.isParent }.map { it.id }) {
        if (members.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { api.shareChildrenWithEachOther(config.token, members) } }
    }

    // Tracés du jour, rechargés chaque minute tant que la carte est ouverte.
    LaunchedEffect(mapOpen, members.map { it.id }) {
        if (!mapOpen) return@LaunchedEffect
        while (true) {
            val zone = java.time.ZoneId.systemDefault()
            val from = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { members.associate { m -> m.id to api.positions(config.token, m.id, from, now) } }
            }.onSuccess { todayTraces = it }
            delay(60_000)
        }
    }

    val names = members.associate { it.id to it.name }
    val newAlarms = alarms.filter { it.id > acknowledgedAlarmId }

    if (settingsOpen) {
        ParentSettingsScreen(
            repository = repository,
            config = config,
            api = api,
            members = members,
            photos = photos,
            tracking = tracking,
            status = status,
            permissions = permissions,
            actions = actions,
            onBack = { settingsOpen = false },
            onMembersChanged = { refreshTick++ },
            onLock = onLock,
            onChangePin = onChangePin,
            onChangeMode = onChangeMode,
        )
    } else if (historyOpen) {
        HistoryScreen(
            api = api,
            token = config.token,
            members = members,
            photos = photos,
            ownDeviceId = ownDeviceId,
            initialFocus = historyFocus,
            onBack = { historyOpen = false },
        )
    } else if (mapOpen) {
        FamilyMapScreen(
            traces = todayTraces,
            ownDeviceId = ownDeviceId,
            onOpenHistory = { historyFocus = null; historyOpen = true },
            members = members,
            photos = photos,
            focusId = mapFocus,
            permissions = permissions,
            actions = actions,
            onBack = { mapOpen = false },
        )
    } else Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = Muted)
                        Text("Ma famille", style = MaterialTheme.typography.headlineMedium)
                    }
                    FilledTonalIconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                    FilledTonalIconButton(onClick = { historyFocus = null; historyOpen = true }) {
                        Icon(Icons.Default.History, contentDescription = "Historique")
                    }
                    FilledTonalIconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            }

            // Grande carte « Carte de la famille » : point d'entrée principal.
            item {
                val online = members.count { it.online }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, MaterialTheme.shapes.large, ambientColor = AppTheme.colors.heroGradient.first(), spotColor = AppTheme.colors.heroGradient.first())
                        .clip(MaterialTheme.shapes.large)
                        .background(Brush.linearGradient(AppTheme.colors.heroGradient))
                        .clickable { openMap(null) }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Voir la carte", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(
                            when {
                                loading -> "Chargement…"
                                members.isEmpty() -> "Aucun membre pour l'instant"
                                else -> "$online sur ${members.size} en ligne · actualisé à ${formatTime(refreshedAt)}"
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Map, contentDescription = null, tint = Color.White) }
                }
            }

            if (newAlarms.isNotEmpty()) {
                items(newAlarms, key = { "alarm-${it.id}" }) { alarm ->
                    val member = members.firstOrNull { it.id == alarm.deviceId }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(12.dp, MaterialTheme.shapes.large, ambientColor = Danger, spotColor = Danger)
                            .clip(MaterialTheme.shapes.large)
                            .background(Brush.linearGradient(AppTheme.colors.alarmGradient))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White) }
                            Column {
                                Text(
                                    "SOS — ${names[alarm.deviceId] ?: "Appareil ${alarm.deviceId}"}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text("Déclenché le ${formatDateTime(alarm.time)}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        run {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                member?.position?.let { p ->
                                    Button(
                                        onClick = { openMap(member.id) },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Danger),
                                    ) { Text("Voir sur la carte") }
                                }
                                TextButton(
                                    onClick = { scope.launch { repository.acknowledgeAlarm(alarm.id) } },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                ) { Text("J'ai vu") }
                            }
                        }
                    }
                }
            }

            if (canUseBiometric && !biometricEnabled && !biometricOfferDismissed) {
                item {
                    SectionCard(container = SoftGreen) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(Icons.Default.Fingerprint, MaterialTheme.colorScheme.primary, size = 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Déverrouiller avec ton empreinte ?", style = MaterialTheme.typography.titleSmall)
                                Text("Le code PIN reste demandé pour changer de mode ou de code.", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                        run {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(shape = CircleShape, onClick = {
                                    // Une empreinte reconnue confirme que le doigt est bien enregistré avant d'activer.
                                    actions.authenticate { scope.launch { repository.setBiometric(true) } }
                                }) { Text("Activer") }
                                TextButton(onClick = { scope.launch { repository.setBiometric(false) } }) { Text("Non merci") }
                            }
                        }
                    }
                }
            }

            error?.let { message ->
                item { InfoCard(title = "Actualisation impossible", detail = message, container = SoftOrange, icon = Icons.Default.CloudOff, tint = Warning) }
            }

            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (members.isEmpty() && error == null) {
                item {
                    InfoCard(
                        title = "Aucun appareil",
                        detail = "Utilise « Ajouter un enfant » ci-dessous, puis scanne le QR code avec son téléphone en mode enfant.",
                        container = SoftGreen,
                        icon = Icons.Default.PersonAdd,
                    )
                }
            }

            if (members.isNotEmpty()) {
                item { Text("Membres", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            }

            items(members, key = { it.id }) { member ->
                MemberCard(
                    member = member,
                    onShowMap = { openMap(member.id) },
                    // QR complet : envoi des positions + accès « Voir mes parents ».
                    onShowQr = { scope.launch { qrFor = member.name to childSetupPayload(repository, config, trackUrl, member.uniqueId) } },
                    onRename = { renaming = member },
                    onShowHistory = { historyFocus = member.id; historyOpen = true },
                    isMe = member.id == ownDeviceId,
                    photo = photos[member.id],
                    onChangePhoto = { changePhoto(member) },
                    onRemovePhoto = if (member.photo != null) {
                        { memberOperation { api.removePhoto(config.token, member.id) } }
                    } else {
                        null
                    },
                    onDelete = { deleting = member },
                )
            }

            item {
                OutlinedButton(
                    onClick = { showAddChild = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Text("Ajouter un enfant")
                }
            }

        }
    }

    if (showAddChild) {
        AddChildDialog(
            repository = repository,
            config = config,
            onCreated = { name, payload ->
                showAddChild = false
                qrFor = name to payload
                refreshTick++
            },
            onDismiss = { showAddChild = false },
        )
    }

    qrFor?.let { (name, payload) -> SetupQrDialog(name, payload, onDismiss = { qrFor = null }) }

    renaming?.let { member ->
        var name by remember(member.id) { mutableStateOf(member.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Prénom affiché") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != member.name,
                    onClick = {
                        renaming = null
                        val newName = name.trim()
                        memberOperation { api.renameDevice(config.token, member.id, newName) }
                    },
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } },
        )
    }

    deleting?.let { member ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer ${member.name} ?") },
            text = {
                Text(
                    "L'appareil et tout son historique de positions seront effacés du serveur. " +
                        "Son téléphone ne pourra plus envoyer de position tant qu'il n'est pas reconfiguré " +
                        "avec un nouveau QR code. Cette action est définitive.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    memberOperation { api.deleteDevice(config.token, member.id) }
                }) { Text("Supprimer", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }

}

@Composable
private fun MemberCard(
    member: Member,
    onShowMap: () -> Unit,
    onShowQr: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    photo: android.graphics.Bitmap? = null,
    onShowHistory: () -> Unit = {},
    isMe: Boolean = false,
    onChangePhoto: () -> Unit = {},
    onRemovePhoto: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val position = member.position
    val battery = position?.battery
    val charging = position?.charging == true
    val lowBattery = battery != null && battery < 20 && !charging

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box {
                Box(Modifier.clip(CircleShape).clickable(onClick = onChangePhoto)) {
                    Avatar(member.name, size = 52.dp, photo = photo, colorHex = member.color)
                }
                // Pastille en ligne / hors ligne sur l'avatar
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(CardSurface).padding(3.dp)
                        .clip(CircleShape).background(if (member.online) Ok else Idle),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isMe) "${member.name} (moi)" else member.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        position != null -> "Vu ${relative(position.fixTime)}"
                        else -> "Aucune position reçue"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions pour ${member.name}", tint = Muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (position != null) {
                        DropdownMenuItem(
                            text = { Text("Voir sur la carte") },
                            leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) },
                            onClick = { menuOpen = false; onShowMap() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Historique") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = { menuOpen = false; onShowHistory() },
                    )
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (photo != null || onRemovePhoto != null) "Changer la photo" else "Ajouter une photo") },
                        leadingIcon = { Icon(Icons.Default.AddAPhoto, contentDescription = null) },
                        onClick = { menuOpen = false; onChangePhoto() },
                    )
                    if (onRemovePhoto != null) {
                        DropdownMenuItem(
                            text = { Text("Retirer la photo") },
                            leadingIcon = { Icon(Icons.Default.HideImage, contentDescription = null) },
                            onClick = { menuOpen = false; onRemovePhoto() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("QR code de configuration") },
                        leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                        onClick = { menuOpen = false; onShowQr() },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer", color = Danger) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Danger) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (member.online) "En ligne" else "Hors ligne", if (member.online) Ok else Idle)
            battery?.let {
                StatusPill(
                    "$it %" + if (charging) " · en charge" else "",
                    when {
                        lowBattery -> Danger
                        charging -> Ok
                        else -> MaterialTheme.colorScheme.primary
                    },
                    icon = if (charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                )
            }
            position?.takeIf { it.accuracy > 0 }?.let {
                StatusPill("± ${it.accuracy.toInt()} m", Muted, icon = Icons.Default.GpsFixed)
            }
        }

        if (position != null) {
            FilledTonalButton(onClick = onShowMap, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Voir sur la carte")
            }
        }
    }
}

/** « Bonjour », « Bon après-midi » ou « Bonsoir » selon l'heure. */
private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bonjour 👋"
    in 12..17 -> "Bon après-midi 👋"
    else -> "Bonsoir 👋"
}

private fun relative(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000L
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 48 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}
