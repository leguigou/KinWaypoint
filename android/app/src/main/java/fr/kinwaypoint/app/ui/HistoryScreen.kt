package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TraccarApi
import fr.kinwaypoint.app.net.TracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Durée de conservation côté serveur (rétention TimescaleDB). */
private const val RETENTION_DAYS = 30L

/** Une ligne de la chronologie : un arrêt, ou un trajet entre deux arrêts. */
private sealed interface HistoryRow {
    val memberId: Long
    /** Point mis en avant sur la carte quand on touche la ligne. */
    val point: TracePoint
    val start: Long
    val end: Long

    data class Stop(override val memberId: Long, override val point: TracePoint, override val end: Long) : HistoryRow {
        override val start get() = point.time
    }

    data class Trip(override val memberId: Long, val points: List<TracePoint>) : HistoryRow {
        override val point get() = points.first()
        override val start get() = points.first().time
        override val end get() = points.last().time
        val distance: Float get() = traceLength(cleanTrace(points))
        /** Vitesse moyenne en km/h, sur la durée du trajet. */
        val averageKmh: Double get() = if (end > start) distance / 1000.0 / ((end - start) / 3_600_000.0) else 0.0
        /** Vitesse maximale mesurée pendant le trajet (km/h), ou null. */
        val maxKmh: Double? get() = points.maxOfOrNull { it.speedKmh }?.takeIf { it >= 1 }
    }
}

/** Mode de déplacement déduit de la vitesse moyenne (estimation, sans capteur). */
private fun tripMode(kmh: Double): Pair<String, String> = when {
    kmh <= 15 -> "🚶" to "Marche"
    else -> "🚗" to "Voiture"
}

/**
 * Découpe la journée : plusieurs points à moins de 60 m pendant 3 min ou plus
 * forment un « arrêt » ; ce qu'il y a entre deux arrêts forme un « trajet ».
 */
private fun buildRows(memberId: Long, points: List<TracePoint>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    val result = FloatArray(1)
    val moving = mutableListOf<TracePoint>()

    fun closeTrip(next: TracePoint?) {
        val tripPoints = buildList {
            (rows.lastOrNull() as? HistoryRow.Stop)?.let { add(it.point) }
            addAll(moving)
            next?.let { add(it) }
        }
        if (tripPoints.size >= 2 && traceLength(tripPoints) >= 50f) rows += HistoryRow.Trip(memberId, tripPoints)
        moving.clear()
    }

    var i = 0
    while (i < points.size) {
        val anchor = points[i]
        var j = i
        while (j + 1 < points.size) {
            val next = points[j + 1]
            Location.distanceBetween(anchor.latitude, anchor.longitude, next.latitude, next.longitude, result)
            if (result[0] > 60f) break
            j++
        }
        if (j > i && points[j].time - anchor.time >= 3 * 60_000L) {
            closeTrip(anchor)
            rows += HistoryRow.Stop(memberId, anchor, points[j].time)
            i = j + 1
        } else {
            moving += anchor
            i++
        }
    }
    closeTrip(null)
    return rows
}

/**
 * Historique jour par jour : tracés colorés sur la carte, liste chronologique des
 * passages et des arrêts. Le jour en cours se met à jour toutes les minutes.
 */
@Composable
fun HistoryScreen(
    api: TraccarApi,
    token: String,
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    ownDeviceId: Long,
    initialFocus: Long?,
    onBack: () -> Unit,
    places: List<fr.kinwaypoint.app.net.Place> = emptyList(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var day by remember { mutableStateOf(today) }
    var focus by remember { mutableStateOf(initialFocus) } // null = tout le monde
    var traces by remember { mutableStateOf<Map<Long, List<TracePoint>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var highlighted by remember { mutableStateOf<HistoryRow?>(null) }
    val state = remember { KinMapState() }

    BackHandler(onBack = onBack)

    fun nameOf(m: Member) = if (m.id == ownDeviceId) "Moi" else m.name
    val colors: Map<Long, Int> = members.associate { it.id to profileColor(it.name, it.color).toArgb() }

    // Chargement du jour choisi (et rafraîchissement toutes les minutes si c'est aujourd'hui).
    LaunchedEffect(day, members.map { it.id }) {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        while (true) {
            val result = withContext(Dispatchers.IO) {
                runCatching { members.associate { m -> m.id to api.positions(token, m.id, from, to) } }
            }
            result.onSuccess { traces = it; error = null }.onFailure { error = it.message ?: "Historique indisponible" }
            loading = false
            if (day != today) break
            delay(60_000)
        }
    }

    val visible = if (focus == null) traces else traces.filterKeys { it == focus }
    val rows = remember(visible) {
        visible.flatMap { (id, points) -> buildRows(id, points) }.sortedByDescending { it.start }
    }

    // Rejouer la journée d'une personne : curseur horaire, lecture automatique.
    val replayTrace = remember(visible, focus) { focus?.let { visible[it] }?.sortedBy { it.time }.orEmpty() }
    var replayAt by remember(day, focus) { mutableStateOf<Long?>(null) }
    var playing by remember(day, focus) { mutableStateOf(false) }
    val replayPoint = replayAt?.let { pointAt(replayTrace, it) }

    // Carte : tracés visibles, point mis en avant, cadrage sur la journée.
    LaunchedEffect(state.style, places) { state.style?.setPlaces(context, places) }

    LaunchedEffect(state.style, visible, highlighted, replayAt) {
        val st = state.style ?: return@LaunchedEffect
        val at = replayAt
        val who = focus
        if (at != null && who != null && replayPoint != null) {
            // En lecture : le tracé s'arrête à l'heure du curseur.
            st.setTraces(mapOf(who to replayTrace.filter { it.time <= at } + replayPoint), colors)
            st.setHighlight(replayPoint, colors[who] ?: 0)
        } else {
            st.setTraces(visible, colors)
            st.setHighlight(highlighted?.point, highlighted?.let { colors[it.memberId] } ?: 0)
        }
    }

    // Lecture : on avance de point en point (les arrêts passent vite, les trajets
    // se déroulent), la carte suit la personne.
    LaunchedEffect(playing) {
        while (playing && replayTrace.size >= 2) {
            val t = replayAt ?: replayTrace.first().time
            val next = replayTrace.firstOrNull { it.time > t }
            if (next == null) { playing = false; break }
            replayAt = next.time
            state.map?.let { m ->
                runCatching { m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(next.latitude, next.longitude)), 250) }
            }
            delay(220)
        }
    }
    LaunchedEffect(state.map, visible) {
        val m = state.map ?: return@LaunchedEffect
        val points = visible.values.flatten().map { LatLng(it.latitude, it.longitude) }
        if (points.isNotEmpty()) frameAll(m, points, bottomPaddingPx = 120)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // En-tête
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                Text("Historique", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (day != today) TextButton(onClick = { day = today }) { Text("Aujourd'hui") }
            }

            // Jour : précédent / suivant
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = { day = day.minusDays(1); highlighted = null; loading = true },
                    enabled = day.isAfter(today.minusDays(RETENTION_DAYS - 1)),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Jour précédent") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(day, today), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                FilledTonalIconButton(
                    onClick = { day = day.plusDays(1); highlighted = null; loading = true },
                    enabled = day.isBefore(today),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Jour suivant") }
            }

            // Qui afficher
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = focus == null, onClick = { focus = null; highlighted = null }, label = { Text("Tout le monde") })
                members.forEach { m ->
                    val travel = traces[m.id]?.let { travelSplit(it) }
                    // Pas du jour : le plus grand compteur envoyé ce jour-là.
                    val steps = traces[m.id]?.mapNotNull { it.steps }?.maxOrNull()
                    FilterChip(
                        selected = focus == m.id,
                        onClick = { focus = m.id; highlighted = null },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(colors[m.id] ?: 0))) },
                        label = {
                            Text(
                                listOfNotNull(
                                    nameOf(m),
                                    travel?.let { t -> travelParts(t).joinToString(" ") { (e, km) -> "$e $km" } }?.takeIf { it.isNotEmpty() },
                                    steps?.let { "${formatSteps(it)}/${formatSteps(m.stepGoal)} pas" + if (it >= m.stepGoal) " 🎉" else "" },
                                ).joinToString(" · "),
                            )
                        },
                    )
                }
            }

            // Carte
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large)) {
                KinMap(state, Modifier.fillMaxSize(), attributionTopDp = 8)
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            // Rejouer le trajet : bouton lecture, curseur horaire, heure et vitesse.
            if (replayTrace.size >= 2) {
                val first = replayTrace.first().time
                val last = replayTrace.last().time
                val shownAt = replayAt ?: last
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(onClick = {
                        if (!playing && (replayAt == null || replayAt!! >= last)) replayAt = first
                        highlighted = null
                        playing = !playing
                    }) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Rejouer la journée",
                        )
                    }
                    androidx.compose.material3.Slider(
                        // Secondes depuis le premier point : un Float ne garde pas la précision d'une date.
                        value = ((shownAt - first) / 1000f).coerceIn(0f, (last - first) / 1000f),
                        onValueChange = { s ->
                            playing = false
                            highlighted = null
                            replayAt = first + (s * 1000).toLong()
                        },
                        valueRange = 0f..((last - first) / 1000f).coerceAtLeast(1f),
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            java.time.Instant.ofEpochMilli(shownAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        replayPoint?.speedKmh?.takeIf { it >= 1 }?.let { kmh ->
                            Text("${if (kmh > 15) "🚗" else "🚶"} ${kmh.toInt()} km/h", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                    }
                    if (replayAt != null) {
                        IconButton(onClick = { playing = false; replayAt = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer la lecture")
                        }
                    }
                }
            } else if (focus == null && traces.values.any { it.size >= 2 }) {
                Text(
                    "▶ Choisis une personne pour rejouer sa journée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            // Chronologie
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    error != null -> InfoCardPadded("Historique indisponible", error!!)
                    !loading && rows.isEmpty() -> InfoCardPadded(
                        "Aucun déplacement ce jour-là",
                        "Les positions sont conservées $RETENTION_DAYS jours sur le serveur.",
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(rows, key = { "${it.memberId}-${it.start}-${it is HistoryRow.Stop}" }) { row ->
                            val member = members.firstOrNull { it.id == row.memberId }
                            TimelineRow(
                                row = row,
                                name = member?.let { nameOf(it) } ?: "",
                                showName = focus == null,
                                color = Color(colors[row.memberId] ?: 0),
                                selected = highlighted == row,
                                place = (row as? HistoryRow.Stop)?.let { placeAt(it.point.latitude, it.point.longitude, places) },
                                onClick = {
                                    playing = false
                                    replayAt = null
                                    highlighted = row
                                    val m = state.map
                                    if (row is HistoryRow.Trip && m != null) {
                                        frameAll(m, row.points.map { LatLng(it.latitude, it.longitude) }, bottomPaddingPx = 120)
                                    } else {
                                        m?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(row.point.latitude, row.point.longitude), 16.0), 500)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCardPadded(title: String, detail: String) {
    Box(Modifier.padding(16.dp)) { InfoCard(title = title, detail = detail, container = SoftGreen) }
}

/** Une ligne de la frise : heure, pastille de couleur reliée par un trait, description. */
@Composable
private fun TimelineRow(
    row: HistoryRow,
    name: String,
    showName: Boolean,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    place: fr.kinwaypoint.app.net.Place? = null,
) {
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
    fun hhmm(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(time)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) color.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(hhmm(row.start), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.width(52.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(6.dp).background(color.copy(alpha = 0.4f)))
            Box(
                Modifier.size(if (row is HistoryRow.Stop) 26.dp else 12.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center,
            ) {
                if (row is HistoryRow.Stop) {
                    if (place != null) Text(placeEmoji(place.icon), style = MaterialTheme.typography.labelSmall)
                    else Icon(Icons.Default.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Box(Modifier.width(2.dp).height(6.dp).background(color.copy(alpha = 0.4f)))
        }
        Column(Modifier.weight(1f)) {
            val who = if (showName) "$name · " else ""
            when (row) {
                is HistoryRow.Stop -> {
                    val minutes = (row.end - row.start) / 60_000
                    Text("$who${place?.name ?: "Arrêt"}", style = MaterialTheme.typography.titleSmall)
                    Text("${hhmm(row.start)} → ${hhmm(row.end)} · ${durationLabel(minutes)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                is HistoryRow.Trip -> {
                    val (emoji, mode) = tripMode(row.averageKmh)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$who$emoji $mode", style = MaterialTheme.typography.bodyMedium)
                        // Vitesse moyenne du trajet, dans une pastille à côté du mode.
                        StatusPill("${row.averageKmh.toInt()} km/h", color, showDot = false)
                    }
                    Text(
                        listOfNotNull(
                            formatDistance(row.distance),
                            durationLabel((row.end - row.start) / 60_000),
                            "moy. ${row.averageKmh.toInt()} km/h",
                            row.maxKmh?.let { "max ${it.toInt()} km/h" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
        Icon(Icons.Default.Place, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(2.dp))
    }
}

private fun durationLabel(minutes: Long) = when {
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} h ${String.format(Locale.FRANCE, "%02d", minutes % 60)}"
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Aujourd'hui"
    today.minusDays(1) -> "Hier"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE)).replaceFirstChar { it.uppercase() }
}

/** Position à l'instant t : interpolée entre les deux points qui l'encadrent. */
private fun pointAt(points: List<TracePoint>, t: Long): TracePoint? {
    if (points.isEmpty()) return null
    if (t <= points.first().time) return points.first()
    if (t >= points.last().time) return points.last()
    val i = points.indexOfFirst { it.time > t }
    val a = points[i - 1]
    val b = points[i]
    val f = (t - a.time).toDouble() / (b.time - a.time).coerceAtLeast(1)
    return a.copy(
        latitude = a.latitude + (b.latitude - a.latitude) * f,
        longitude = a.longitude + (b.longitude - a.longitude) * f,
        time = t,
        speedKmh = b.speedKmh,
    )
}
