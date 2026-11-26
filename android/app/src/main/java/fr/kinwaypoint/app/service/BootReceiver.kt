package fr.kinwaypoint.app.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Relance le suivi après un redémarrage du téléphone, si l'appareil est en mode enfant et le suivi activé. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = SettingsRepository(context)
                val config = repository.snapshot()
                val mode = repository.mode.first()
                val hasLocation = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

                if (mode == AppMode.CHILD && config.enabled && config.isValid && hasLocation) {
                    TrackingService.start(context)
                }
            } catch (e: Exception) {
                Log.e("KinWaypoint", "relance au démarrage impossible", e)
            } finally {
                pending.finish()
            }
        }
    }
}
