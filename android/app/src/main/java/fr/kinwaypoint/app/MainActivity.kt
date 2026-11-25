package fr.kinwaypoint.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import fr.kinwaypoint.app.service.TrackingService
import fr.kinwaypoint.app.service.TrackingStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var permissionsState by mutableStateOf(PermissionSnapshot())

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsState = snapshotPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsState = snapshotPermissions()

        setContent {
            KinWaypointTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        permissions = permissionsState,
                        onRequestLocation = { requestLocation.launch(locationPermissions()) },
                        onOpenAppSettings = { openAppSettings() },
                        onOpenBatterySettings = { openBatterySettings() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsState = snapshotPermissions()
    }

    private fun locationPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun snapshotPermissions(): PermissionSnapshot {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return PermissionSnapshot(
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryOptimized = !power.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.fromParts("package", packageName, null)
        runCatching { startActivity(intent) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

data class PermissionSnapshot(
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val notifications: Boolean = false,
    val batteryOptimized: Boolean = true,
)

private val Green = Color(0xFF1F6F5C)
private val GreenLight = Color(0xFF2E8F76)

@Composable
private fun KinWaypointTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Green,
        onPrimary = Color.White,
        secondary = GreenLight,
        background = Color(0xFFF7F9F8),
        surface = Color.White,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun HomeScreen(
    permissions: PermissionSnapshot,
    onRequestLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context.applicationContext) }
    val status by TrackingStatus.state.collectAsState()

    var serverUrl by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("60") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val config = repository.snapshot()
        serverUrl = config.serverUrl
        deviceId = config.deviceId
        interval = config.intervalSeconds.toString()
        loaded = true
    }

    fun persist(enabled: Boolean) {
        scope.launch {
            repository.save(
                TrackingConfig(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    intervalSeconds = interval.toIntOrNull() ?: 60,
                    enabled = enabled,
                ),
            )
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("KinWaypoint", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Green)
            Text(
                "Mode enfant — cet appareil partage sa position avec le serveur familial.",
                fontSize = 14.sp,
                color = Color(0xFF5B6B66),
            )

            StatusCard(status = status, permissions = permissions)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Configuration", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Serveur") },
                        placeholder = { Text("https://track.exemple.fr") },
                        singleLine = true,
                        enabled = loaded && !status.running,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("Identifiant de l'appareil") },
                        placeholder = { Text("kw…") },
                        singleLine = true,
                        enabled = loaded && !status.running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                        label = { Text("Intervalle (secondes)") },
                        singleLine = true,
                        enabled = loaded && !status.running,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            if (!permissions.fineLocation) {
                PermissionRow(
                    label = "Localisation requise",
                    detail = "Sans elle, aucun suivi n'est possible.",
                    action = "Autoriser",
                    onClick = onRequestLocation,
                )
            } else if (!permissions.backgroundLocation) {
                PermissionRow(
                    label = "Localisation en arrière-plan",
                    detail = "Choisir « Toujours autoriser » dans les réglages de l'app.",
                    action = "Ouvrir",
                    onClick = onOpenAppSettings,
                )
            }

            if (permissions.batteryOptimized) {
                PermissionRow(
                    label = "Optimisation batterie active",
                    detail = "Android peut couper le suivi écran éteint.",
                    action = "Désactiver",
                    onClick = onOpenBatterySettings,
                )
            }

            if (status.running) {
                Button(
                    onClick = {
                        persist(false)
                        TrackingService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B7A75)),
                ) { Text("Arrêter le suivi") }

                Button(
                    onClick = {
                        TrackingService.sos(context)
                        Toast.makeText(context, "Alerte SOS envoyée", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("SOS", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = {
                        if (!permissions.fineLocation) {
                            onRequestLocation()
                            return@Button
                        }
                        if (deviceId.isBlank() || serverUrl.isBlank()) {
                            Toast.makeText(context, "Renseigne le serveur et l'identifiant", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        persist(true)
                        TrackingService.start(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Démarrer le suivi", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun StatusCard(status: fr.kinwaypoint.app.service.TrackingStatusState, permissions: PermissionSnapshot) {
    val dot = when {
        !status.running -> Color(0xFF9E9E9E)
        status.lastOk == false -> Color(0xFFEF6C00)
        else -> Color(0xFF2E7D32)
    }
    val title = when {
        !status.running -> "Suivi arrêté"
        status.lastOk == false -> "En attente de réseau"
        status.lastSentAt == null -> "Recherche d'une position…"
        else -> "Suivi actif"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF5F3)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = dot, fontSize = 16.sp)
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            status.lastSentAt?.let {
                Text("Dernier envoi : ${formatTime(it)}", fontSize = 13.sp, color = Color(0xFF5B6B66))
            }
            status.battery?.let { Text("Batterie : $it %", fontSize = 13.sp, color = Color(0xFF5B6B66)) }
            status.accuracy?.let {
                Text("Précision : ${it.toInt()} m", fontSize = 13.sp, color = Color(0xFF5B6B66))
            }
            if (status.sosPending) {
                Text("Alerte SOS en cours d'envoi", fontSize = 13.sp, color = Color(0xFFC62828))
            }
            if (!permissions.notifications) {
                Text("Notifications désactivées : le suivi peut être interrompu.", fontSize = 13.sp, color = Color(0xFFEF6C00))
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, detail: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 13.sp, color = Color(0xFF6B5A45))
            OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) { Text(action) }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(millis))
