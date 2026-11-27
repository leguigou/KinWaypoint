package fr.kinwaypoint.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * « Écoute en direct » : relais audio entre le téléphone de l'enfant (micro) et
 * celui du parent (haut-parleur). Rien n'est enregistré, ni sur les téléphones
 * ni sur le serveur. L'adresse du relais est annoncée par le serveur familial
 * (server.json, champ audioRelayUrl).
 */
object AudioRelay {
    /** PCM 16 bits mono : bonne intelligibilité de la voix pour ~256 kbit/s. */
    const val SAMPLE_RATE = 16_000

    /** Trames de 100 ms. */
    const val FRAME_BYTES = SAMPLE_RATE * 2 / 10

    /** Une commande d'écoute plus vieille que ça est ignorée (téléphone resté injoignable). */
    const val COMMAND_MAX_AGE_S = 120L

    val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Adresse wss:// du relais, ou null si le serveur familial ne l'annonce pas. */
    suspend fun relayUrl(hub: String = fr.kinwaypoint.app.ui.FAMILY_HUB): String? = withContext(Dispatchers.IO) {
        if (!hub.startsWith("https://")) return@withContext null
        runCatching {
            client.newCall(Request.Builder().url("$hub/apk/server.json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty()).optString("audioRelayUrl")
                    .takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
                    ?.trimEnd('/')
            }
        }.getOrNull()
    }

    /** Identifiant aléatoire (salle ou secret) : 32 caractères hexadécimaux. */
    fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

    fun url(relay: String, room: String, role: String, secret: String) = "$relay/$room?role=$role&secret=$secret"

    /** Commande transmise au téléphone de l'enfant : « listen:<salle>:<secret>:<horodatage> ». */
    fun command(room: String, secret: String) = "listen:$room:$secret:${System.currentTimeMillis() / 1000}"

    data class ListenRequest(val room: String, val secret: String)

    /** Lit une commande d'écoute ; null si absente, mal formée ou trop ancienne. */
    fun parse(line: String): ListenRequest? {
        val parts = line.trim().split(":")
        if (parts.size != 4 || parts[0] != "listen") return null
        val (_, room, secret, stamp) = parts
        if (!room.matches(Regex("[A-Za-z0-9]{16,64}")) || secret.length < 16) return null
        val age = System.currentTimeMillis() / 1000 - (stamp.toLongOrNull() ?: return null)
        return if (age in -60..COMMAND_MAX_AGE_S) ListenRequest(room, secret) else null
    }
}
