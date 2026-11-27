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
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(dayEvents, key = { it.id }) { e ->
                            val member = members.firstOrNull { it.id == e.deviceId }
                            val name = member?.name ?: "Appareil ${e.deviceId}"
                            val place = places.firstOrNull { it.id == e.placeId }
                            val placeName = place?.name ?: "un lieu"
                            val (text, sos) = when {
                                e.type == "geofenceEnter" -> "$name est arrivé·e à $placeName" to false
                                e.type == "geofenceExit" -> "$name a quitté $placeName" to false
                                e.type == "deviceUnknown" -> "📵 Pas de nouvelles de $name depuis 1 h" to false
                                e.type == "deviceOverspeed" -> "🚗 $name roule au-delà de la limite" to false
                                e.type == "proximityEnter" -> "👋 $name est de retour près de toi" to false
                                e.alarm.equals("lowBattery", ignoreCase = true) -> "🔋 Batterie faible pour $name" to false
                                e.alarm.equals("general", ignoreCase = true) -> "✅ $name est bien arrivé·e" to false
                                else -> "$name a déclenché un SOS" to true
                            }
                            SectionCard(container = if (sos) SoftRed else CardSurface, modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Avatar(name, size = 40.dp, photo = member?.let { photos[it.id] }, colorHex = member?.color)
                                    Column(Modifier.weight(1f)) {
                                        Text((place?.let { placeEmoji(it.icon) + " " } ?: if (sos) "🚨 " else "") + text, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            Instant.ofEpochMilli(e.time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muted,
                                        )
                                    }
                                    if (member != null) {
                                        IconButton(onClick = { onOpenMember(member.id) }) {
                                            Icon(Icons.Default.Map, contentDescription = "Voir sur la carte")
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
