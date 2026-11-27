package fr.kinwaypoint.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Chien de garde du suivi : toutes les 15 min (minimum Android), relance le
 * service s'il ne tourne plus alors que le suivi est activé. Couvre l'arrêt
 * depuis le volet « Applications actives » d'Android 13+, la fermeture par
 * certaines surcouches (balayage dans les applis récentes) et les arrêts par
 * manque de mémoire. Ne fait rien si le suivi a été arrêté volontairement
 * depuis l'appli (réglage « enabled » à faux).
 *
 * Limite Android : après « Forcer l'arrêt » dans les réglages de l'appli, plus
 * rien ne tourne (ni tâches ni alarmes) tant que l'appli n'est pas rouverte.
 */
class TrackingWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { restartIfNeeded(applicationContext) }
            .onFailure { Log.w(TAG, "relance du suivi impossible", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "KinWaypoint"
        private const val PERIODIC = "kw-tracking-watchdog"
        private const val SOON = "kw-tracking-restart"

        /** Planifie le contrôle périodique (sans effet s'il l'est déjà). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrackingWatchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Contrôle dans ~30 s (appli balayée des applis récentes, service détruit). */
        fun checkSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackingWatchdog>().setInitialDelay(30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }

        suspend fun restartIfNeeded(context: Context) {
            if (TrackingStatus.state.value.running) return
            val repository = SettingsRepository(context)
            val config = repository.snapshot()
            val mode = repository.mode.first()
            if (mode == AppMode.UNSET || !config.enabled || !config.isValid) return
            fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
            // Depuis l'arrière-plan, Android 10+ n'autorise la localisation qu'avec « Toujours autoriser ».
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Log.w(TAG, "suivi arrêté, relance impossible sans « Toujours autoriser »")
                return
            }
            Log.i(TAG, "suivi arrêté alors qu'il est activé : relance")
            // Android 12+ peut refuser un démarrage depuis l'arrière-plan (sans exemption batterie) : rattrapé.
            runCatching { TrackingService.start(context) }
                .onFailure { Log.w(TAG, "démarrage refusé par Android", it) }
        }
    }
}
