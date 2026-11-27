package fr.kinwaypoint.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import fr.kinwaypoint.app.MainActivity
import fr.kinwaypoint.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Sources de mise à jour, dans l'ordre : le dépôt d'APK familial (route publique
 * /apk/latest.json, voir server/config-api), puis GitHub en secours.
 * Format : {"versionCode": 15, "versionName": "0.14.1", "apkUrl": "…", "sha256": "…"}
 */
private val UPDATE_SOURCES = listOfNotNull(
    FAMILY_HUB.takeIf { it.startsWith("https://") }?.let { "$it/apk/latest.json" },
    "https://github.com/leguigou/KinWaypoint/releases/download/test-latest/version.json",
)

data class AvailableUpdate(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String?)

private val updateHttp by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
}

private fun installedVersionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
}.getOrDefault(Long.MAX_VALUE)

/**
 * Ménage : supprime les APK de mise à jour déjà installés (ou plus anciens) et
 * les téléchargements interrompus. Appelé à chaque lancement.
 */
fun cleanupDownloadedUpdates(context: Context) {
    val installed = installedVersionCode(context)
    File(context.cacheDir, "updates").listFiles()?.forEach { f ->
        val code = Regex("""kinwaypoint-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()
        if (f.name.endsWith(".part") || code == null || code <= installed) f.delete()
    }
}

/** Nouvelle version disponible (null : à jour, ou aucune source joignable). */
suspend fun checkForUpdate(context: Context): AvailableUpdate? = withContext(Dispatchers.IO) {
    for (source in UPDATE_SOURCES) {
        val found = runCatching {
            updateHttp.newCall(Request.Builder().url(source).header("Cache-Control", "no-cache").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                AvailableUpdate(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.optString("versionName"),
                    apkUrl = json.optString("apkUrl"),
                    sha256 = json.optString("sha256").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) },
                )
            }
        }.getOrNull() ?: continue // source injoignable : on essaie la suivante
        return@withContext found.takeIf { it.versionCode > installedVersionCode(context) && it.apkUrl.startsWith("https://") }
    }
    null
}

/**
 * Télécharge l'APK dans le cache de l'appli et vérifie son empreinte SHA-256
 * (fichier corrompu ou modifié = refusé). Android vérifie en plus la signature :
 * seule une version signée avec la même clé peut remplacer l'appli.
 */
suspend fun downloadUpdate(context: Context, update: AvailableUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // anciennes versions téléchargées
    val target = File(dir, "kinwaypoint-${update.versionCode}.apk")
    val part = File(dir, target.name + ".part")
    val digest = MessageDigest.getInstance("SHA-256")
    updateHttp.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
        if (!response.isSuccessful) error("Téléchargement refusé (HTTP ${response.code})")
        val body = response.body ?: error("Réponse vide")
        val total = body.contentLength()
        body.byteStream().use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    }
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    if (update.sha256 != null && sha != update.sha256) {
        part.delete()
        error("Fichier altéré (empreinte différente) : mise à jour refusée")
    }
    if (!part.renameTo(target)) error("Enregistrement impossible")
    target
}

/**
 * Lance l'installeur d'Android (confirmation obligatoire, imposée par Android).
 * Renvoie false si l'autorisation « installer des applis inconnues » manque :
 * l'écran de réglage correspondant est alors ouvert.
 */
fun installUpdate(context: Context, apk: File): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return true
}

/**
 * Vérification en arrière-plan (tâche Android planifiée toutes les 4 h) :
 * une notification par nouvelle version, qui ouvre l'appli sur le bandeau.
 */
suspend fun notifyIfUpdateAvailable(context: Context) {
    val update = checkForUpdate(context) ?: return
    val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    if (prefs.getLong("notified", 0L) >= update.versionCode) return
    prefs.edit().putLong("notified", update.versionCode).apply()

    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(NotificationChannel("updates", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val open = PendingIntent.getActivity(
        context, 3, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(
        88,
        NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mise à jour KinWaypoint ${update.versionName}")
            .setContentText("Ouvre l'appli pour l'installer (réglages conservés).")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build(),
    )
}

/** Tâche planifiée par Android : survit à la veille, au redémarrage et à l'appli fermée. */
class UpdateCheckWorker(context: Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { notifyIfUpdateAvailable(applicationContext) }
        return Result.success()
    }
}

/** Planifie la vérification des mises à jour (toutes les 4 h, avec réseau). Sans effet si déjà planifiée. */
fun scheduleUpdateChecks(context: Context) {
    val request = androidx.work.PeriodicWorkRequestBuilder<UpdateCheckWorker>(4, java.util.concurrent.TimeUnit.HOURS)
        .setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build(),
        )
        .build()
    androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "kw-update-check",
        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

/**
 * Bandeau « nouvelle version » : téléchargement dans l'appli avec progression,
 * vérification de l'empreinte, puis installeur Android.
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) } // -1 = pas de téléchargement en cours
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { update = checkForUpdate(context) }
    val u = update ?: return

    SectionCard(container = SoftGreen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.SystemUpdate, MaterialTheme.colorScheme.primary, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text("Nouvelle version ${u.versionName}", style = MaterialTheme.typography.titleSmall)
                Text(
                    message ?: "Elle s'installe par-dessus : tes réglages sont conservés.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message != null) Danger else Muted,
                )
            }
        }
        if (progress >= 0f) {
            // Barre pleine et épaisse, sans le point de fin ni l'espace ajoutés par Material 3.
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Button(
            enabled = progress < 0f,
            shape = CircleShape,
            onClick = {
                val ready = file
                if (ready != null) {
                    // Déjà téléchargée (ex. retour après avoir autorisé l'installation).
                    if (!installUpdate(context, ready)) message = "Autorise KinWaypoint à installer des applis, puis touche « Installer »."
                    return@Button
                }
                message = null
                progress = 0f
                scope.launch {
                    runCatching { downloadUpdate(context, u) { p -> progress = p } }
                        .onSuccess { apk ->
                            file = apk
                            if (!installUpdate(context, apk)) message = "Autorise KinWaypoint à installer des applis, puis touche « Installer »."
                        }
                        .onFailure { message = it.message ?: "Téléchargement impossible" }
                    progress = -1f
                }
            },
        ) {
            Text(
                when {
                    progress >= 0f -> "Téléchargement… ${(progress * 100).toInt()} %"
                    file != null -> "Installer"
                    else -> "Mettre à jour maintenant"
                },
            )
        }
    }
}
