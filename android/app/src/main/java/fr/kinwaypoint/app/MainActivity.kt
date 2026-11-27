package fr.kinwaypoint.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.maplibre.android.MapLibre
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.net.TraccarApi
import fr.kinwaypoint.app.ui.AppRoot
import fr.kinwaypoint.app.ui.KinWaypointTheme
import fr.kinwaypoint.app.ui.PermissionSnapshot
import fr.kinwaypoint.app.ui.PlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// FragmentActivity (et non ComponentActivity) : exigée par BiometricPrompt.
class MainActivity : FragmentActivity() {

    /** Absence au-delà de laquelle l'espace parent se reverrouille (0 = jamais), réglable. */
    @Volatile private var relockAfterMs = 60_000L
    private var repositoryRef: SettingsRepository? = null

    private var permissionsState by mutableStateOf(PermissionSnapshot())
    private var lockSignal by mutableIntStateOf(0)
    private var stoppedAt = 0L

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsState = snapshotPermissions()
        // Le suivi en cours prend en compte les nouvelles autorisations (podomètre).
        if (fr.kinwaypoint.app.service.TrackingStatus.state.value.running) {
            fr.kinwaypoint.app.service.TrackingService.start(this)
        }
    }

    // Localisation « Toujours » : demande séparée (Android 11+ ouvre sa propre page de réglage).
    private val requestBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionsState = snapshotPermissions()
    }

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionsState = snapshotPermissions()
    }

    private var onScanResult: ((String?) -> Unit)? = null
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        onScanResult?.invoke(result.contents)
        onScanResult = null
    }

    private var onImagePicked: ((Uri?) -> Unit)? = null
    // Sélecteur de photos système : aucune autorisation d'accès aux fichiers n'est demandée.
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onImagePicked?.invoke(uri)
        onImagePicked = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        // Barres système transparentes : l'appli dessine jusqu'aux bords (clair et sombre).
        enableEdgeToEdge()
        permissionsState = snapshotPermissions()
        // Initialisation de la carte, une fois, avant tout affichage.
        MapLibre.getInstance(applicationContext)
        val repository = SettingsRepository(applicationContext)
        repositoryRef = repository
        lifecycleScope.launch {
            combine(repository.relockMinutes, repository.mode) { minutes, mode -> minutes to mode }.collect { (minutes, mode) ->
                // Le réglage ne concerne que l'espace parent : côté enfant, toujours 1 min.
                relockAfterMs = if (mode == fr.kinwaypoint.app.data.AppMode.PARENT) minutes * 60_000L else 60_000L
            }
        }
        // Notification quand une nouvelle version est déposée (parents et enfants).
        runCatching { fr.kinwaypoint.app.ui.scheduleUpdateChecks(applicationContext) }
        // APK de mise à jour déjà installé : supprimé (il n'occupe plus de place).
        runCatching { fr.kinwaypoint.app.ui.cleanupDownloadedUpdates(applicationContext) }
        lifecycleScope.launch { repository.migrateDefaults() }

        // Notifications poussées : on récupère le jeton FCM, puis on le déclare
        // au serveur dès que le parent est connecté (attribut notificationTokens
        // lu par le notificateur Firebase de Traccar).
        lifecycleScope.launch {
            runCatching {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    lifecycleScope.launch { repository.savePushToken(token) }
                }
            }.onFailure { Log.w("KinWaypoint", "Firebase indisponible : ${it.message}") }
        }

        lifecycleScope.launch {
            combine(repository.parent, repository.pushToken) { parent, token -> parent to token }
                .collect { (parent, token) ->
                    if (parent.isConnected && !token.isNullOrBlank()) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                TraccarApi(parent.apiUrl).registerPushToken(parent.token, token)
                            }
                        }.onFailure {
                            Log.w("KinWaypoint", "jeton push non déclaré : ${it.message}")
                        }
                    }
                }
        }
        val actions = PlatformActions(
            requestLocation = { requestLocation.launch(locationPermissions()) },
            openAppSettings = { openAppSettings() },
            openBatterySettings = { openBatterySettings() },
            openMap = { lat, lon, label -> openMap(lat, lon, label) },
            scanQr = { callback -> scanQr(callback) },
            biometricAvailable = { biometricAvailable() },
            authenticate = { onSuccess -> authenticate(onSuccess) },
            pickImage = { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            requestMicrophone = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
            requestBackgroundLocation = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                        .onFailure { openAppSettings() }
                } else {
                    openAppSettings()
                }
            },
            openDndSettings = {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    .onFailure { openAppSettings() }
            },
            openOverlaySettings = {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
                }.onFailure { openAppSettings() }
            },
        )

        setContent {
            KinWaypointTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashReportDialog()
                    AppRoot(
                        repository = repository,
                        permissions = permissionsState,
                        actions = actions,
                        lockSignal = lockSignal,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val relock = relockAfterMs
        if (stoppedAt > 0L && relock > 0L && SystemClock.elapsedRealtime() - stoppedAt > relock) {
            lockSignal++
        }
        stoppedAt = 0L
    }

    override fun onResume() {
        super.onResume()
        permissionsState = snapshotPermissions()
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        // Heure de sortie mémorisée : au prochain lancement, on sait si le délai est écoulé.
        repositoryRef?.let { repo -> lifecycleScope.launch { repo.markActive() } }
        super.onStop()
    }

    private fun locationPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list += Manifest.permission.ACTIVITY_RECOGNITION
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
            activityRecognition = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACTIVITY_RECOGNITION),
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            overlay = Settings.canDrawOverlays(this),
            dndAccess = getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted,
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

    /**
     * Empreinte « forte » uniquement (classe 3) : la reconnaissance faciale par
     * simple photo, souvent classée faible, est exclue. Android 9+ : en dessous,
     * l'ancienne boîte de dialogue exige un thème AppCompat absent de l'appli.
     */
    private fun biometricAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate(onSuccess: () -> Unit) {
        if (!biometricAvailable()) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                // Échec, annulation ou « Utiliser le code PIN » : on reste sur le pavé numérique.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Espace parent")
            .setSubtitle("Pose ton doigt pour déverrouiller")
            .setNegativeButtonText("Utiliser le code PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    private fun scanQr(callback: (String?) -> Unit) {
        onScanResult = callback
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Vise le QR code affiché sur le téléphone du parent")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    /** Ouvre la position dans l'appli de cartes installée (Google Maps, OsmAnd, Organic Maps…). */
    private fun openMap(latitude: Double, longitude: Double, label: String) {
        val coords = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        val uri = Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure {
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=17/$latitude/$longitude"),
                    ),
                )
            }.onFailure {
                Toast.makeText(this, "Aucune application de carte disponible", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
