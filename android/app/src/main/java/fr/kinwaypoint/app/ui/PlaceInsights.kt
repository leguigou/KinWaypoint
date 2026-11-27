package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.PlaceSchedule
import fr.kinwaypoint.app.net.TraccarApi
import fr.kinwaypoint.app.net.TracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

// --- Statistiques d'un lieu -------------------------------------------------------------

/** Passages d'une personne dans un lieu sur la période. */
data class PlaceVisitStats(
    val memberId: Long,
    val visits: Int,
    val totalMs: Long,
    /** Dernier point vu dans le lieu, ou null si jamais venu sur la période. */
    val lastSeen: Long?,
    /** Encore dans le lieu (dernier point dedans, récent). */
    val insideNow: Boolean,
)

private fun distance(a: TracePoint, lat: Double, lon: Double): Float {
    val r = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, lat, lon, r)
    return r[0]
}

/**
 * Visites d'un lieu : suites de points dans le rayon (petite tolérance pour la
 * précision). Une visite compte à partir de 3 min ; les trous de plus de 30 min
 * (téléphone éteint) ne sont pas comptés dans le temps passé.
 */
fun placeStats(memberId: Long, points: List<TracePoint>, place: Place, now: Long = System.currentTimeMillis()): PlaceVisitStats {
    val sorted = points.sortedBy { it.time }
    var visits = 0
    var total = 0L
    var last: Long? = null
    var runStart: Long? = null
    var runLast = 0L
    var runMs = 0L

    fun inside(p: TracePoint) = distance(p, place.latitude, place.longitude) <= place.radius + minOf(p.accuracy, 50.0)

    fun closeRun(next: Long?) {
        runStart ?: return
        // Après le dernier point dedans, on compte jusqu'au point suivant (10 min au plus).
        val tail = ((next ?: now) - runLast).coerceIn(0L, 10 * MINUTE)
        val duration = runMs + tail
        if (duration >= 3 * MINUTE) {
            visits++
            total += duration
        }
        runStart = null
    }

    for (p in sorted) {
        if (inside(p)) {
            last = p.time
            if (runStart == null) {
                runStart = p.time
                runMs = 0L
            } else {
                val gap = p.time - runLast
                when {
                    gap > 3 * HOUR -> { closeRun(p.time); runStart = p.time; runMs = 0L }
                    gap <= 30 * MINUTE -> runMs += gap
                }
            }
            runLast = p.time
        } else if (runStart != null) {
            closeRun(p.time)
        }
    }
    val insideNow = runStart != null && now - runLast < 30 * MINUTE
    closeRun(null)
    return PlaceVisitStats(memberId, visits, total, last, insideNow)
}

/** « 46 h », « 6 h 20 », « 25 min ». */
fun durationText(ms: Long): String {
    val minutes = ms / MINUTE
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 10 * 60 -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
        else -> "${minutes / 60} h"
    }
}

// --- Horaires -------------------------------------------------------------------------

private val DayShort = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")

fun hhmmMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** « lun–ven 08:00–17:00 », « week-end 20:00–07:00 »… */
fun PlaceSchedule.label(): String {
    val d = when (days) {
        (1..5).toSet() -> "lun–ven"
        setOf(6, 7) -> "week-end"
        (1..7).toSet() -> "tous les jours"
        else -> days.sorted().joinToString(", ") { DayShort[it - 1] }
    }
    return "$d ${hhmmMinutes(fromMinutes)}–${hhmmMinutes(toMinutes)}"
}

// --- Lieux fréquents ------------------------------------------------------------------

/** Lieu où la famille passe souvent du temps et qui n'est pas encore enregistré. */
data class PlaceSuggestion(
    val latitude: Double,
    val longitude: Double,
    val icon: String,
    val name: String,
    val visits: Int,
    val days: Int,
    val totalMs: Long,
    val hint: String,
)

private data class Stop(val lat: Double, val lon: Double, val start: Long, val end: Long)

/** Arrêts d'au moins 20 min (points à moins de 80 m d'un même point de départ). */
private fun stops(points: List<TracePoint>): List<Stop> {
    val sorted = points.sortedBy { it.time }
    val out = ArrayList<Stop>()
    var i = 0
    while (i < sorted.size) {
        val anchor = sorted[i]
        var j = i
        while (j + 1 < sorted.size &&
            distance(sorted[j + 1], anchor.latitude, anchor.longitude) <= 80f &&
            sorted[j + 1].time - sorted[j].time <= 3 * HOUR
        ) j++
        if (sorted[j].time - anchor.time >= 20 * MINUTE) {
            val run = sorted.subList(i, j + 1)
            out += Stop(run.map { it.latitude }.average(), run.map { it.longitude }.average(), anchor.time, sorted[j].time)
        }
        i = j + 1
    }
    return out
}

/**
 * Lieux fréquents : arrêts regroupés à moins de 150 m, vus au moins deux jours
 * différents et 2 h au total, loin des lieux déjà enregistrés. Nom proposé selon
 * les heures : surtout la nuit → Maison, en semaine de jour → École.
 */
fun suggestPlaces(tracks: Collection<List<TracePoint>>, places: List<Place>, zone: ZoneId = ZoneId.systemDefault()): List<PlaceSuggestion> {
    class Cluster(var lat: Double, var lon: Double) {
        var weight = 0.0
        var total = 0L
        var visits = 0
        var night = 0L
        var school = 0L
        val days = HashSet<java.time.LocalDate>()
    }
    val clusters = ArrayList<Cluster>()
    tracks.flatMap { stops(it) }.forEach { s ->
        val r = FloatArray(1)
        val c = clusters.firstOrNull { c ->
            Location.distanceBetween(c.lat, c.lon, s.lat, s.lon, r); r[0] <= 150f
        } ?: Cluster(s.lat, s.lon).also { clusters += it }
        val duration = (s.end - s.start).toDouble()
        c.lat = (c.lat * c.weight + s.lat * duration) / (c.weight + duration)
        c.lon = (c.lon * c.weight + s.lon * duration) / (c.weight + duration)
        c.weight += duration
        c.total += s.end - s.start
        c.visits++
        // Répartition par quart d'heure : nuit (21 h–7 h) et journée de semaine (8 h–17 h).
        var t = s.start
        while (t < s.end) {
            val local = Instant.ofEpochMilli(t).atZone(zone)
            c.days += local.toLocalDate()
            val h = local.hour
            if (h >= 21 || h < 7) c.night += 15 * MINUTE
            if (local.dayOfWeek.value <= 5 && h in 8..16) c.school += 15 * MINUTE
            t += 15 * MINUTE
        }
    }
    return clusters
        .filter { c ->
            c.days.size >= 2 && c.total >= 2 * HOUR && places.none { p ->
                val r = FloatArray(1)
                Location.distanceBetween(p.latitude, p.longitude, c.lat, c.lon, r)
                r[0] <= p.radius + 150
            }
        }
        .sortedByDescending { it.total }
        .take(3)
        .map { c ->
            val nightShare = c.night.toDouble() / c.total.coerceAtLeast(1)
            val schoolShare = c.school.toDouble() / c.total.coerceAtLeast(1)
            val (icon, name, hint) = when {
                nightShare >= 0.4 -> Triple("home", "Maison", "surtout la nuit")
                schoolShare >= 0.5 -> Triple("school", "École", "en semaine, en journée")
                else -> Triple("other", "Lieu fréquent", "souvent")
            }
            PlaceSuggestion(c.lat, c.lon, icon, name, c.visits, c.days.size, c.total, hint)
        }
}

// --- Fiche d'un lieu ------------------------------------------------------------------

/**
 * Fiche d'un lieu : dernier passage, nombre de visites et temps cumulé, par
 * personne, sur 7 ou 30 jours (calculés à partir des positions enregistrées).
 */
@Composable
fun PlaceDetailScreen(
    api: TraccarApi,
    token: String,
    place: Place,
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    onEdit: () -> Unit,
    onShowOnMap: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var days by remember { mutableIntStateOf(7) }
    var stats by remember(place.id, days) { mutableStateOf<List<PlaceVisitStats>?>(null) }
    val color = parseHex(place.color) ?: MaterialTheme.colorScheme.primary

    LaunchedEffect(place.id, days, members.map { it.id }) {
        val now = System.currentTimeMillis()
        val from = now - days * 24 * HOUR
        stats = withContext(Dispatchers.IO) {
            members.map { m ->
                val points = runCatching { api.positions(token, m.id, from, now) }.getOrDefault(emptyList())
                placeStats(m.id, points, place, now)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
            Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(color), contentAlignment = Alignment.Center) {
                Text(placeEmoji(place.icon), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOfNotNull(
                        "Rayon ${place.radius.toInt()} m",
                        if (place.alerts) place.schedule?.label() ?: "alertes tout le temps" else "point d'intérêt",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to "7 derniers jours", 30 to "30 derniers jours").forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
            }

            val current = stats
            if (current == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                // Résumé de la famille.
                item {
                    val lastAll = current.mapNotNull { it.lastSeen }.maxOrNull()
                    val here = current.filter { it.insideNow }.mapNotNull { s -> members.firstOrNull { it.id == s.memberId }?.name }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryTile("🔁", current.sumOf { it.visits }.toString(), "visites", Modifier.weight(1f))
                        SummaryTile("⏱️", durationText(current.sumOf { it.totalMs }), "cumulé", Modifier.weight(1f))
                        SummaryTile(
                            "🕒",
                            when {
                                here.isNotEmpty() -> "Maintenant"
                                lastAll != null -> relativeTime(lastAll)
                                else -> "—"
                            },
                            "dernier passage",
                            Modifier.weight(1f),
                        )
                    }
                }
                items(current.sortedByDescending { it.lastSeen ?: 0L }, key = { it.memberId }) { s ->
                    val m = members.firstOrNull { it.id == s.memberId } ?: return@items
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(m.name, size = 44.dp, photo = photos[m.id], colorHex = m.color)
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        s.insideNow -> "Ici en ce moment"
                                        s.lastSeen != null -> "Dernier passage ${relativeTime(s.lastSeen)} · ${formatDateTime(s.lastSeen)}"
                                        else -> "Pas venu sur la période"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.insideNow) AppTheme.colors.success else Muted,
                                )
                            }
                        }
                        if (s.visits > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill("${s.visits} visite${if (s.visits > 1) "s" else ""}", color, showDot = false)
                                StatusPill(durationText(s.totalMs), color, showDot = false)
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Calculé à partir des positions enregistrées : une visite compte à partir de 3 minutes sur place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onShowOnMap, shape = CircleShape, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Sur la carte")
                    }
                    Button(onClick = onEdit, shape = CircleShape, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Modifier")
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
