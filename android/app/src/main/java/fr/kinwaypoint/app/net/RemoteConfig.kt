package fr.kinwaypoint.app.net

import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.data.ParentConfig
import fr.kinwaypoint.app.data.SettingsRepository
import fr.kinwaypoint.app.data.TrackingConfig
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Configuration à distance : un lien HTTPS (contenant un jeton) renvoie un JSON
 * qui remplit l'appli à la place de la saisie manuelle.
 *
 * Exemple de réponse attendue : voir `docs/CONFIG-DISTANTE.md`.
 */
data class RemoteConfig(
    /** Mode de l'appareil, si le serveur l'impose ("child" ou "parent"). */
    val mode: AppMode?,
    val child: Child?,
    val parent: Parent?,
    /** Texte libre affiché à l'utilisateur (ex. « Téléphone de Léa »). */
    val label: String?,
) {
    data class Child(
        val serverUrl: String,
        val deviceId: String,
        val intervalSeconds: Int?,
    )

    data class Parent(
        val apiUrl: String,
        val trackUrl: String?,
        val email: String?,
    )

    companion object {
        const val SUPPORTED_VERSION = 1
        private const val MAX_BYTES = 64 * 1024

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false) // jamais de redirection HTTPS → HTTP
            .build()

        /** Le texte collé ou scanné ressemble-t-il à un lien de configuration ? */
        fun looksLikeLink(text: String?): Boolean = text?.trim()?.startsWith("https://", ignoreCase = true) == true

        /** Télécharge et valide la configuration. Lève une exception au message lisible. */
        fun fetch(link: String): RemoteConfig {
            val url = link.trim().toHttpUrlOrNull() ?: error("Lien invalide")
            require(url.isHttps) { "Le lien doit commencer par https:// (la configuration contient des secrets)" }

            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            val body = http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 || response.code == 404 || response.code == 410 ->
                        error("Code invalide, expiré ou déjà utilisé")
                    !response.isSuccessful -> error("Serveur de configuration en erreur (HTTP ${response.code})")
                    !response.request.url.isHttps -> error("Redirection non sécurisée refusée")
                }
                // Lecture plafonnée (readNBytes n'existe qu'à partir d'Android 13).
                val bytes = response.body?.byteStream()?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= MAX_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                if (bytes.size > MAX_BYTES) error("Réponse trop volumineuse")
                String(bytes, Charsets.UTF_8)
            }
            return parse(body)
        }

        fun parse(text: String): RemoteConfig {
            val json = runCatching { JSONObject(text) }.getOrElse { error("Réponse illisible (JSON attendu)") }

            val version = json.optInt("version", SUPPORTED_VERSION)
            if (version > SUPPORTED_VERSION) error("Configuration trop récente : mets l'appli à jour")

            json.optString("expiresAt").takeIf { it.isNotBlank() }?.let {
                val expires = runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
                    .getOrElse { error("Date d'expiration illisible") }
                if (expires < System.currentTimeMillis()) error("Ce code de configuration a expiré")
            }

            val mode = when (json.optString("mode").lowercase()) {
                "" -> null
                "child", "enfant" -> AppMode.CHILD
                "parent" -> AppMode.PARENT
                else -> error("Mode inconnu : ${json.optString("mode")}")
            }

            val child = json.optJSONObject("child")?.let { c ->
                val deviceId = c.optString("deviceId").trim()
                if (deviceId.length < 12) error("Identifiant d'appareil absent ou trop court (12 caractères minimum)")
                RemoteConfig.Child(
                    serverUrl = httpsUrl(c.optString("serverUrl"), "child.serverUrl"),
                    deviceId = deviceId,
                    intervalSeconds = c.optInt("intervalSeconds", 0).takeIf { it > 0 }?.coerceIn(15, 3600),
                )
            }

            val parent = json.optJSONObject("parent")?.let { p ->
                RemoteConfig.Parent(
                    apiUrl = httpsUrl(p.optString("apiUrl"), "parent.apiUrl"),
                    trackUrl = p.optString("trackUrl").takeIf { it.isNotBlank() }?.let { httpsUrl(it, "parent.trackUrl") },
                    email = p.optString("email").trim().takeIf { it.isNotBlank() },
                )
            }

            if (child == null && parent == null) error("La configuration ne contient ni « child » ni « parent »")
            if (mode == AppMode.CHILD && child == null) error("Mode enfant demandé mais section « child » absente")
            if (mode == AppMode.PARENT && parent == null) error("Mode parent demandé mais section « parent » absente")

            return RemoteConfig(mode, child, parent, json.optString("label").trim().takeIf { it.isNotBlank() })
        }

        private fun httpsUrl(value: String, field: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: error("Adresse « $field » invalide")
            if (!url.isHttps) error("L'adresse « $field » doit être en https://")
            return url.toString().trimEnd('/')
        }
    }

    /** Range la configuration reçue dans les réglages. Le suivi n'est PAS démarré automatiquement. */
    suspend fun applyTo(repository: SettingsRepository) {
        child?.let { c ->
            val current = repository.config.first()
            repository.save(
                TrackingConfig(
                    serverUrl = c.serverUrl,
                    deviceId = c.deviceId,
                    intervalSeconds = c.intervalSeconds ?: current.intervalSeconds,
                    enabled = current.enabled,
                ),
            )
        }
        parent?.let { p ->
            val current = repository.parent.first()
            repository.saveParent(
                current.copy(
                    apiUrl = p.apiUrl,
                    trackUrl = p.trackUrl ?: current.trackUrl,
                    email = p.email ?: current.email,
                ),
            )
        }
    }
}

/** Réglages parent pré-remplis, pour l'écran de connexion. */
fun ParentConfig.withRemote(p: RemoteConfig.Parent) =
    copy(apiUrl = p.apiUrl, trackUrl = p.trackUrl ?: trackUrl, email = p.email ?: email)
