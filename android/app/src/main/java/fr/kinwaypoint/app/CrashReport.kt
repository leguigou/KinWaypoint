package fr.kinwaypoint.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapport de plantage, sans service tiers : l'erreur est écrite sur le téléphone
 * au moment où l'appli se ferme, puis proposée (copier / partager) au lancement
 * suivant. Il ne contient ni position ni donnée personnelle, seulement l'erreur.
 */
object CrashReport {
    private const val FILE = "crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(app, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun describe(context: Context, thread: Thread, error: Throwable): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("KinWaypoint $version — ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) — ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Fil : ${thread.name}")
            appendLine()
            append(error.stackTraceToString().take(12_000))
        }
    }
}

/** Au lancement : « l'appli s'est fermée la dernière fois », avec le rapport à envoyer. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReport.pending(context)) }
    val text = report ?: return
    fun close() {
        CrashReport.clear(context)
        report = null
    }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("L'appli s'est fermée la dernière fois") },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Envoie ce rapport pour aider à corriger le problème. Il ne contient aucune position, seulement l'erreur.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.heightIn(min = 40.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                .putExtra(Intent.EXTRA_SUBJECT, "Rapport de plantage KinWaypoint"),
                            "Envoyer le rapport",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                close()
            }) { Text("Partager") }
        },
        dismissButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Rapport KinWaypoint", text))
                close()
            }) { Text("Copier et fermer") }
        },
    )
}
