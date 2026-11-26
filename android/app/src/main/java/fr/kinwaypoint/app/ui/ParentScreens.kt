package fr.kinwaypoint.app.ui

import androidx.compose.foundation.layout.Arrangement
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
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiUrl by remember { mutableStateOf(current.apiUrl) }
    var trackUrl by remember { mutableStateOf(current.trackUrl) }
    var email by remember { mutableStateOf(current.email) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Espace parent", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Green)
            Text(
                "Connecte-toi avec ton compte du serveur familial. Le mot de passe n'est pas conservé : " +
                    "le téléphone garde seulement un jeton d'accès valable un an.",
                fontSize = 14.sp,
                color = Muted,
            )
            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("Adresse du serveur (interface)") },
                placeholder = { Text("https://api.exemple.fr") },
                singleLine = true,
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
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
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
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp), color = Color.White) else Text("Se connecter")
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Verrouiller")
            }
        }
    }
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
    var showMenu by remember { mutableStateOf(false) }
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

    val names = members.associate { it.id to it.name }
    val newAlarms = alarms.filter { it.id > acknowledgedAlarmId }

    if (mapOpen) {
        FamilyMapScreen(
            members = members,
            focusId = mapFocus,
            permissions = permissions,
            actions = actions,
            onBack = { mapOpen = false },
        )
    } else Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Famille", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Green)
                        Text(
                            if (refreshedAt > 0) "Actualisé à ${formatTime(refreshedAt)}" else "Chargement…",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                    }
                    IconButton(onClick = { openMap(null) }) { Icon(Icons.Default.Map, contentDescription = "Carte", tint = Green) }
                    IconButton(onClick = { refreshTick++ }) { Icon(Icons.Default.Refresh, contentDescription = "Actualiser", tint = Green) }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Green) }
                }
            }

            if (newAlarms.isNotEmpty()) {
                items(newAlarms, key = { "alarm-${it.id}" }) { alarm ->
                    val member = members.firstOrNull { it.id == alarm.deviceId }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Danger),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "🚨 SOS — ${names[alarm.deviceId] ?: "Appareil ${alarm.deviceId}"}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text("Déclenché le ${formatDateTime(alarm.time)}", color = Color.White, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                member?.position?.let { p ->
                                    Button(
                                        onClick = { openMap(member.id) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Danger),
                                    ) { Text("Voir sur la carte") }
                                }
                                OutlinedButton(
                                    onClick = { scope.launch { repository.acknowledgeAlarm(alarm.id) } },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                ) { Text("J'ai vu") }
                            }
                        }
                    }
                }
            }

            if (canUseBiometric && !biometricEnabled && !biometricOfferDismissed) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Ouvrir l'espace parent avec ton empreinte ?", fontWeight = FontWeight.SemiBold)
                            Text("Le code PIN reste valable et toujours demandé pour changer de mode ou de code.", fontSize = 13.sp, color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
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
                item { InfoCard(title = "Actualisation impossible", detail = message, container = SoftOrange) }
            }

            if (loading) {
                item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            } else if (members.isEmpty() && error == null) {
                item {
                    InfoCard(
                        title = "Aucun appareil",
                        detail = "Utilise « Ajouter un enfant » ci-dessous, puis scanne le QR code avec son téléphone en mode enfant.",
                        container = SoftGreen,
                    )
                }
            }

            items(members, key = { it.id }) { member ->
                MemberCard(
                    member = member,
                    onShowMap = { openMap(member.id) },
                    onShowQr = { qrFor = member.name to SetupPayload(trackUrl, member.uniqueId) },
                    onRename = { renaming = member },
                    onDelete = { deleting = member },
                )
            }

            item {
                Button(onClick = { showAddChild = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("＋ Ajouter un enfant")
                }
            }

            item {
                Text(
                    "Les alertes SOS ne s'affichent que lorsque cette application est ouverte : " +
                        "les notifications en arrière-plan ne sont pas encore disponibles.",
                    fontSize = 12.sp,
                    color = Muted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
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

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("Espace parent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connecté : ${config.email}", fontSize = 13.sp, color = Muted)
                    OutlinedButton(onClick = { showMenu = false; onLock() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Verrouiller")
                    }
                    OutlinedButton(onClick = { showMenu = false; onChangePin() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Changer le code PIN")
                    }
                    OutlinedButton(
                        enabled = canUseBiometric || biometricEnabled,
                        onClick = {
                            showMenu = false
                            if (biometricEnabled) {
                                scope.launch { repository.setBiometric(false) }
                            } else {
                                actions.authenticate { scope.launch { repository.setBiometric(true) } }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                biometricEnabled -> "Désactiver l'empreinte"
                                canUseBiometric -> "Déverrouiller avec l'empreinte"
                                else -> "Empreinte : aucune enregistrée sur ce téléphone"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            showMenu = false
                            scope.launch { repository.saveParent(config.copy(token = "")) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Se déconnecter du serveur") }
                    OutlinedButton(onClick = { showMenu = false; onChangeMode() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Changer le mode de l'appareil")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMenu = false }) { Text("Fermer") } },
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    val position = member.position
    val battery = position?.battery
    val charging = position?.charging == true
    val lowBattery = battery != null && battery < 20 && !charging

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = if (member.online) Ok else Idle, fontSize = 16.sp)
                Text(member.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                battery?.let {
                    Text(
                        "🔋 $it %" + if (charging) " ⚡" else "",
                        color = if (lowBattery) Danger else Muted,
                        fontWeight = if (lowBattery) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions pour ${member.name}")
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
                            text = { Text("Renommer") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onRename() },
                        )
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
            if (position == null) {
                Text("Aucune position reçue pour l'instant", fontSize = 13.sp, color = Muted)
            } else {
                Text("Position du ${formatDateTime(position.fixTime)} (${relative(position.fixTime)})", fontSize = 13.sp, color = Muted)
                if (position.accuracy > 0) {
                    Text("Précision : ${position.accuracy.toInt()} m", fontSize = 13.sp, color = Muted)
                }
                OutlinedButton(onClick = onShowMap) { Text("Voir sur la carte") }
            }
            member.lastUpdate?.let {
                Text("Dernière connexion : ${relative(it)}", fontSize = 12.sp, color = Muted)
            }
        }
    }
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
