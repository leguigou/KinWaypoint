package fr.kinwaypoint.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fichier publié par la CI à chaque build, à côté de l'APK de test :
 * {"versionCode": 13, "versionName": "0.13.0", "apkUrl": "…/kinwaypoint-test.apk"}
 */
private const val VERSION_URL = "https://github.com/leguigou/KinWaypoint/releases/download/test-latest/version.json"

data class AvailableUpdate(val versionCode: Long, val versionName: String, val apkUrl: String)

private fun installedVersionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
}.getOrDefault(Long.MAX_VALUE)

/** Nouvelle version disponible (null : à jour, ou vérification impossible). */
suspend fun checkForUpdate(context: Context): AvailableUpdate? = withContext(Dispatchers.IO) {
    runCatching {
        val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string().orEmpty())
            AvailableUpdate(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName"),
                apkUrl = json.optString("apkUrl"),
            ).takeIf { it.versionCode > installedVersionCode(context) && it.apkUrl.startsWith("https://") }
        }
    }.getOrNull()
}

/**
 * Bandeau « nouvelle version » : vérifie au lancement et propose de télécharger
 * l'APK (signé avec la même clé : il s'installe par-dessus, réglages conservés).
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    LaunchedEffect(Unit) { update = checkForUpdate(context) }
    val u = update ?: return
    SectionCard(container = SoftGreen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.SystemUpdate, MaterialTheme.colorScheme.primary, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text("Nouvelle version ${u.versionName}", style = MaterialTheme.typography.titleSmall)
                Text("Elle s'installe par-dessus : tes réglages sont conservés.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        Button(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            shape = androidx.compose.foundation.shape.CircleShape,
        ) { Text("Télécharger la mise à jour") }
    }
}
