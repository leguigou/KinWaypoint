package fr.kinwaypoint.app.ui

import androidx.compose.foundation.layout.Arrangement
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
                            repository.saveParent(
                                ParentConfig(apiUrl = TraccarApi.normalize(apiUrl).toString(), email = email, token = token),
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

    Scaffold { padding ->
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
                    TextButton(onClick = { refreshTick++ }) { Text("Actualiser") }
                    TextButton(onClick = { showMenu = true }) { Text("⋮", fontSize = 20.sp) }
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
                                        onClick = { actions.openMap(p.latitude, p.longitude, member.name) },
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

            error?.let { message ->
                item { InfoCard(title = "Actualisation impossible", detail = message, container = SoftOrange) }
            }

            if (loading) {
                item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            } else if (members.isEmpty() && error == null) {
                item {
                    InfoCard(
                        title = "Aucun appareil",
                        detail = "Crée un appareil par enfant dans l'interface web du serveur, puis configure son téléphone en mode enfant.",
                        container = SoftGreen,
                    )
                }
            }

            items(members, key = { it.id }) { member -> MemberCard(member, actions) }

            item {
                Text(
                    "Les alertes n'arrivent que lorsque cette application est ouverte : " +
                        "les notifications en arrière-plan (zones, SOS) seront ajoutées avec Firebase.",
                    fontSize = 12.sp,
                    color = Muted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
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
private fun MemberCard(member: Member, actions: PlatformActions) {
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
            }
            if (position == null) {
                Text("Aucune position reçue pour l'instant", fontSize = 13.sp, color = Muted)
            } else {
                Text("Position du ${formatDateTime(position.fixTime)} (${relative(position.fixTime)})", fontSize = 13.sp, color = Muted)
                if (position.accuracy > 0) {
                    Text("Précision : ${position.accuracy.toInt()} m", fontSize = 13.sp, color = Muted)
                }
                OutlinedButton(onClick = { actions.openMap(position.latitude, position.longitude, member.name) }) {
                    Text("Voir sur la carte")
                }
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
