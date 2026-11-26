package fr.kinwaypoint.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Un membre de la famille vu par l'API Traccar. */
data class Member(
    val id: Long,
    val name: String,
    /** Identifiant d'appareil côté Traccar : c'est le secret d'envoi des positions. */
    val uniqueId: String,
    val online: Boolean,
    val lastUpdate: Long?,
    val position: MemberPosition?,
)

data class MemberPosition(
    val latitude: Double,
    val longitude: Double,
    val fixTime: Long,
    val accuracy: Double,
    val battery: Int?,
    val charging: Boolean?,
)

data class Alarm(
    val id: Long,
    val deviceId: Long,
    val time: Long,
    val kind: String,
)

class AuthException(message: String) : Exception(message)

/**
 * Accès en lecture à l'API REST Traccar pour le mode parent.
 *
 * Le mot de passe ne sert qu'une fois, à la connexion : on obtient un jeton
 * signé par le serveur (valable un an), seul conservé sur le téléphone et
 * envoyé en `Authorization: Bearer`.
 */
class TraccarApi(baseUrl: String) {

    private val base: HttpUrl = normalize(baseUrl)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        fun normalize(url: String): HttpUrl {
            var text = url.trim().trimEnd('/')
            if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
            if (text.endsWith("/api")) text = text.removeSuffix("/api")
            return text.toHttpUrl()
        }

        private fun parseTime(text: String?): Long? =
            text?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

        private fun iso(millis: Long): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneOffset.UTC),
            )
    }

    /** Connexion par e-mail / mot de passe, renvoie un jeton d'accès longue durée. */
    fun login(email: String, password: String): String {
        // Cookie de session éphémère, le temps d'obtenir le jeton.
        val jar = mutableListOf<Cookie>()
        val session = http.newBuilder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val names = cookies.map { it.name }.toSet()
                    jar.removeAll { it.name in names }
                    jar.addAll(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> = jar.toList()
            })
            .build()

        val loginRequest = Request.Builder()
            .url(url("api/session"))
            .post(FormBody.Builder().add("email", email.trim()).add("password", password).build())
            .build()
        session.newCall(loginRequest).execute().use { response ->
            when {
                response.code == 401 -> throw AuthException("E-mail ou mot de passe incorrect")
                !response.isSuccessful -> error("Connexion refusée (HTTP ${response.code})")
            }
        }

        val expiration = iso(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
        val tokenRequest = Request.Builder()
            .url(url("api/session/token"))
            .post(FormBody.Builder().add("expiration", expiration).build())
            .build()
        return session.newCall(tokenRequest).execute().use { response ->
            if (!response.isSuccessful) error("Jeton refusé (HTTP ${response.code})")
            response.body?.string()?.trim().takeUnless { it.isNullOrBlank() } ?: error("Jeton vide")
        }
    }

    /** Tous les membres visibles par ce compte, avec leur dernière position. */
    fun members(token: String): List<Member> {
        val devices = getArray(token, url("api/devices"))
        val positions = getArray(token, url("api/positions"))

        val byDevice = HashMap<Long, MemberPosition>()
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val attrs = p.optJSONObject("attributes")
            byDevice[p.getLong("deviceId")] = MemberPosition(
                latitude = p.getDouble("latitude"),
                longitude = p.getDouble("longitude"),
                fixTime = parseTime(p.optString("fixTime")) ?: 0L,
                accuracy = p.optDouble("accuracy", 0.0),
                battery = attrs?.takeIf { it.has("batteryLevel") }?.optDouble("batteryLevel")?.toInt(),
                charging = attrs?.takeIf { it.has("charge") }?.optBoolean("charge"),
            )
        }

        return (0 until devices.length()).map { i ->
            val d = devices.getJSONObject(i)
            val id = d.getLong("id")
            Member(
                id = id,
                name = d.optString("name"),
                uniqueId = d.optString("uniqueId"),
                online = d.optString("status") == "online",
                lastUpdate = parseTime(d.optString("lastUpdate")),
                position = byDevice[id],
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Alertes SOS des dernières `hours` heures pour les appareils donnés. */
    fun recentAlarms(token: String, deviceIds: List<Long>, hours: Int = 24): List<Alarm> {
        if (deviceIds.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val builder = url("api/reports/events").newBuilder()
            .addQueryParameter("type", "alarm")
            .addQueryParameter("from", iso(now - hours * 3600_000L))
            .addQueryParameter("to", iso(now + 60_000L))
        deviceIds.forEach { builder.addQueryParameter("deviceId", it.toString()) }

        val events = getArray(token, builder.build())
        return (0 until events.length()).map { i ->
            val e = events.getJSONObject(i)
            Alarm(
                id = e.getLong("id"),
                deviceId = e.getLong("deviceId"),
                time = parseTime(e.optString("eventTime")) ?: 0L,
                kind = e.optJSONObject("attributes")?.optString("alarm").orEmpty().ifBlank { "alarm" },
            )
        }.sortedByDescending { it.time }
    }

    /** Crée un appareil (un enfant) avec l'identifiant fourni. Renvoie son id Traccar. */
    fun createDevice(token: String, name: String, uniqueId: String): Long {
        val json = JSONObject().put("name", name.trim()).put("uniqueId", uniqueId).toString()
        val request = Request.Builder()
            .url(url("api/devices"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw AuthException("Session expirée, reconnecte-toi")
                response.code == 400 -> error("Création refusée : ce compte n'a peut-être pas le droit d'ajouter un appareil")
                !response.isSuccessful -> error("Erreur serveur (HTTP ${response.code})")
            }
            return JSONObject(response.body?.string().orEmpty()).getLong("id")
        }
    }

    /**
     * Renomme un appareil. Traccar exige l'objet complet en PUT (un objet
     * partiel efface les autres champs et est refusé) : on relit puis on renvoie.
     */
    fun renameDevice(token: String, id: Long, name: String) {
        val device = authorized(token, Request.Builder().url(url("api/devices/$id")).get()).use { response ->
            check(response)
            JSONObject(response.body?.string().orEmpty())
        }
        device.put("name", name.trim())
        val put = Request.Builder()
            .url(url("api/devices/$id"))
            .put(device.toString().toRequestBody("application/json".toMediaType()))
        authorized(token, put).use { check(it) }
    }

    /** Supprime un appareil et tout son historique sur le serveur. */
    fun deleteDevice(token: String, id: Long) {
        authorized(token, Request.Builder().url(url("api/devices/$id")).delete()).use { check(it) }
    }

    private fun authorized(token: String, builder: Request.Builder) = http.newCall(
        builder.header("Authorization", "Bearer $token").header("Accept", "application/json").build(),
    ).execute()

    private fun check(response: okhttp3.Response) {
        when {
            response.code == 401 -> throw AuthException("Session expirée, reconnecte-toi")
            response.code == 400 || response.code == 403 -> error("Opération refusée par le serveur (droits insuffisants ?)")
            response.code == 404 -> error("Appareil introuvable (déjà supprimé ?)")
            !response.isSuccessful -> error("Erreur serveur (HTTP ${response.code})")
        }
    }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    /**
     * Déclare le jeton Firebase du téléphone sur le compte, dans l'attribut
     * `notificationTokens` que lit le notificateur Firebase de Traccar.
     *
     * Traccar refuse un PUT partiel (400) et son update réécrit toutes les
     * colonnes sauf `id` : il faut donc relire l'objet utilisateur complet,
     * modifier l'attribut, puis le renvoyer tel quel (vérifié en prod).
     */
    fun registerPushToken(token: String, fcmToken: String) {
        // /api/session n'accepte pas le Bearer : l'identité passe par ?token=
        val sessionUrl = url("api/session").newBuilder().addQueryParameter("token", token).build()
        val me = getObject(token, sessionUrl)
        val userId = me.getLong("id")
        val user = getObject(token, url("api/users/$userId"))
        val attributes = user.optJSONObject("attributes") ?: JSONObject()
        val existing = attributes.optString("notificationTokens")
            .split(Regex("[, ]+"))
            .filter { it.isNotBlank() }
        if (fcmToken in existing) return
        attributes.put("notificationTokens", (existing + fcmToken).joinToString(","))
        user.put("attributes", attributes)
        putObject(token, url("api/users/$userId"), user.toString())
    }

    private fun getObject(token: String, url: HttpUrl): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw AuthException("Session expirée, reconnecte-toi")
            if (!response.isSuccessful) error("Erreur serveur (HTTP ${response.code})")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun putObject(token: String, url: HttpUrl, body: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw AuthException("Session expirée, reconnecte-toi")
            if (!response.isSuccessful) error("Erreur serveur (HTTP ${response.code})")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun getArray(token: String, url: HttpUrl): JSONArray {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw AuthException("Session expirée, reconnecte-toi")
            if (!response.isSuccessful) error("Erreur serveur (HTTP ${response.code})")
            return JSONArray(response.body?.string().orEmpty().ifBlank { "[]" })
        }
    }
}
