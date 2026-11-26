package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.TraccarApi
import fr.kinwaypoint.app.net.TracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bilan d'une journée. */
private data class DaySummary(val day: LocalDate, val steps: Int?, val meters: Float, val placeMillis: Map<Long, Long>)

/** Découpe 7 jours de positions en bilans quotidiens. */
private fun summarize(points: List<TracePoint>, days: List<LocalDate>, places: List<Place>, zone: ZoneId): List<DaySummary> {
    val byDay = points.filter { it.accuracy <= 100 || it.accuracy == 0.0 }
        .groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
    return days.map { day ->
        val list = byDay[day].orEmpty()
        val placeMillis = HashMap<Long, Long>()
        // Temps passé : deux points consécutifs dans le même lieu, à moins de 30 min d'écart.
        list.zipWithNext().forEach { (a, b) ->
            val gap = b.time - a.time
            if (gap in 1..30 * 60_000L) {
                val pa = placeAt(a.latitude, a.longitude, places)
                if (pa != null && pa == placeAt(b.latitude, b.longitude, places)) {
                    placeMillis[pa.id] = (placeMillis[pa.id] ?: 0L) + gap
                }
            }
        }
        DaySummary(day, list.mapNotNull { it.steps }.maxOrNull(), traceLength(list), placeMillis)
    }
}

/** Bilan de la semaine : pas jour par jour, objectif, distance, temps passé dans chaque lieu. */
@Composable
fun WeeklyScreen(
    api: TraccarApi,
    token: String,
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    places: List<Place>,
    ownDeviceId: Long,
    onBack: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    var focus by remember { mutableStateOf(members.firstOrNull { !it.isParent }?.id ?: members.firstOrNull()?.id) }
    var summaries by remember { mutableStateOf<List<DaySummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    val member = members.firstOrNull { it.id == focus }
    val color = member?.let { profileColor(it.name, it.color) } ?: MaterialTheme.colorScheme.primary

    LaunchedEffect(focus, places) {
        val id = focus ?: return@LaunchedEffect
        summaries = null
        val from = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        withContext(Dispatchers.IO) {
            runCatching { summarize(api.positions(token, id, from, System.currentTimeMillis()), days, places, zone) }
        }.onSuccess { summaries = it; error = null }.onFailure { error = it.message ?: "Bilan indisponible" }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                Column {
                    Text("Bilan de la semaine", style = MaterialTheme.typography.headlineSmall)
                    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
                    Text("${days.first().format(fmt)} – ${days.last().format(fmt)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                members.forEach { m ->
                    FilterChip(
                        selected = focus == m.id,
                        onClick = { focus = m.id },
                        label = { Text(if (m.id == ownDeviceId) "Moi" else m.name) },
                        leadingIcon = { Avatar(m.name, size = 22.dp, photo = photos[m.id], colorHex = m.color) },
                    )
                }
            }

            val data = summaries
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    error != null -> InfoCard("Bilan indisponible", error!!, SoftOrange)
                    data == null || member == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> {
                        val goal = member.stepGoal
                        val stepDays = data.mapNotNull { it.steps }
                        val totalSteps = stepDays.sum()
                        val reached = data.count { (it.steps ?: 0) >= goal }
                        val meters = data.sumOf { it.meters.toDouble() }.toFloat()

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("👟", formatSteps(totalSteps), "pas", Modifier.weight(1f))
                            StatTile("📈", formatSteps(if (stepDays.isEmpty()) 0 else totalSteps / stepDays.size), "pas / jour", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("🎯", "$reached / 7", "objectif atteint", Modifier.weight(1f))
                            StatTile("🗺️", formatDistance(meters), "parcourus", Modifier.weight(1f))
                        }

                        SectionCard {
                            Text("Pas par jour", style = MaterialTheme.typography.titleMedium)
                            Text("Objectif : ${formatSteps(goal)} pas (ligne pointillée)", style = MaterialTheme.typography.bodySmall, color = Muted)
                            StepsChart(data, goal, color)
                        }

                        SectionCard {
                            Text("Temps passé", style = MaterialTheme.typography.titleMedium)
                            val totals = places.associateWith { p -> data.sumOf { it.placeMillis[p.id] ?: 0L } }
                                .filterValues { it >= 5 * 60_000L }
                                .entries.sortedByDescending { it.value }
                            if (totals.isEmpty()) {
                                Text("Aucun passage dans vos lieux cette semaine.", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            totals.forEach { (place, millis) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${placeEmoji(place.icon)}  ${place.name}", modifier = Modifier.weight(1f))
                                    Text(shortDuration(millis), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Text(
                            "Calculé à partir des positions conservées sur le serveur. Les pas ne sont comptés que les jours où le podomètre est actif.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** Histogramme des 7 jours : vert si l'objectif est atteint, ligne pointillée = objectif. */
@Composable
private fun StepsChart(data: List<DaySummary>, goal: Int, color: androidx.compose.ui.graphics.Color) {
    val success = AppTheme.colors.success
    val track = color.copy(alpha = 0.12f)
    val goalColor = Muted
    val max = maxOf(goal, data.maxOfOrNull { it.steps ?: 0 } ?: 0).toFloat() * 1.1f
    Column {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val slot = size.width / data.size
            val barW = slot * 0.55f
            data.forEachIndexed { i, d ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), androidx.compose.ui.geometry.CornerRadius(12f, 12f))
                val steps = d.steps ?: 0
                if (steps > 0) {
                    val h = size.height * steps / max
                    drawRoundRect(
                        if (steps >= goal) success else color,
                        Offset(x, size.height - h),
                        Size(barW, h),
                        androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                    )
                }
            }
            val y = size.height - size.height * goal / max
            drawLine(goalColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            data.forEach { d ->
                Text(
                    d.day.format(DateTimeFormatter.ofPattern("EEEEE", Locale.FRANCE)).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
