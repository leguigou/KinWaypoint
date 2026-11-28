package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.FamilyEvent
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cloche : arrivées et départs des lieux, SOS, sur les 7 derniers jours. */
@Composable
fun EventsScreen(
    api: TraccarApi,
    token: String,
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    places: List<Place>,
    onOpenMember: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var events by remember { mutableStateOf<List<FamilyEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(members.map { it.id }) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching { api.events(token, members.map { it.id }, now - 7L * 24 * 3600 * 1000, now + 60_000) }
        }.onSuccess { events = it; error = null }.onFailure { error = it.message ?: "Notifications indisponibles" }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay = events.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                Text("Notifications", style = MaterialTheme.typography.headlineSmall)
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.padding(16.dp)) { InfoCard("Notifications indisponibles", error!!, SoftOrange) }
                events.isEmpty() -> Box(Modifier.padding(16.dp)) {
                    InfoCard(
                        "Rien à signaler",
                        "Les arrivées et départs des lieux et les SOS des 7 derniers jours apparaîtront ici.",
                        SoftGreen,
                        icon = Icons.Default.NotificationsNone,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    byDay.forEach { (day, dayEvents) ->
                        item(key = "day-$day") {
                            Text(
                                when (day) {
                                    today -> "Aujourd'hui"
                                    today.minusDays(1) -> "Hier"
                                    else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)).replaceFirstChar { it.uppercase() }
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = Muted,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        // Les SOS sortent du lot (bandeau rouge) ; les autres notifications
                        // consécutives sont regroupées dans une même carte, en lignes compactes.
                        val segments = mutableListOf<List<FamilyEvent>>()
                        dayEvents.forEach { e ->
                            val last = segments.lastOrNull()
                            if (e.isSos || last == null || last.first().isSos) segments += listOf(e) else segments[segments.lastIndex] = last + e
                        }
                        segments.forEach { segment ->
                            item(key = "seg-${segment.first().id}") {
                                if (segment.first().isSos) {
                                    SosEventCard(segment.first(), members, photos, zone, onOpenMember)
                                } else {
                                    SectionCard(modifier = Modifier.fillMaxWidth()) {
                                        segment.forEachIndexed { i, e ->
                                            if (i > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            EventRow(e, members, photos, places, zone, onOpenMember)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private val FamilyEvent.isSos: Boolean
    get() = type == "alarm" && !alarm.equals("lowBattery", ignoreCase = true) && !alarm.equals("general", ignoreCase = true)

/** Texte et utilité d'une notification : le bouton carte n'apparaît que s'il mène quelque part. */
private fun describe(e: FamilyEvent, name: String, place: fr.kinwaypoint.app.net.Place?): Pair<String, Boolean> {
    val placeName = place?.name ?: "un lieu"
    return when {
        e.type == "geofenceEnter" -> "${place?.let { placeEmoji(it.icon) + " " }.orEmpty()}$name est arrivé·e à $placeName" to true
        e.type == "geofenceExit" -> "${place?.let { placeEmoji(it.icon) + " " }.orEmpty()}$name a quitté $placeName" to true
        e.type == "deviceUnknown" -> "📵 Pas de nouvelles de $name depuis 1 h" to true
        e.type == "deviceOverspeed" -> "🚗 $name roule au-delà de la limite" to true
        e.type == "proximityEnter" -> "👋 $name est de retour près de toi" to true
        // Simple information : pas de bouton.
        e.alarm.equals("lowBattery", ignoreCase = true) -> "🪫 Batterie faible pour $name" to false
        e.alarm.equals("general", ignoreCase = true) -> "✅ $name est bien arrivé·e" to true
        else -> "🚨 $name a déclenché un SOS" to true
    }
}

/** Ligne compacte : petit avatar, texte, heure ; bouton carte seulement si utile. */
@Composable
private fun EventRow(
    e: FamilyEvent,
    members: List<Member>,
    photos: Map<Long, android.graphics.Bitmap>,
    places: List<fr.kinwaypoint.app.net.Place>,
    zone: ZoneId,
    onOpenMember: (Long) -> Unit,
) {
    val member = members.firstOrNull { it.id == e.deviceId }
    val name = member?.name ?: "Appareil ${e.deviceId}"
    val (text, actionable) = describe(e, name, places.firstOrNull { it.id == e.placeId })
    val canOpen = actionable && member != null
    Row(
        Modifier.fillMaxWidth()
            .then(if (canOpen) Modifier.clickable { onOpenMember(member!!.id) } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name, size = 32.dp, photo = member?.let { photos[it.id] }, colorHex = member?.color)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                Instant.ofEpochMilli(e.time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        if (canOpen) {
            Icon(Icons.Default.Map, contentDescription = "Voir sur la carte", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

/** SOS : bandeau rouge bien visible, avec un vrai bouton vers la carte. */
@Composable
private fun SosEventCard(
    e: FamilyEvent,
    members: List<Member>,
    photos: Map<Long, android.graphics.Bitmap>,
    zone: ZoneId,
    onOpenMember: (Long) -> Unit,
) {
    val member = members.firstOrNull { it.id == e.deviceId }
    val name = member?.name ?: "Appareil ${e.deviceId}"
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFE5484D), Color(0xFFFF7A59))))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("SOS de $name", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Déclenché à " + Instant.ofEpochMilli(e.time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
                Avatar(name, size = 36.dp, photo = member?.let { photos[it.id] }, colorHex = member?.color)
            }
            if (member != null) {
                androidx.compose.material3.Button(
                    onClick = { onOpenMember(member.id) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFE5484D)),
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Voir sur la carte", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
