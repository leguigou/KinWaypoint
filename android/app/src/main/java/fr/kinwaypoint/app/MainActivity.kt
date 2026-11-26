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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.ui.AppRoot
import fr.kinwaypoint.app.ui.KinWaypointTheme
import fr.kinwaypoint.app.ui.PermissionSnapshot
import fr.kinwaypoint.app.ui.PlatformActions
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        /** Au-delà de cette absence, l'espace parent et les réglages se reverrouillent. */
        private const val RELOCK_AFTER_MS = 60_000L
    }

    private var permissionsState by mutableStateOf(PermissionSnapshot())
    private var lockSignal by mutableIntStateOf(0)
    private var stoppedAt = 0L

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsState = snapshotPermissions()
    }

    private var onScanResult: ((String?) -> Unit)? = null
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        onScanResult?.invoke(result.contents)
        onScanResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsState = snapshotPermissions()
        val repository = SettingsRepository(applicationContext)
        val actions = PlatformActions(
            requestLocation = { requestLocation.launch(locationPermissions()) },
            openAppSettings = { openAppSettings() },
            openBatterySettings = { openBatterySettings() },
            openMap = { lat, lon, label -> openMap(lat, lon, label) },
            scanQr = { callback -> scanQr(callback) },
        )

        setContent {
            KinWaypointTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
        if (stoppedAt > 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) {
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
