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
    /** Nom du fichier photo sur le serveur (attribut Traccar `deviceImage`), ou null. */
    val photo: String? = null,
    /** Change à chaque nouvelle photo : sert de clé de cache. */
    val photoVersion: Long = 0L,
    /** Couleur du tracé choisie par les parents (« #RRGGBB »), ou null = couleur par défaut. */
    val color: String? = null,
    /** Appareil d'un parent (« Partager ma position ») et non d'un enfant. */
    val isParent: Boolean = false,
    /** Objectif de pas quotidien (8 000 par défaut). */
    val stepGoal: Int = DEFAULT_STEP_GOAL,
    /** Limite de vitesse (km/h) déclenchant l'alerte « excès de vitesse », ou null. */
    val speedLimitKmh: Int? = null,
    /** Parent en mode fantôme : invisible des enfants et des autres parents. */
    val ghost: Boolean = false,
    /** Rang d'affichage choisi par les parents (glisser-déposer), ou null = ordre alphabétique. */
    val order: Int? = null,
    /** Cadences de suivi réglées par les parents, ou null = valeurs par défaut du téléphone. */
    val cadence: Cadence? = null,
    /** Désactivé par un parent (attribut kwHidden) : masqué de l'accueil et de la carte, suivi inchangé. */
    val hidden: Boolean = false,
)

/**
 * Cadences d'envoi des positions (secondes) : normale, en mouvement (déplacement
 * détecté), à l'arrêt (GPS coupé). Plus c'est fréquent, plus la batterie s'use.
 */
data class Cadence(val normal: Int, val moving: Int, val still: Int) {
    companion object {
        val ECO = Cadence(600, 60, 1200)
        val BALANCED = Cadence(300, 15, 600)
        val PRECISE = Cadence(60, 10, 300)
    }

    fun presetName(): String? = when (this) {
        ECO -> "Économe"
        BALANCED -> "Équilibré"
        PRECISE -> "Précis"
        else -> null
    }
}

/** Compte parent créé depuis l'appli : identifiants à transmettre (mot de passe affiché une fois). */
data class NewParentAccount(val email: String, val password: String)

/** Résultat du mode fantôme : comptes administrateurs qui voient encore tout sur le serveur. */
data class GhostResult(val adminsStillSeeing: Int)

/** Objectif de pas quotidien proposé par défaut. */
const val DEFAULT_STEP_GOAL = 8000

/** Un point d'historique. */
data class TracePoint(
    val latitude: Double,
    val longitude: Double,
    val time: Long,
    val speedKmh: Double,
    val accuracy: Double,
    val battery: Int?,
    val steps: Int? = null,
    val activity: String? = null,
    /** Téléphone en charge à ce moment (attribut « charge »), ou null si inconnu. */
    val charging: Boolean? = null,
)

data class MemberPosition(
    val latitude: Double,
    val longitude: Double,
    val fixTime: Long,
    val accuracy: Double,
    val battery: Int?,
    val charging: Boolean?,
    /** "normal", "vibrate", "silent" (envoyé par l'appli), ou null. */
    val ringer: String? = null,
    /** Nom du Wi-Fi connecté, ou null. */
    val wifi: String? = null,
    /** Wi-Fi activé sur le téléphone (même non connecté), ou null si inconnu. */
    val wifiOn: Boolean? = null,
    /** Pas du jour (podomètre du téléphone), ou null. */
    val steps: Int? = null,
    /** Activité estimée par le téléphone : still, walking, bike, vehicle. */
    val activity: String? = null,
    /** Vitesse en km/h (lissée par le téléphone si disponible, sinon GPS). */
    val kmh: Double? = null,
)

/** Un lieu de la famille (zone circulaire Traccar) : maison, école… */
data class Place(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    /** Type d'icône : home, school, work, sport… (voir PlaceTypes). */
    val icon: String,
    /** Couleur choisie (« #RRGGBB »). */
    val color: String = "#5B5BD6",
    /** Alertes d'arrivée / départ ; false = simple point d'intérêt sur la carte. */
    val alerts: Boolean = true,
    /** Enfants concernés par les alertes ; null = tout le monde. */
    val childIds: List<Long>? = null,
    /** Horaires des alertes ; null = tout le temps. */
    val schedule: PlaceSchedule? = null,
    /** Nom du Wi-Fi de ce lieu (ex. la box de la maison) : connecté = présent, même si le GPS est imprécis. */
    val ssid: String? = null,
)

/** Appareils concernés par les alertes d'un lieu, parmi ceux donnés. */
fun Place.targets(deviceIds: List<Long>): List<Long> =
    childIds?.takeIf { it.isNotEmpty() }?.let { chosen -> deviceIds.filter { it in chosen } } ?: deviceIds

/**
 * Créneau d'un lieu : jours (1 = lundi … 7 = dimanche) et heures (minutes depuis
 * minuit). Fin avant début = le créneau passe minuit (ex. 20:00 → 07:00).
 */
data class PlaceSchedule(val days: Set<Int>, val fromMinutes: Int, val toMinutes: Int) {
    /**
     * Calendrier iCalendar lu par Traccar (calendrier de la zone) : les arrivées
     * et départs ne sont signalés que pendant ces créneaux. Fuseau du téléphone,
     * heure d'été comprise. Format vérifié avec ical4j 4.4.0 (celui de Traccar 6.17).
     */
    fun toICalendar(placeId: Long, name: String, zone: String = java.util.TimeZone.getDefault().id): String {
        val byDay = days.sorted().joinToString(",") { listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[it - 1] }
        // Lundi 5 janvier 2026 (puis +1 jour si le créneau passe minuit).
        fun stamp(day: Int, minutes: Int) = String.format(java.util.Locale.US, "202601%02dT%02d%02d00", day, minutes / 60, minutes % 60)
        val endDay = if (toMinutes <= fromMinutes) 6 else 5
        val safeName = name.replace(Regex("[\\r\\n,;]"), " ")
        return listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//KinWaypoint//Horaires//FR",
            "BEGIN:VEVENT",
            "UID:kw-place-$placeId@kinwaypoint",
            "DTSTAMP:20260101T000000Z",
            "DTSTART;TZID=$zone:${stamp(5, fromMinutes)}",
            "DTEND;TZID=$zone:${stamp(endDay, toMinutes)}",
            "RRULE:FREQ=WEEKLY;BYDAY=$byDay",
            "SUMMARY:$safeName",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")
    }
}

/** Un événement à afficher dans la cloche : arrivée, départ, SOS. */
data class FamilyEvent(
    val id: Long,
    val deviceId: Long,
    val type: String,
    val time: Long,
    val placeId: Long,
    /** Pour une alarme : « sos », « lowBattery », « general » (bien arrivé·e)… */
    val alarm: String = "",
)

/** Alertes réglables depuis l'appli (règles de notification Traccar). */
enum class AlertRule(val key: String, val type: String, val alarms: String? = null) {
    /** SOS : toujours actif, créé d'office pour chaque compte parent (pas d'interrupteur). */
    SOS("sos", "alarm", "sos"),
    NO_NEWS("noNews", "deviceUnknown"),
    LOW_BATTERY("lowBattery", "alarm", "lowBattery"),
    CHECK_IN("checkIn", "alarm", "general"),
    OVERSPEED("overspeed", "deviceOverspeed"),
    /** Un enfant revient près du téléphone d'un parent (calculé par Traccar, appareils liés). */
    PROXIMITY("proximity", "proximityEnter"),
}

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
        /** Positions sans activité envoyée (ancienne version de l'appli) : déduite de la vitesse. */
        fun activityFromSpeed(kmh: Double): String? = when {
            kmh > 15 -> "vehicle"
            kmh >= 7 -> "bike"
            else -> null
        }

        /** Adresse (fictive) du compte lecteur des enfants, créé par l'appli parent. */
        const val CHILD_VIEWER_EMAIL = "kw-enfants@kinwaypoint.local"

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
    fun login(
        email: String,
        password: String,
        expiresAt: Long = System.currentTimeMillis() + 365L * 24 * 3600 * 1000,
    ): String {
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

        val expiration = iso(expiresAt)
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
            byDevice[p.getLong("deviceId")] = parsePosition(p)
        }

        return (0 until devices.length()).map { i ->
            val d = devices.getJSONObject(i)
            parseMember(d, byDevice[d.getLong("id")])
        }.sortedWith(compareBy<Member>({ it.order ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    /** Message reçu en direct : nouvelles positions, statut des appareils, événements. */
    data class LiveMessage(
        val positions: Map<Long, MemberPosition>,
        val online: Map<Long, Boolean>,
        val hasEvents: Boolean,
    )

    fun parseLiveMessage(text: String): LiveMessage {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return LiveMessage(emptyMap(), emptyMap(), false)
        // Chaque élément est lu séparément : un élément inattendu est ignoré, jamais fatal.
        val positions = json.optJSONArray("positions")?.let { a ->
            (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { it.getLong("deviceId") to parsePosition(it) } }.getOrNull() }.toMap()
        }.orEmpty()
        val online = json.optJSONArray("devices")?.let { a ->
            (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { it.getLong("id") to (it.optString("status") == "online") } }.getOrNull() }.toMap()
        }.orEmpty()
        return LiveMessage(positions, online, (json.optJSONArray("events")?.length() ?: 0) > 0)
    }

    /**
     * Connexion permanente au serveur (WebSocket /api/socket) : chaque nouvelle
     * position arrive tout de suite. La session (cookie) est ouverte avec le jeton.
     */
    fun openLiveSocket(token: String, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val session = url("api/session").newBuilder().addQueryParameter("token", token).build()
        mediaHttp.newCall(Request.Builder().url(session).get().build()).execute().use { response ->
            if (response.code == 401) throw AuthException("Session expirée, reconnecte-toi")
            if (!response.isSuccessful) error("Connexion en direct refusée (HTTP ${response.code})")
        }
        val live = mediaHttp.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
        return live.newWebSocket(Request.Builder().url(url("api/socket")).build(), listener)
    }

    private fun parsePosition(p: JSONObject): MemberPosition {
        val attrs = p.optJSONObject("attributes")
        return MemberPosition(
                latitude = p.getDouble("latitude"),
                longitude = p.getDouble("longitude"),
                fixTime = parseTime(p.optString("fixTime")) ?: 0L,
                accuracy = p.optDouble("accuracy", 0.0),
                battery = attrs?.takeIf { it.has("batteryLevel") }?.optDouble("batteryLevel")?.toInt(),
                charging = attrs?.takeIf { it.has("charge") }?.optBoolean("charge"),
                ringer = attrs?.optString("ringer")?.takeIf { it.isNotBlank() },
                wifi = attrs?.optString("ssid")?.takeIf { it.isNotBlank() },
                wifiOn = attrs?.takeIf { it.has("wifiOn") }?.let { a -> a.optBoolean("wifiOn") || a.optString("wifiOn") == "true" },
                steps = attrs?.takeIf { it.has("steps") }?.optDouble("steps")?.toInt(),
                activity = attrs?.optString("activity")?.takeIf { it.isNotBlank() }
                    ?: activityFromSpeed(p.optDouble("speed", 0.0) * 1.852),
                kmh = attrs?.takeIf { it.has("kmh") }?.optDouble("kmh") ?: p.optDouble("speed", 0.0) * 1.852,
            )
    }

    private fun parseMember(d: JSONObject, position: MemberPosition?): Member {
            val id = d.getLong("id")
            return Member(
                id = id,
                name = d.optString("name"),
                uniqueId = d.optString("uniqueId"),
                online = d.optString("status") == "online",
                lastUpdate = parseTime(d.optString("lastUpdate")),
                position = position,
                photo = d.optJSONObject("attributes")?.optString("deviceImage")?.takeIf { it.isNotBlank() },
                photoVersion = d.optJSONObject("attributes")?.optLong("kwPhotoVersion") ?: 0L,
                color = d.optJSONObject("attributes")?.optString("kwColor")?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) },
                isParent = d.optJSONObject("attributes")?.optString("kwRole") == "parent",
                stepGoal = d.optJSONObject("attributes")?.optInt("kwStepGoal", DEFAULT_STEP_GOAL)?.takeIf { it > 0 } ?: DEFAULT_STEP_GOAL,
                speedLimitKmh = d.optJSONObject("attributes")?.takeIf { it.has("speedLimit") }?.optDouble("speedLimit")?.let { (it * 1.852).toInt() },
                ghost = d.optJSONObject("attributes")?.optBoolean("kwGhost", false) == true,
                order = d.optJSONObject("attributes")?.takeIf { it.has("kwOrder") }?.optInt("kwOrder"),
                hidden = d.optJSONObject("attributes")?.optBoolean("kwHidden", false) == true,
                cadence = d.optJSONObject("attributes")?.let { a ->
                    val n = a.optInt("kwIntNormal", 0); val m = a.optInt("kwIntMoving", 0); val s = a.optInt("kwIntStill", 0)
                    if (n > 0 && m > 0 && s > 0) Cadence(n, m, s) else null
                },
            )
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
        }
            // Seuls les SOS déclenchent le bandeau rouge (pas la batterie faible ni « bien arrivé »).
            .filter { it.kind.equals("sos", ignoreCase = true) }
            .sortedByDescending { it.time }
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

    // --- Photos des membres ----------------------------------------------------
    //
    // Traccar stocke l'image dans son dossier `media/<uniqueId>/` (POST image),
    // puis l'attribut `deviceImage` de l'appareil la référence (comme le fait
    // l'interface web). La lecture de /api/media exige une session (cookie) :
    // le Bearer n'y est pas accepté, on ouvre donc une session via ?token=.

    private val mediaCookies = mutableListOf<okhttp3.Cookie>()
    private val mediaHttp by lazy {
        http.newBuilder().cookieJar(object : okhttp3.CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {
                synchronized(mediaCookies) {
                    val names = cookies.map { it.name }.toSet()
                    mediaCookies.removeAll { it.name in names }
                    mediaCookies.addAll(cookies)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> = synchronized(mediaCookies) { mediaCookies.toList() }
        }).build()
    }

    /** Envoie une photo (JPEG) pour un membre et la rattache à son appareil. */
    fun uploadPhoto(token: String, id: Long, jpeg: ByteArray) {
        val upload = Request.Builder()
            .url(url("api/devices/$id/image"))
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
        val fileName = authorized(token, upload).use { response ->
            check(response)
            response.body?.string()?.trim().orEmpty().ifBlank { "device.jpeg" }
        }
        updateAttributes(token, id) { attrs ->
            attrs.put("deviceImage", fileName)
            attrs.put("kwPhotoVersion", System.currentTimeMillis())
        }
    }

    /** Marque l'appareil comme celui d'un parent (jamais rendu visible automatiquement). */
    /**
     * Mode fantôme d'un parent, vis-à-vis des AUTRES parents :
     * - attribut kwGhost : les applis des autres parents masquent l'appareil ;
     * - accès retiré aux comptes parents non administrateurs (rétabli à la sortie) ;
     * - appareil détaché des lieux : pas de « X vient d'arriver : Maison » envoyé
     *   aux autres (rattaché de nouveau par la synchro de l'accueil à la sortie).
     * Un compte administrateur voit toujours tout sur le serveur (interface web
     * Traccar) : leur nombre est renvoyé pour prévenir l'utilisateur.
     */
    fun setGhost(token: String, deviceId: Long, ghost: Boolean): GhostResult {
        updateAttributes(token, deviceId) { attrs -> if (ghost) attrs.put("kwGhost", true) else attrs.remove("kwGhost") }
        var admins = 0
        runCatching {
            val sessionUrl = url("api/session").newBuilder().addQueryParameter("token", token).build()
            val me = getObject(token, sessionUrl).getLong("id")
            val users = getArray(token, url("api/users"))
            (0 until users.length()).map { users.getJSONObject(it) }
                .filter { it.getLong("id") != me && it.optJSONObject("attributes")?.optString("kwRole") != "children-viewer" }
                .forEach { u ->
                    if (u.optBoolean("administrator")) {
                        admins++
                    } else {
                        val body = JSONObject().put("userId", u.getLong("id")).put("deviceId", deviceId).toString()
                            .toRequestBody("application/json".toMediaType())
                        val request = Request.Builder().url(url("api/permissions")).let { if (ghost) it.delete(body) else it.post(body) }
                        runCatching { authorized(token, request).close() }
                    }
                }
        }
        if (ghost) runCatching { places(token).forEach { unlinkPlace(token, it.id, listOf(deviceId)) } }
        return GhostResult(admins)
    }

    /**
     * Crée le compte d'un autre parent : non administrateur (le mode fantôme peut
     * alors le masquer côté serveur), en français, avec un mot de passe
     * provisoire lisible. Réservé à un compte administrateur.
     */
    fun createParentAccount(token: String, name: String, email: String): NewParentAccount {
        val password = readablePassword()
        val body = JSONObject()
            .put("name", name.trim())
            .put("email", email.trim())
            .put("password", password)
            .put("administrator", false)
            .put("attributes", JSONObject()
                .put("kwRole", "parent")
                .put("language", "fr")
                .put("timezone", java.util.TimeZone.getDefault().id))
        val request = Request.Builder().url(url("api/users"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        authorized(token, request).use { response ->
            when {
                response.code == 400 && response.body?.string().orEmpty().contains("unique", ignoreCase = true) ->
                    error("Un compte existe déjà avec cet e-mail.")
                response.code == 400 || response.code == 403 ->
                    error("Création refusée : seul un compte administrateur peut créer un compte parent.")
                else -> check(response)
            }
        }
        return NewParentAccount(email.trim(), password)
    }

    /**
     * Partage toute la famille avec les autres parents (comptes non
     * administrateurs, hors compte lecteur des enfants) : appareils et lieux.
     * Lancé par l'appli d'un administrateur à chaque changement ; sans droits
     * administrateur, la liste des comptes est refusée et rien n'est fait.
     * Liens déjà présents : ignorés.
     */
    fun shareFamilyWithParents(token: String, deviceIds: List<Long>, placeIds: List<Long>) {
        val sessionUrl = url("api/session").newBuilder().addQueryParameter("token", token).build()
        val me = getObject(token, sessionUrl).getLong("id")
        val users = getArray(token, url("api/users"))
        (0 until users.length()).map { users.getJSONObject(it) }
            .filter {
                it.getLong("id") != me && !it.optBoolean("administrator") &&
                    it.optJSONObject("attributes")?.optString("kwRole") != "children-viewer"
            }
            .forEach { parent ->
                val userId = parent.getLong("id")
                fun link(key: String, id: Long) {
                    val body = JSONObject().put("userId", userId).put(key, id).toString()
                        .toRequestBody("application/json".toMediaType())
                    runCatching { authorized(token, Request.Builder().url(url("api/permissions")).post(body)).close() }
                }
                deviceIds.forEach { link("deviceId", it) }
                placeIds.forEach { link("geofenceId", it) }
            }
    }

    /** Mot de passe provisoire lisible : 3 groupes de 4 caractères sans ambiguïté (ex. kh7m-p3wa-x9td). */
    private fun readablePassword(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        return (1..3).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
    }

    /** Cadences de suivi d'un membre : appliquées par son téléphone à son prochain réveil. */
    fun setCadence(token: String, id: Long, cadence: Cadence) {
        updateAttributes(token, id) { attrs ->
            attrs.put("kwIntNormal", cadence.normal).put("kwIntMoving", cadence.moving).put("kwIntStill", cadence.still)
        }
    }

    /** Rang d'affichage d'un membre (attribut kwOrder), partagé par toute la famille. */
    fun setOrder(token: String, id: Long, order: Int) {
        updateAttributes(token, id) { attrs -> attrs.put("kwOrder", order) }
    }

    /**
     * Désactive (masque) un membre pour toute la famille : attribut kwHidden.
     * Son téléphone continue d'envoyer ses positions et les alertes restent actives.
     */
    fun setHidden(token: String, id: Long, hidden: Boolean) {
        updateAttributes(token, id) { attrs -> if (hidden) attrs.put("kwHidden", true) else attrs.remove("kwHidden") }
    }

    fun markAsParent(token: String, id: Long) {
        updateAttributes(token, id) { attrs -> attrs.put("kwRole", "parent") }
    }

    /**
     * Les enfants se voient entre eux : chaque appareil d'enfant est lié au compte
     * lecteur. Les appareils des parents ne sont pas touchés (mode fantôme respecté).
     */
    fun shareChildrenWithEachOther(token: String, members: List<Member>) {
        members.filter { !it.isParent }.forEach { setVisibleToChildren(token, it.id, visible = true) }
    }

    // --- Lieux (zones Traccar) --------------------------------------------------------

    /** Lieux circulaires de la famille. Les zones polygonales (créées sur le web) sont ignorées. */
    fun places(token: String): List<Place> {
        val array = getArray(token, url("api/geofences"))
        val circle = Regex("""CIRCLE\s*\(\s*(-?[0-9.]+)\s+(-?[0-9.]+)\s*,\s*([0-9.]+)\s*\)""")
        return (0 until array.length()).mapNotNull { i ->
            val g = array.getJSONObject(i)
            val m = circle.find(g.optString("area")) ?: return@mapNotNull null
            Place(
                id = g.getLong("id"),
                name = g.optString("name"),
                latitude = m.groupValues[1].toDouble(),
                longitude = m.groupValues[2].toDouble(),
                radius = m.groupValues[3].toDouble(),
                icon = g.optJSONObject("attributes")?.optString("kwIcon")?.takeIf { it.isNotBlank() } ?: "other",
                color = g.optJSONObject("attributes")?.optString("kwColor")?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) } ?: "#5B5BD6",
                alerts = g.optJSONObject("attributes")?.let { if (it.has("kwAlerts")) it.optBoolean("kwAlerts") else true } ?: true,
                childIds = g.optJSONObject("attributes")?.optString("kwChildren")?.takeIf { it.isNotBlank() }
                    ?.split(",")?.mapNotNull { it.trim().toLongOrNull() },
                ssid = g.optJSONObject("attributes")?.optString("kwSsid")?.trim()?.takeIf { it.isNotBlank() },
                schedule = g.optJSONObject("attributes")?.let { a ->
                    val days = a.optString("kwDays").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
                    if (days.isEmpty() || g.optLong("calendarId", 0L) == 0L) null
                    else PlaceSchedule(days, a.optInt("kwFrom", 480), a.optInt("kwTo", 1020))
                },
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Crée un lieu et l'applique à tous les appareils (alertes d'entrée / sortie). */
    /**
     * Crée (id = 0) ou modifie un lieu. Avec alertes, il est rattaché à tous les
     * appareils (le serveur génère arrivées / départs) ; sans alertes, il n'est
     * rattaché à aucun : c'est un simple point d'intérêt sur la carte.
     */
    fun savePlace(token: String, place: Place, deviceIds: List<Long>): Long {
        val area = String.format(java.util.Locale.US, "CIRCLE (%.6f %.6f, %d)", place.latitude, place.longitude, place.radius.toInt())
        val id = if (place.id <= 0) {
            val body = JSONObject().put("name", place.name.trim()).put("area", area)
                .put("attributes", JSONObject().put("kwIcon", place.icon).put("kwColor", place.color).put("kwAlerts", place.alerts))
            val create = Request.Builder().url(url("api/geofences"))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
            authorized(token, create).use { response ->
                check(response)
                JSONObject(response.body?.string().orEmpty()).getLong("id")
            }
        } else {
            place.id
        }
        // Objet complet relu puis renvoyé (Traccar refuse un PUT partiel) : attributs,
        // enfants concernés et calendrier des horaires.
        val g = getObject(token, url("api/geofences/$id"))
        val attrs = g.optJSONObject("attributes") ?: JSONObject()
        attrs.put("kwIcon", place.icon).put("kwColor", place.color).put("kwAlerts", place.alerts)
        if (place.childIds.isNullOrEmpty()) attrs.remove("kwChildren") else attrs.put("kwChildren", place.childIds.joinToString(","))
        if (place.ssid.isNullOrBlank()) attrs.remove("kwSsid") else attrs.put("kwSsid", place.ssid.trim())
        val oldCalendar = g.optLong("calendarId", 0L)
        val schedule = place.schedule?.takeIf { place.alerts && it.days.isNotEmpty() }
        val calendarId = if (schedule != null) {
            attrs.put("kwDays", schedule.days.sorted().joinToString(",")).put("kwFrom", schedule.fromMinutes).put("kwTo", schedule.toMinutes)
            saveCalendar(token, oldCalendar, "KinWaypoint · ${place.name.trim()}", schedule.toICalendar(id, place.name.trim()))
        } else {
            attrs.remove("kwDays"); attrs.remove("kwFrom"); attrs.remove("kwTo")
            0L
        }
        g.put("name", place.name.trim()).put("area", area).put("attributes", attrs).put("calendarId", calendarId)
        putObject(token, url("api/geofences/$id"), g.toString())
        if (oldCalendar != 0L && calendarId == 0L) {
            runCatching { authorized(token, Request.Builder().url(url("api/calendars/$oldCalendar")).delete()).close() }
        }
        // Alertes : seulement pour les appareils concernés ; les autres sont détachés.
        val targets = place.targets(deviceIds)
        if (place.alerts) linkPlace(token, id, targets) else unlinkPlace(token, id, deviceIds)
        if (place.alerts) unlinkPlace(token, id, deviceIds - targets.toSet())
        return id
    }

    /** Crée ou met à jour un calendrier Traccar (contenu iCalendar en base64). */
    private fun saveCalendar(token: String, existingId: Long, name: String, ics: String): Long {
        val data = android.util.Base64.encodeToString(ics.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        if (existingId != 0L) {
            val updated = runCatching {
                val c = getObject(token, url("api/calendars/$existingId"))
                c.put("name", name).put("data", data)
                putObject(token, url("api/calendars/$existingId"), c.toString())
            }
            if (updated.isSuccess) return existingId
        }
        val body = JSONObject().put("name", name).put("data", data)
        val create = Request.Builder().url(url("api/calendars"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        return authorized(token, create).use { response ->
            check(response)
            JSONObject(response.body?.string().orEmpty()).getLong("id")
        }
    }

    /** Détache un lieu des appareils : plus d'alertes d'arrivée / départ. */
    fun unlinkPlace(token: String, placeId: Long, deviceIds: List<Long>) {
        deviceIds.forEach { deviceId ->
            val body = JSONObject().put("deviceId", deviceId).put("geofenceId", placeId).toString()
                .toRequestBody("application/json".toMediaType())
            runCatching { authorized(token, Request.Builder().url(url("api/permissions")).delete(body)).close() }
        }
    }

    fun deletePlace(token: String, id: Long) {
        authorized(token, Request.Builder().url(url("api/geofences/$id")).delete()).use { check(it) }
    }

    /** Rattache un lieu à des appareils (déjà rattaché : ignoré). */
    fun linkPlace(token: String, placeId: Long, deviceIds: List<Long>) {
        deviceIds.forEach { deviceId ->
            val body = JSONObject().put("deviceId", deviceId).put("geofenceId", placeId).toString()
                .toRequestBody("application/json".toMediaType())
            runCatching { authorized(token, Request.Builder().url(url("api/permissions")).post(body)).close() }
        }
    }

    /** Arrivées, départs et SOS des appareils donnés, du plus récent au plus ancien. */
    fun events(token: String, deviceIds: List<Long>, from: Long, to: Long): List<FamilyEvent> {
        if (deviceIds.isEmpty()) return emptyList()
        val builder = url("api/reports/events").newBuilder()
            .addQueryParameter("from", iso(from))
            .addQueryParameter("to", iso(to))
        listOf("geofenceEnter", "geofenceExit", "alarm", "deviceUnknown", "deviceOverspeed", "proximityEnter").forEach { builder.addQueryParameter("type", it) }
        deviceIds.forEach { builder.addQueryParameter("deviceId", it.toString()) }
        val array = getArray(token, builder.build())
        return (0 until array.length()).map { i ->
            val e = array.getJSONObject(i)
            FamilyEvent(
                id = e.getLong("id"),
                deviceId = e.getLong("deviceId"),
                type = e.optString("type"),
                time = parseTime(e.optString("eventTime")) ?: 0L,
                placeId = e.optLong("geofenceId", 0L),
                alarm = e.optJSONObject("attributes")?.optString("alarm").orEmpty(),
            )
        }.sortedByDescending { it.time }
    }

    /** Objectif de pas quotidien d'un membre, partagé par tous les parents. */
    fun setStepGoal(token: String, id: Long, goal: Int) {
        updateAttributes(token, id) { attrs -> attrs.put("kwStepGoal", goal) }
    }

    // --- Commandes à distance -------------------------------------------------------

    /**
     * Fait sonner le téléphone : la commande « ring » est mise en attente sur le
     * serveur, puis le téléphone est réveillé par Firebase pour envoyer sa position
     * — la réponse lui transmet « ring ». Renvoie false si le réveil immédiat est
     * impossible (téléphone pas encore inscrit) : il sonnera à son prochain envoi.
     */
    fun ring(token: String, deviceId: Long): Boolean {
        // Demande posée sur l'appareil (kwRing) : lue par le téléphone à son réveil.
        // Traccar envoie TOUTES les commandes par Firebase dès que le téléphone a un
        // jeton Firebase, sans leur contenu : une commande « ring » seule n'arrivait jamais.
        updateAttributes(token, deviceId) { attrs -> attrs.put("kwRing", System.currentTimeMillis()) }
        // Serveur sans Firebase : la commande attend dans la file et part avec la prochaine position.
        runCatching { sendCommand(token, JSONObject().put("deviceId", deviceId).put("type", "custom").put("attributes", JSONObject().put("data", "ring"))) }
        return locateNow(token, deviceId)
    }

    /**
     * Arrête à distance une sonnerie en cours : attribut kwRingStop (horodaté),
     * lu par le téléphone à son réveil, comme kwRing. Commande « ringStop » en
     * secours pour un serveur sans Firebase.
     */
    fun stopRing(token: String, deviceId: Long): Boolean {
        updateAttributes(token, deviceId) { attrs -> attrs.put("kwRingStop", System.currentTimeMillis()) }
        runCatching { sendCommand(token, JSONObject().put("deviceId", deviceId).put("type", "custom").put("attributes", JSONObject().put("data", "ringStop"))) }
        return locateNow(token, deviceId)
    }

    /** Demandes en attente pour CET appareil (sonnerie, écoute), lues dans ses attributs. */
    fun pendingActions(token: String, uniqueId: String): JSONObject? = ownDevice(token, uniqueId)?.optJSONObject("attributes")

    /** L'appareil de CE téléphone (nom, attributs), vu avec le jeton parent ou lecteur. */
    fun ownDevice(token: String, uniqueId: String): JSONObject? {
        val devices = getArray(token, url("api/devices"))
        return (0 until devices.length()).map { devices.getJSONObject(it) }
            .firstOrNull { it.optString("uniqueId") == uniqueId }
    }

    /**
     * Écoute en direct : la commande (salle + secret) attend sur le serveur, puis
     * le téléphone est réveillé par Firebase ; la réponse à sa position la lui
     * transmet. false si le réveil immédiat est impossible.
     */
    fun startListen(token: String, deviceId: Long, room: String, secret: String): Boolean {
        val command = AudioRelay.command(room, secret)
        // Même raison que pour la sonnerie : la demande passe par l'attribut kwListen
        // (horodatée, ignorée après 2 min), la commande en file sert de secours.
        updateAttributes(token, deviceId) { attrs -> attrs.put("kwListen", command) }
        runCatching {
            sendCommand(
                token,
                JSONObject().put("deviceId", deviceId).put("type", "custom")
                    .put("attributes", JSONObject().put("data", command)),
            )
        }
        return locateNow(token, deviceId)
    }

    /** Demande une position tout de suite (réveil Firebase). false si le téléphone n'est pas joignable ainsi. */
    fun locateNow(token: String, deviceId: Long): Boolean = runCatching {
        sendCommand(token, JSONObject().put("deviceId", deviceId).put("type", "positionSingle"))
    }.isSuccess

    private fun sendCommand(token: String, command: JSONObject) {
        val request = Request.Builder().url(url("api/commands/send"))
            .post(command.toString().toRequestBody("application/json".toMediaType()))
        authorized(token, request).use { check(it) }
    }

    // --- Alertes (règles de notification Traccar) --------------------------------------

    /** Règles déjà créées par l'appli (attribut kwRule), par clé. */
    fun alertRules(token: String): Map<String, Long> {
        val array = getArray(token, url("api/notifications"))
        return (0 until array.length()).map { array.getJSONObject(it) }
            .mapNotNull { n -> n.optJSONObject("attributes")?.optString("kwRule")?.takeIf { it.isNotBlank() }?.let { it to n.getLong("id") } }
            .toMap()
    }

    /** Active ou désactive une alerte pour tous les appareils du compte (notifications push + Telegram). */
    fun setAlertRule(token: String, rule: AlertRule, enabled: Boolean, notificators: String = "firebase,telegram,web") {
        val existing = alertRules(token)[rule.key]
        if (enabled && existing == null) {
            val attrs = JSONObject().put("kwRule", rule.key)
            rule.alarms?.let { attrs.put("alarms", it) }
            val body = JSONObject().put("type", rule.type).put("always", true).put("notificators", notificators).put("attributes", attrs)
            val request = Request.Builder().url(url("api/notifications"))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
            authorized(token, request).use { check(it) }
        } else if (!enabled && existing != null) {
            authorized(token, Request.Builder().url(url("api/notifications/$existing")).delete()).use { check(it) }
        }
    }

    /**
     * Alerte de proximité calculée par Traccar : chaque enfant est lié au téléphone
     * du parent, avec une distance d'entrée (attribut proximityEnterDistance).
     * Quand une position de l'enfant passe de « plus loin » à « plus près » que
     * cette distance, Traccar crée un événement proximityEnter → notification
     * Firebase, même appli fermée. meters = 0 : désactivé.
     */
    fun setProximity(token: String, childIds: List<Long>, parentDeviceId: Long, meters: Int) {
        childIds.forEach { child ->
            updateAttributes(token, child) { attrs ->
                if (meters > 0) attrs.put("proximityEnterDistance", meters.toDouble()) else attrs.remove("proximityEnterDistance")
            }
            if (meters > 0) {
                val body = JSONObject().put("deviceId", child).put("linkedDeviceId", parentDeviceId).toString()
                val request = Request.Builder().url(url("api/permissions"))
                    .post(body.toRequestBody("application/json".toMediaType()))
                authorized(token, request).use { response ->
                    // Lien déjà présent : l'état voulu est atteint.
                    if (!response.isSuccessful && response.code != 400) check(response)
                }
            }
        }
    }

    /**
     * Notifications en français et à l'heure du téléphone : attributs language
     * et timezone du compte, lus par Traccar pour choisir les textes et formater
     * l'heure. Ne réécrit le compte que si quelque chose change.
     */
    fun ensureUserPreferences(token: String, timezone: String = java.util.TimeZone.getDefault().id) {
        val sessionUrl = url("api/session").newBuilder().addQueryParameter("token", token).build()
        val userId = getObject(token, sessionUrl).getLong("id")
        val user = getObject(token, url("api/users/$userId"))
        val attributes = user.optJSONObject("attributes") ?: JSONObject()
        if (attributes.optString("language") == "fr" && attributes.optString("timezone") == timezone) return
        attributes.put("language", "fr")
        attributes.put("timezone", timezone)
        user.put("attributes", attributes)
        putObject(token, url("api/users/$userId"), user.toString())
    }

    // --- SMS de secours (SOS) ------------------------------------------------------

    /** Compte connecté (objet utilisateur complet). */
    private fun ownUser(token: String): JSONObject {
        val sessionUrl = url("api/session").newBuilder().addQueryParameter("token", token).build()
        val userId = getObject(token, sessionUrl).getLong("id")
        return getObject(token, url("api/users/$userId"))
    }

    /** Numéro du parent connecté pour les SMS de SOS (attribut kwPhone du compte), ou "". */
    fun ownPhone(token: String): String =
        ownUser(token).optJSONObject("attributes")?.optString("kwPhone").orEmpty()

    /** Enregistre (ou efface, "") le numéro du parent connecté. PUT complet du compte. */
    fun setOwnPhone(token: String, phone: String) {
        val user = ownUser(token)
        val attributes = user.optJSONObject("attributes") ?: JSONObject()
        if (phone.isBlank()) attributes.remove("kwPhone") else attributes.put("kwPhone", phone)
        user.put("attributes", attributes)
        putObject(token, url("api/users/${user.getLong("id")}"), user.toString())
    }

    /**
     * Inscrit le numéro du parent connecté sur chaque téléphone d'enfant
     * (attribut kwSosPhones : {"idCompte": "numéro"}), où l'appli enfant le
     * lit et le garde pour envoyer un SMS au moment d'un SOS, même sans
     * Internet. Chaque parent ne touche que sa propre entrée.
     */
    fun shareSosPhone(token: String, childIds: List<Long>) {
        val user = ownUser(token)
        val userId = user.getLong("id").toString()
        val phone = user.optJSONObject("attributes")?.optString("kwPhone").orEmpty()
        childIds.forEach { child ->
            val current = runCatching {
                authorized(token, Request.Builder().url(url("api/devices/$child")).get()).use { response ->
                    check(response)
                    JSONObject(response.body?.string().orEmpty()).optJSONObject("attributes")?.optString("kwSosPhones")
                }
            }.getOrNull().orEmpty()
            val map = runCatching { JSONObject(current) }.getOrDefault(JSONObject())
            val wanted = phone.takeIf { it.isNotBlank() }
            if (map.optString(userId).ifBlank { null } == wanted) return@forEach // déjà à jour
            if (wanted == null) map.remove(userId) else map.put(userId, wanted)
            updateAttributes(token, child) { attrs ->
                if (map.length() == 0) attrs.remove("kwSosPhones") else attrs.put("kwSosPhones", map.toString())
            }
        }
    }

    /** Limite de vitesse d'un appareil (km/h) : au-delà, Traccar génère « excès de vitesse ». */
    fun setSpeedLimit(token: String, id: Long, kmh: Int?) {
        updateAttributes(token, id) { attrs ->
            if (kmh == null) attrs.remove("speedLimit") else attrs.put("speedLimit", kmh / 1.852) // Traccar : nœuds
        }
    }

    /** Les lieux sont visibles des enfants (carte en lecture seule). */
    fun sharePlacesWithChildren(token: String, placeIds: List<Long>) {
        val viewerId = runCatching { ensureChildViewer(token) }.getOrNull() ?: return
        placeIds.forEach { placeId ->
            val body = JSONObject().put("userId", viewerId).put("geofenceId", placeId).toString()
                .toRequestBody("application/json".toMediaType())
            runCatching { authorized(token, Request.Builder().url(url("api/permissions")).post(body)).close() }
        }
    }

    /** Couleur du tracé d'un membre, partagée par tous les parents (attribut serveur). */
    fun setColor(token: String, id: Long, color: String) {
        updateAttributes(token, id) { attrs -> attrs.put("kwColor", color) }
    }

    /** Positions d'un appareil entre deux instants, dans l'ordre chronologique. */
    fun positions(token: String, deviceId: Long, from: Long, to: Long): List<TracePoint> {
        val url = url("api/positions").newBuilder()
            .addQueryParameter("deviceId", deviceId.toString())
            .addQueryParameter("from", iso(from))
            .addQueryParameter("to", iso(to))
            .build()
        val array = getArray(token, url)
        return (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            val attrs = p.optJSONObject("attributes")
            TracePoint(
                latitude = p.getDouble("latitude"),
                longitude = p.getDouble("longitude"),
                time = parseTime(p.optString("fixTime")) ?: 0L,
                speedKmh = p.optDouble("speed", 0.0) * 1.852, // Traccar : nœuds
                accuracy = p.optDouble("accuracy", 0.0),
                battery = attrs?.takeIf { it.has("batteryLevel") }?.optDouble("batteryLevel")?.toInt(),
                steps = attrs?.takeIf { it.has("steps") }?.optDouble("steps")?.toInt(),
                activity = attrs?.optString("activity")?.takeIf { it.isNotBlank() },
                charging = attrs?.takeIf { it.has("charge") }?.optBoolean("charge"),
            )
        }.filter { it.time > 0 }.sortedBy { it.time }
    }

    // --- Compte « Enfants (lecture seule) » -----------------------------------------
    //
    // Les téléphones des enfants lisent la position des parents avec ce compte :
    // il ne voit QUE les appareils qui lui sont liés (ceux des parents qui
    // partagent leur position). Mode fantôme = lien retiré, appliqué par le serveur.

    /** Id du compte lecteur, créé s'il n'existe pas encore. */
    fun ensureChildViewer(token: String): Long {
        findChildViewer(token)?.let { return it.getLong("id") }
        val body = JSONObject()
            .put("name", "Enfants (lecture seule)")
            .put("email", CHILD_VIEWER_EMAIL)
            .put("password", randomSecret())
            .put("readonly", true)
            .put("deviceReadonly", true)
            .put("attributes", JSONObject().put("kwRole", "children-viewer"))
        val create = Request.Builder().url(url("api/users"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        return authorized(token, create).use { response ->
            check(response)
            JSONObject(response.body?.string().orEmpty()).getLong("id")
        }
    }

    /**
     * Jeton du compte lecteur (valable un an), à mettre dans les QR codes enfants.
     * Le mot de passe du compte est remplacé par un secret aléatoire jamais conservé,
     * puis utilisé une seule fois pour ouvrir une session et obtenir le jeton.
     */
    fun childViewerToken(token: String): Pair<String, Long> {
        val viewerId = ensureChildViewer(token)
        val password = randomSecret()
        val user = authorized(token, Request.Builder().url(url("api/users/$viewerId")).get()).use { response ->
            check(response)
            JSONObject(response.body?.string().orEmpty())
        }
        user.put("password", password)
        val put = Request.Builder().url(url("api/users/$viewerId"))
            .put(user.toString().toRequestBody("application/json".toMediaType()))
        authorized(token, put).use { check(it) }
        val expires = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
        return login(user.getString("email"), password, expires) to expires
    }

    /** L'appareil est-il visible des enfants ? (= lié au compte lecteur) */
    fun isVisibleToChildren(token: String, deviceId: Long): Boolean {
        val viewer = findChildViewer(token) ?: return false
        val url = url("api/devices").newBuilder().addQueryParameter("userId", viewer.getLong("id").toString()).build()
        val devices = getArray(token, url)
        return (0 until devices.length()).any { devices.getJSONObject(it).getLong("id") == deviceId }
    }

    /** Rend l'appareil visible (true) ou invisible (false, mode fantôme) pour les enfants. */
    fun setVisibleToChildren(token: String, deviceId: Long, visible: Boolean) {
        val viewerId = ensureChildViewer(token)
        val body = JSONObject().put("userId", viewerId).put("deviceId", deviceId).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url("api/permissions"))
            .let { if (visible) it.post(body) else it.delete(body) }
        authorized(token, request).use { response ->
            // Lien déjà présent / déjà absent : l'état voulu est atteint.
            if (!response.isSuccessful && response.code != 400) check(response)
        }
    }

    private fun findChildViewer(token: String): JSONObject? {
        val users = getArray(token, url("api/users"))
        return (0 until users.length()).map { users.getJSONObject(it) }
            .firstOrNull { it.optString("email") == CHILD_VIEWER_EMAIL }
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    }

    /** Retire la photo d'un membre (l'initiale réapparaît). */
    fun removePhoto(token: String, id: Long) {
        updateAttributes(token, id) { attrs ->
            attrs.remove("deviceImage")
            attrs.remove("kwPhotoVersion")
        }
    }

    /** Télécharge une photo de membre. */
    fun fetchPhoto(token: String, uniqueId: String, fileName: String): ByteArray {
        val mediaUrl = base.newBuilder().addPathSegments("api/media").addPathSegment(uniqueId).addPathSegment(fileName).build()
        repeat(2) { attempt ->
            mediaHttp.newCall(Request.Builder().url(mediaUrl).get().build()).execute().use { response ->
                if (response.isSuccessful) return response.body?.bytes() ?: ByteArray(0)
                if (response.code != 401 || attempt == 1) error("Photo indisponible (HTTP ${response.code})")
            }
            // Pas (ou plus) de session : on en ouvre une avec le jeton, puis on réessaie.
            val session = url("api/session").newBuilder().addQueryParameter("token", token).build()
            mediaHttp.newCall(Request.Builder().url(session).get().build()).execute().use { response ->
                if (response.code == 401) throw AuthException("Session expirée, reconnecte-toi")
            }
        }
        error("Photo indisponible")
    }

    /** Relit l'appareil complet, modifie ses attributs, le renvoie (PUT partiel refusé). */
    private fun updateAttributes(token: String, id: Long, change: (JSONObject) -> Unit) {
        val device = authorized(token, Request.Builder().url(url("api/devices/$id")).get()).use { response ->
            check(response)
            JSONObject(response.body?.string().orEmpty())
        }
        val attrs = device.optJSONObject("attributes") ?: JSONObject()
        change(attrs)
        device.put("attributes", attrs)
        val put = Request.Builder()
            .url(url("api/devices/$id"))
            .put(device.toString().toRequestBody("application/json".toMediaType()))
        authorized(token, put).use { check(it) }
    }

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
