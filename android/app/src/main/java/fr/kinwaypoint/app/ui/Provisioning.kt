package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fr.kinwaypoint.app.data.ParentConfig
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Contenu d'un QR code de configuration d'un téléphone enfant :
 * `kinwaypoint://setup?server=<adresse d'envoi>&id=<identifiant>&interval=<s>`
 */
data class SetupPayload(
    val serverUrl: String,
    val deviceId: String,
    val intervalSeconds: Int? = null,
    /** Adresse de l'API et jeton lecteur : permettent à l'enfant de voir ses parents. */
    val apiUrl: String? = null,
    val viewerToken: String? = null,
) {

    fun encode(): String = Uri.Builder()
        .scheme("kinwaypoint")
        .authority("setup")
        .appendQueryParameter("server", serverUrl)
        .appendQueryParameter("id", deviceId)
        .apply {
            intervalSeconds?.let { appendQueryParameter("interval", it.toString()) }
            if (!apiUrl.isNullOrBlank() && !viewerToken.isNullOrBlank()) {
                appendQueryParameter("api", apiUrl)
                appendQueryParameter("viewer", viewerToken)
            }
        }
        .build()
        .toString()

    companion object {
        fun decode(text: String?): SetupPayload? {
            val uri = runCatching { Uri.parse(text?.trim() ?: return null) }.getOrNull() ?: return null
            if (uri.scheme != "kinwaypoint" || uri.host != "setup") return null
            val server = uri.getQueryParameter("server")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val id = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() }
            if (server == null || id == null) return null
            return SetupPayload(
                serverUrl = server,
                deviceId = id,
                intervalSeconds = uri.getQueryParameter("interval")?.toIntOrNull(),
                apiUrl = uri.getQueryParameter("api")?.takeIf { it.startsWith("https://") },
                viewerToken = uri.getQueryParameter("viewer")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Jeton du compte « Enfants (lecture seule) » à glisser dans les QR codes, pour que
 * l'enfant voie ses parents. Mis en cache ; null si le serveur refuse (droits
 * insuffisants…) : le QR reste valable, l'enfant ne verra simplement pas ses parents.
 */
suspend fun childViewerToken(repository: SettingsRepository, config: ParentConfig): String? {
    repository.cachedChildViewerToken()?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching { TraccarApi(config.apiUrl).childViewerToken(config.token) }.getOrNull()
    }?.let { (token, expires) ->
        repository.cacheChildViewerToken(token, expires)
        token
    }
}

/** QR code de configuration complet pour un enfant (envoi + accès « Voir mes parents »). */
suspend fun childSetupPayload(repository: SettingsRepository, config: ParentConfig, trackUrl: String, deviceId: String) =
    SetupPayload(
        serverUrl = trackUrl,
        deviceId = deviceId,
        apiUrl = config.apiUrl,
        viewerToken = childViewerToken(repository, config),
    )

/**
 * Identifiant d'appareil aléatoire : 26 caractères (≈ 130 bits) sans caractères
 * ambigus. Le protocole OsmAnd n'ayant pas d'authentification, c'est lui le secret.
 */
fun generateDeviceId(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    val random = SecureRandom()
    return "kw" + (1..26).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

/** Devine l'adresse d'envoi à partir de celle de l'interface (api.x → track.x), à vérifier. */
fun guessTrackUrl(apiUrl: String): String {
    val uri = runCatching { Uri.parse(apiUrl) }.getOrNull() ?: return ""
    val host = uri.host ?: return ""
    val guessed = if (host.startsWith("api.")) "track." + host.removePrefix("api.") else host
    return "https://$guessed"
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Affiche le QR code de configuration d'un enfant. */
@Composable
fun SetupQrDialog(name: String, payload: SetupPayload, onDismiss: () -> Unit) {
    val bitmap = remember(payload) { qrBitmap(payload.encode()).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurer le téléphone de $name") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(bitmap = bitmap, contentDescription = "QR code de configuration", modifier = Modifier.size(260.dp))
                Text(
                    "Sur son téléphone : mode Enfant → Réglages parent → « Scanner le QR code ».",
                    fontSize = 13.sp,
                    color = Muted,
                )
                Text(
                    "Ce code contient l'identifiant secret de l'appareil : ne le photographie pas, ne l'envoie pas.",
                    fontSize = 12.sp,
                    color = Danger,
                )
                Text("Serveur : ${payload.serverUrl}", fontSize = 12.sp, color = Muted)
                Text(payload.deviceId, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Création d'un appareil enfant sur le serveur, puis affichage de son QR code. */
@Composable
fun AddChildDialog(
    repository: SettingsRepository,
    config: ParentConfig,
    onCreated: (name: String, payload: SetupPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var trackUrl by remember { mutableStateOf(config.trackUrl.ifBlank { guessTrackUrl(config.apiUrl) }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Ajouter un enfant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Prénom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = trackUrl,
                    onValueChange = { trackUrl = it },
                    label = { Text("Adresse d'envoi des positions") },
                    supportingText = { Text("Le domaine du port 5055, pas celui de l'interface") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && trackUrl.trim().startsWith("https://"),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val id = generateDeviceId()
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val api = TraccarApi(config.apiUrl)
                                val deviceId = api.createDevice(config.token, name, id)
                                // Visible des autres enfants (et de lui-même) sur leur carte.
                                runCatching { api.setVisibleToChildren(config.token, deviceId, visible = true) }
                                deviceId
                            }
                        }
                        result.onSuccess {
                            repository.saveParent(config.copy(trackUrl = trackUrl.trim()))
                            onCreated(name.trim(), childSetupPayload(repository, config, trackUrl.trim(), id))
                        }.onFailure { error = it.message ?: "Création impossible" }
                        busy = false
                    }
                },
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") } },
    )
}
