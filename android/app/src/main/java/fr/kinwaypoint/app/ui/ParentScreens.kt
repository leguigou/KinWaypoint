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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Hearing
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.runtime.rememberUpdatedState
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
import fr.kinwaypoint.app.net.targets
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
    // Les adresses déjà connues (reconnexion) restent dans « Réglages avancés ».
    var email by remember(current.email) { mutableStateOf(current.email) }
    var showConfigCode by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var advanced by remember { mutableStateOf(false) }

    /** Connexion : adresses du serveur trouvées automatiquement, sauf si saisies à la main. */
    fun signIn() {
        busy = true
        error = null
        scope.launch {
            val server = if (apiUrl.isNotBlank()) {
                ServerInfo(apiUrl.trim(), trackUrl.trim().ifBlank { guessTrackUrl(apiUrl.trim()) })
            } else {
                discoverServer()
            }
            if (server == null) {
                error = "Serveur familial introuvable. Vérifie la connexion, ou renseigne l'adresse dans « Réglages avancés »."
                advanced = true
                busy = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) { runCatching { TraccarApi(server.apiUrl).login(email, password) } }
            result.onSuccess { token ->
                password = ""
                val normalized = TraccarApi.normalize(server.apiUrl).toString()
                repository.saveParent(
                    current.copy(apiUrl = normalized, trackUrl = server.trackUrl.ifBlank { guessTrackUrl(normalized) }, email = email.trim(), token = token),
                )
            }.onFailure { error = it.message ?: "Connexion impossible" }
            busy = false
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            BrandMark(size = 72.dp)
            Text("Espace parent", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Connecte-toi avec ton compte familial. Le mot de passe n'est pas conservé : " +
                    "le téléphone garde seulement un jeton d'accès valable un an.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            )
            var showPassword by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Masquer" else "Afficher",
                        )
                    }
                },
                singleLine = true,
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    if (!busy && email.isNotBlank() && password.isNotBlank()) signIn()
                }),
            )
            error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodyMedium) }

            Button(
                onClick = { signIn() },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Text("Se connecter")
            }

            // Réglages avancés : autre serveur, ou serveur familial injoignable.
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (advanced) "Masquer les réglages avancés" else "Réglages avancés")
            }
            if (advanced) {
                SectionCard {
                    Text(
                        "Laisse vide pour utiliser le serveur familial ($FAMILY_HUB).",
                        style = MaterialTheme.typography.bodySmall,
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
                        label = { Text("Adresse d'envoi des positions") },
                        placeholder = { Text(guessTrackUrl(apiUrl).ifBlank { "https://track.exemple.fr" }) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    TextButton(onClick = { showConfigCode = true }) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("J'ai un code d'invitation")
                    }
                }
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
            onFilled = { p ->
                showConfigCode = false
                if (p.token != null) {
                    // Code d'invitation : clé de connexion fournie, pas de mot de passe à saisir.
                    val normalized = TraccarApi.normalize(p.apiUrl).toString()
                    scope.launch {
                        repository.saveParent(
                            current.copy(
                                apiUrl = normalized,
                                trackUrl = p.trackUrl ?: guessTrackUrl(normalized),
                                email = p.email.orEmpty(),
                                token = p.token,
                            ),
                        )
                    }
                } else {
                    apiUrl = p.apiUrl
                    p.trackUrl?.let { trackUrl = it }
                    p.email?.let { email = it }
                }
            },
        )
    }
}

/**
 * Code d'invitation (connexion directe, clé fournie) ou ancien code de
 * configuration (champs pré-remplis, mot de passe à saisir).
 */
@Composable
private fun ParentConfigCode(actions: PlatformActions, onFilled: (fr.kinwaypoint.app.net.RemoteConfig.Parent) -> Unit, onDismiss: () -> Unit) {
    ConfigCodeDialog(
        actions = actions,
        expected = fr.kinwaypoint.app.data.AppMode.PARENT,
        onDismiss = onDismiss,
        onResult = { cfg -> cfg.parent?.let(onFilled) },
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
    var placesOpen by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    var weeklyOpen by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf<List<fr.kinwaypoint.app.net.Place>>(emptyList()) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyFocus by remember { mutableStateOf<Long?>(null) }
    var todayTraces by remember { mutableStateOf<Map<Long, List<fr.kinwaypoint.app.net.TracePoint>>>(emptyMap()) }
    val ownDeviceId by repository.ownDeviceId.collectAsState(initial = 0L)
    val trackingContext = LocalContext.current
    // Position de CE téléphone (le parent) : sert à l'alerte de proximité.
    val myLocation = rememberMyLocation(enabled = permissions.fineLocation)
    val proximityConfig by repository.proximityAlert.collectAsState(initial = ProximityConfig())

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
    var listeningTo by remember { mutableStateOf<Member?>(null) }
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
        withContext(Dispatchers.IO) { runCatching { api.places(config.token) } }.onSuccess { places = it }
        result.onSuccess { (list, recent) ->
            // Parent en mode fantôme : invisible ici, sauf pour lui-même.
            members = list.filter { !it.ghost || it.id == ownDeviceId }.map { fresh ->
                fresh.copy(position = fresh.position?.keepSteps(members.firstOrNull { it.id == fresh.id }?.position))
            }
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

    val photos = rememberMemberPhotos(api, config.token, members)

    // Mise à jour en direct : positions poussées par le serveur dès leur arrivée.
    val live = rememberLiveUpdates(api, config.token) { message ->
        if (message.positions.isNotEmpty() || message.online.isNotEmpty()) {
            members = members.map { m ->
                val p = message.positions[m.id]
                when {
                    p != null -> m.copy(position = p.keepSteps(m.position), online = true, lastUpdate = p.fixTime)
                    message.online[m.id] != null -> m.copy(online = message.online[m.id]!!)
                    else -> m
                }
            }
            refreshedAt = System.currentTimeMillis()
            // Le tracé du jour (et la distance parcourue) s'allonge en direct.
            run {
                todayTraces = todayTraces.toMutableMap().apply {
                    message.positions.forEach { (id, p) ->
                        val trace = this[id].orEmpty()
                        if (trace.lastOrNull()?.time?.let { p.fixTime > it } != false) this[id] = trace + p.toTracePoint()
                    }
                }
            }
        }
        // Nouvel événement (SOS, lieu…) : on recharge les alertes.
        if (message.hasEvents) refreshTick++
    }
    val liveNow by rememberUpdatedState(live)

    // Actualisation toutes les 30 s, uniquement quand l'écran est visible.
    LaunchedEffect(api, refreshTick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                // Connexion en direct établie : simple vérification toutes les 5 min.
                delay(if (liveNow) 300_000L else REFRESH_MS)
            }
        }
    }

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
    // Notifications du serveur en français et à l'heure de ce téléphone (une fois par session).
    LaunchedEffect(config.token) {
        withContext(Dispatchers.IO) { runCatching { api.ensureUserPreferences(config.token) } }
    }

    LaunchedEffect(members.filter { !it.isParent }.map { it.id }, places.map { it.id }, proximityConfig, ownDeviceId) {
        if (members.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // Alerte de proximité : un enfant ajouté après coup est lié lui aussi.
            if (proximityConfig.enabled && ownDeviceId != 0L) {
                val children = members.filter { !it.isParent && it.id != ownDeviceId }.map { it.id }
                runCatching { api.setProximity(config.token, children, ownDeviceId, proximityConfig.radiusMeters) }
            }
            runCatching { api.shareChildrenWithEachOther(config.token, members) }
            // Les autres parents voient toute la famille (appareils hors mode fantôme, et lieux).
            runCatching { api.shareFamilyWithParents(config.token, members.filter { !it.ghost }.map { it.id }, places.map { it.id }) }
            // Chaque lieu s'applique à tous les appareils (y compris un enfant ajouté après le lieu).
            places.filter { it.alerts }.forEach { place ->
                // Un parent en mode fantôme n'est rattaché à aucun lieu (ses arrivées trahiraient sa position).
                val all = members.filter { !it.ghost }.map { it.id }
                val targets = place.targets(all)
                runCatching { api.linkPlace(config.token, place.id, targets) }
                if (targets.size < all.size) runCatching { api.unlinkPlace(config.token, place.id, all - targets.toSet()) }
            }
            // Les lieux apparaissent aussi sur la carte des enfants.
            runCatching { api.sharePlacesWithChildren(config.token, places.map { it.id }) }
        }
    }

    // Tracés du jour (carte, distances parcourues) : rechargés chaque minute
    // quand la carte est ouverte, toutes les 5 minutes sinon.
    LaunchedEffect(mapOpen, members.map { it.id }) {
        while (true) {
            val zone = java.time.ZoneId.systemDefault()
            val from = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { members.associate { m -> m.id to api.positions(config.token, m.id, from, now) } }
            }.onSuccess { todayTraces = it }
            delay(if (mapOpen) 60_000 else 5 * 60_000)
        }
    }

    val names = members.associate { it.id to it.name }
    val newAlarms = alarms.filter { it.id > acknowledgedAlarmId }

    // Alerte de proximité : un enfant redevenu proche après s'être éloigné.
    val (proximityArrivals, dismissProximity) = rememberProximityAlerts(
        config = proximityConfig,
        parent = myLocation,
        members = members,
        ownDeviceId = ownDeviceId,
    )

    val sosIds = alarms.filter { it.id > acknowledgedAlarmId }.map { it.deviceId }.toSet()

    listeningTo?.let { child ->
        ListenScreen(api = api, token = config.token, member = child, photo = photos[child.id], onBack = { listeningTo = null })
        return
    }

    if (weeklyOpen) {
        WeeklyScreen(
            api = api,
            token = config.token,
            members = members,
            photos = photos,
            places = places,
            ownDeviceId = ownDeviceId,
            onBack = { weeklyOpen = false },
        )
    } else if (placesOpen) {
        PlacesScreen(
            api = api,
            token = config.token,
            places = places,
            members = members,
            permissions = permissions,
            onChanged = { refreshTick++ },
            onBack = { placesOpen = false },
            photos = photos,
        )
    } else if (eventsOpen) {
        EventsScreen(
            api = api,
            token = config.token,
            members = members,
            photos = photos,
            places = places,
            onOpenMember = { id -> eventsOpen = false; openMap(id) },
            onBack = { eventsOpen = false },
        )
    } else if (settingsOpen) {
        ParentSettingsScreen(
            onOpenPlaces = { placesOpen = true },
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
            proximity = proximityConfig,
            onProximityChange = { scope.launch { repository.setProximityAlert(it) } },
            placesCount = places.size,
            onAddChild = { showAddChild = true },
        )
    } else if (historyOpen) {
        HistoryScreen(
            places = places,
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
            places = places,
            sosIds = sosIds,
            onLocate = { id ->
                val who = members.firstOrNull { it.id == id }?.name ?: "ce membre"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.locateNow(config.token, id) }
                    android.widget.Toast.makeText(
                        trackingContext,
                        if (ok) "Position demandée à $who…" else "Téléphone de $who pas joignable tout de suite : il répondra à son prochain envoi.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismissSos = { deviceId ->
                // Ferme toutes les alertes SOS en cours de ce membre.
                alarms.filter { it.deviceId == deviceId }.maxOfOrNull { it.id }?.let { id ->
                    scope.launch { repository.acknowledgeAlarm(id) }
                }
            },
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
            // Marge sous le dernier bouton (« Ajouter un enfant ») : il ne colle plus au bas de l'écran.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
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
                    FilledTonalIconButton(onClick = { eventsOpen = true }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications")
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

            item { UpdateBanner() }

            // Bilan de la semaine
            item {
                SectionCard(modifier = Modifier.clickable { weeklyOpen = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconBadge(Icons.Default.BarChart, MaterialTheme.colorScheme.secondary, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text("Bilan de la semaine", style = MaterialTheme.typography.titleMedium)
                            Text("Pas, objectif, distance, temps passé à l'école…", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
                    }
                }
            }

            items(proximityArrivals, key = { "prox-${it.memberId}" }) { arrival ->
                val d = if (arrival.meters < 1000) "${arrival.meters} m" else formatDistance(arrival.meters.toFloat())
                SectionCard(container = SoftGreen) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Default.MyLocation, AppTheme.colors.success)
                        Column(Modifier.weight(1f)) {
                            Text("${arrival.name} est proche", style = MaterialTheme.typography.titleMedium)
                            Text("À environ $d · ${formatTime(arrival.time)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { openMap(arrival.memberId) }, shape = CircleShape) {
                            Text("Voir sur la carte")
                        }
                        OutlinedButton(onClick = { dismissProximity(arrival.memberId) }, shape = CircleShape) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("J'ai vu")
                        }
                    }
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
                                OutlinedButton(
                                    onClick = { scope.launch { repository.acknowledgeAlarm(alarm.id) } },
                                    shape = CircleShape,
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("J'ai vu, fermer")
                                }
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
                    onRing = {
                        scope.launch {
                            val instant = withContext(Dispatchers.IO) { runCatching { api.ring(config.token, member.id) } }
                            android.widget.Toast.makeText(
                                trackingContext,
                                instant.fold(
                                    { if (it) "Le téléphone de ${member.name} va sonner dans quelques secondes." else "Il sonnera à son prochain envoi de position (10 min maximum)." },
                                    { it.message ?: "Impossible de faire sonner" },
                                ),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onListen = if (!member.isParent && member.id != ownDeviceId) {
                        { listeningTo = member }
                    } else {
                        null
                    },
                    onLocate = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { api.locateNow(config.token, member.id) }
                            android.widget.Toast.makeText(
                                trackingContext,
                                if (ok) "Position demandée à ${member.name}…" else "Téléphone pas encore joignable à distance (mets à jour son appli).",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    isMe = member.id == ownDeviceId,
                    place = member.position?.let { placeAt(it.latitude, it.longitude, places) },
                    placeSince = member.position?.let { placeAt(it.latitude, it.longitude, places) }
                        ?.let { p -> todayTraces[member.id]?.let { inPlaceSince(it, p) } },
                    travel = todayTraces[member.id]?.let { travelSplit(it) },
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

    qrFor?.let { (name, payload) -> SetupQrDialog(name, payload, onDismiss = { qrFor = null }, inviteToken = config.token) }

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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MemberCard(
    member: Member,
    onShowMap: () -> Unit,
    onShowQr: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    photo: android.graphics.Bitmap? = null,
    onShowHistory: () -> Unit = {},
    onRing: () -> Unit = {},
    onLocate: () -> Unit = {},
    /** Écoute en direct (enfants uniquement), null = pas proposée. */
    onListen: (() -> Unit)? = null,
    isMe: Boolean = false,
    /** Lieu où se trouve le membre (maison, école…), ou null. */
    place: fr.kinwaypoint.app.net.Place? = null,
    /** Arrivée dans ce lieu (pour « Maison · 2 h 26 »), ou null si inconnue. */
    placeSince: Long? = null,
    /** Distance du jour par mode de déplacement. */
    travel: TravelSplit? = null,
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
                // Bouclier en haut à droite : compte parent.
                if (member.isParent) ParentShield(Modifier.align(Alignment.TopEnd))
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
            }
            // Actions du membre : feuille qui monte du bas, aux couleurs de l'appli.
            if (menuOpen) {
                MemberActionsSheet(
                    member = member,
                    photo = photo,
                    isMe = isMe,
                    onDismiss = { menuOpen = false },
                    onShowMap = if (position != null) onShowMap else null,
                    onLocate = onLocate,
                    onRing = onRing,
                    onListen = onListen,
                    onShowHistory = onShowHistory,
                    onRename = onRename,
                    onChangePhoto = onChangePhoto,
                    onRemovePhoto = onRemovePhoto,
                    onShowQr = onShowQr,
                    onDelete = onDelete,
                )
            }
        }

        // FlowRow : les gélules passent à la ligne au lieu de déborder sur petit écran.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Dans un lieu : gélule « 🏠 Maison » à la couleur du lieu, en premier.
            place?.let {
                val since = placeSince?.let { t -> " · " + shortDuration(System.currentTimeMillis() - t) }.orEmpty()
                StatusPill("${placeEmoji(it.icon)} ${it.name}$since", parseHex(it.color) ?: MaterialTheme.colorScheme.primary, icon = null, showDot = false)
            }
            StatusPill(if (member.online) "En ligne" else "Hors ligne", if (member.online) Ok else Idle)
            // Ancienneté de la position : vert < 15 min, orange < 1 h, rouge au-delà.
            position?.let { StatusPill("🕒 ${relativeTime(it.fixTime)}", Color(freshnessColor(it.fixTime)), showDot = false) }
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
            // Sonnerie coupée : utile avant d'appeler.
            when (position?.ringer) {
                "silent" -> StatusPill("Silencieux", Warning, icon = Icons.AutoMirrored.Filled.VolumeOff)
                "vibrate" -> StatusPill("Vibreur", Muted, icon = Icons.Default.Vibration)
            }
            // Précision : vert ≤ 30 m, orange ≤ 100 m, rouge au-delà.
            position?.takeIf { it.accuracy > 0 }?.let {
                StatusPill("± ${it.accuracy.toInt()} m · ${accuracyWord(it.accuracy)}", Color(accuracyColor(it.accuracy)), icon = Icons.Default.GpsFixed)
            }
            // Distance parcourue aujourd'hui : à pied, vélo, véhicule.
            travel?.let { t -> travelParts(t).forEach { (emoji, km) -> StatusPill("$emoji $km", MaterialTheme.colorScheme.tertiary, showDot = false) } }
        }
        position?.steps?.let { StepGoalBar(it, member.stepGoal, profileColor(member.name, member.color)) }

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

/**
 * Position sans nombre de pas (podomètre pas encore relu, ancienne version de
 * l'appli enfant) : on garde celui de la position précédente du même jour,
 * plutôt que de faire disparaître la barre de pas.
 */
private fun fr.kinwaypoint.app.net.MemberPosition.keepSteps(previous: fr.kinwaypoint.app.net.MemberPosition?): fr.kinwaypoint.app.net.MemberPosition {
    if (steps != null || previous?.steps == null) return this
    val zone = java.time.ZoneId.systemDefault()
    val sameDay = java.time.Instant.ofEpochMilli(previous.fixTime).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(fixTime).atZone(zone).toLocalDate()
    return if (sameDay) copy(steps = previous.steps) else this
}

/**
 * Actions d'un membre, dans une feuille qui monte du bas : en-tête avec
 * l'avatar, actions rapides en gros boutons ronds colorés, puis les autres
 * actions dans une carte, et « Supprimer » à part, en rouge.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MemberActionsSheet(
    member: Member,
    photo: android.graphics.Bitmap?,
    isMe: Boolean,
    onDismiss: () -> Unit,
    onShowMap: (() -> Unit)?,
    onLocate: () -> Unit,
    onRing: () -> Unit,
    onListen: (() -> Unit)?,
    onShowHistory: () -> Unit,
    onRename: () -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: (() -> Unit)?,
    onShowQr: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Chaque action ferme d'abord la feuille.
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val primary = MaterialTheme.colorScheme.primary
    val violet = Color(0xFF8B5CF6)

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // En-tête : qui est concerné.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box {
                    Avatar(member.name, size = 56.dp, photo = photo, colorHex = member.color)
                    if (member.isParent) ParentShield(Modifier.align(Alignment.TopEnd))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (isMe) "${member.name} (moi)" else member.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        member.position?.let { "Vu ${relativeTime(it.fixTime)}" } ?: "Aucune position reçue",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }

            // Actions rapides.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(Icons.Default.Map, "Carte", primary, enabled = onShowMap != null) { onShowMap?.let { run(it)() } }
                QuickAction(Icons.Default.MyLocation, "Localiser", Ok, onClick = run(onLocate))
                QuickAction(Icons.Default.NotificationsActive, "Sonner", Warning, onClick = run(onRing))
                onListen?.let { QuickAction(Icons.Default.Hearing, "Écouter", violet, onClick = run(it)) }
            }

            // Autres actions.
            SectionCard {
                ActionRow(Icons.Default.History, "Historique", primary, run(onShowHistory))
                ActionRow(Icons.Default.Edit, "Renommer", primary, run(onRename))
                ActionRow(
                    Icons.Default.AddAPhoto,
                    if (photo != null || onRemovePhoto != null) "Changer la photo" else "Ajouter une photo",
                    primary,
                    run(onChangePhoto),
                )
                onRemovePhoto?.let { ActionRow(Icons.Default.HideImage, "Retirer la photo", Muted, run(it)) }
                ActionRow(Icons.Default.QrCode, "QR code de configuration", primary, run(onShowQr))
            }

            // Action irréversible, à part.
            SectionCard(container = SoftRed) {
                ActionRow(Icons.Default.Delete, "Supprimer ${member.name}", Danger, run(onDelete), showChevron = false)
            }
        }
    }
}

/** Gros bouton rond coloré avec son libellé (actions rapides). */
@Composable
private fun QuickAction(icon: ImageVector, label: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f * alpha)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(26.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha))
    }
}

/** Ligne d'action : icône dans une pastille teintée, libellé, chevron. */
@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, showChevron: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, tint, size = 40.dp)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tint == Danger) Danger else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (showChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
        }
    }
}
