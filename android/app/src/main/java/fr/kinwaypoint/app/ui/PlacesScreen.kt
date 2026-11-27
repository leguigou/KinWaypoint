package fr.kinwaypoint.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import fr.kinwaypoint.app.net.PlaceSchedule
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.Place
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/**
 * Lieux de la famille (maison, école…). Chaque lieu est une zone Traccar
 * appliquée à tous les appareils : le serveur génère les événements d'arrivée
 * et de départ, envoyés en notification aux parents.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlacesScreen(
    api: TraccarApi,
    token: String,
    places: List<Place>,
    members: List<Member>,
    permissions: PermissionSnapshot,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    photos: Map<Long, android.graphics.Bitmap> = emptyMap(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { KinMapState() }
    val me = rememberMyLocation(enabled = permissions.fineLocation)

    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("home") }
    var radius by remember { mutableFloatStateOf(150f) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Place?>(null) }
    var editingId by remember { mutableStateOf(0L) } // 0 = nouveau lieu
    var color by remember { mutableStateOf("#5B5BD6") }
    var pickingColor by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(true) }
    // Enfants concernés (null = tout le monde) et horaires des alertes.
    var chosen by remember { mutableStateOf<Set<Long>?>(null) }
    var scheduleOn by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var fromMinutes by remember { mutableIntStateOf(8 * 60) }
    var toMinutes by remember { mutableIntStateOf(17 * 60) }
    var pickingTime by remember { mutableStateOf<String?>(null) } // "from" / "to"
    var detail by remember { mutableStateOf<Place?>(null) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }

    fun startAdd() {
        editingId = 0L; name = ""; icon = "home"; color = "#5B5BD6"; alerts = true; radius = 150f
        chosen = null; scheduleOn = false; days = setOf(1, 2, 3, 4, 5); fromMinutes = 8 * 60; toMinutes = 17 * 60
        adding = true
    }

    fun startAddAt(s: PlaceSuggestion) {
        startAdd()
        name = s.name; icon = s.icon
        state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.latitude, s.longitude), 16.0))
    }

    fun startEdit(p: Place) {
        editingId = p.id; name = p.name; icon = p.icon; color = p.color; alerts = p.alerts
        radius = p.radius.toFloat().coerceIn(30f, 500f)
        chosen = p.childIds?.toSet()
        scheduleOn = p.schedule != null
        p.schedule?.let { days = it.days; fromMinutes = it.fromMinutes; toMinutes = it.toMinutes }
        adding = true
        state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 16.0))
    }

    BackHandler { if (detail != null) detail = null else if (adding) adding = false else onBack() }

    // Lieux fréquents des 14 derniers jours, pas encore enregistrés.
    LaunchedEffect(members.map { it.id }, places.map { it.id }) {
        val now = System.currentTimeMillis()
        suggestions = withContext(Dispatchers.IO) {
            runCatching {
                val tracks = members.map { m -> runCatching { api.positions(token, m.id, now - 14 * 24 * 3_600_000L, now) }.getOrDefault(emptyList()) }
                suggestPlaces(tracks, places)
            }.getOrDefault(emptyList())
        }
    }

    // Centre de la carte = position du futur lieu.
    LaunchedEffect(state.map) {
        val m = state.map ?: return@LaunchedEffect
        center = m.cameraPosition.target
        m.addOnCameraIdleListener { center = m.cameraPosition.target }
    }

    // Lieux existants + aperçu du lieu en cours de création.
    LaunchedEffect(state.style, places, adding, center, radius, icon, color, name, alerts) {
        val st = state.style ?: return@LaunchedEffect
        val preview = center?.takeIf { adding }?.let {
            Place(-1, name.ifBlank { "Nouveau lieu" }, it.latitude, it.longitude, radius.toDouble(), icon, color, alerts)
        }
        // En modification, l'ancienne version du lieu est masquée au profit de l'aperçu.
        st.setPlaces(context, places.filter { !adding || it.id != editingId } + listOfNotNull(preview))
    }

    // Cadrage : sur les lieux existants, sinon sur ce téléphone, sinon sur un membre.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(state.map, places, me) {
        val m = state.map ?: return@LaunchedEffect
        if (framed) return@LaunchedEffect
        val points = places.map { LatLng(it.latitude, it.longitude) }
            .ifEmpty { listOfNotNull(me?.let { LatLng(it.latitude, it.longitude) }) }
            .ifEmpty { members.mapNotNull { it.position?.let { p -> LatLng(p.latitude, p.longitude) } } }
        if (points.isNotEmpty()) {
            frameAll(m, points, bottomPaddingPx = 120)
            framed = true
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (adding) adding = false else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text(
                    when {
                        !adding -> "Lieux et points d'intérêt"
                        editingId == 0L -> "Nouveau lieu"
                        else -> "Modifier le lieu"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large)) {
                KinMap(state, Modifier.fillMaxSize(), attributionTopDp = 8)
                if (adding) {
                    // Cible au centre : on déplace la carte sous elle.
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center).size(44.dp).padding(bottom = 22.dp),
                    )
                    if (me != null) {
                        FilledTonalIconButton(
                            onClick = { state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.latitude, me.longitude), 16.0)) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) { Icon(Icons.Default.MyLocation, contentDescription = "Ma position") }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (adding) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Déplace la carte pour placer le lieu sous la cible.", style = MaterialTheme.typography.bodySmall, color = Muted)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            label = { Text("Libellé (ex. Maison, École Jules Ferry, Piscine)") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Icône", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlaceIcons.forEach { t ->
                                FilterChip(
                                    selected = icon == t.key,
                                    onClick = {
                                        // Libellé proposé tant que l'utilisateur n'a pas tapé le sien.
                                        if (name.isBlank() || PlaceIcons.any { it.label == name }) name = t.label
                                        icon = t.key
                                    },
                                    label = { Text("${t.emoji} ${t.label}") },
                                )
                            }
                        }
                        Text("Couleur", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProfilePalette.forEach { hex ->
                                val c = parseHex(hex)!!
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(3.dp, if (color == hex) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                                        .clickable { color = hex },
                                )
                            }
                            CustomColorSwatch(
                                custom = parseHex(color)?.takeIf { color.uppercase() !in ProfilePalette },
                                size = 36.dp,
                            ) { pickingColor = true }
                        }
                        if (pickingColor) {
                            ColorPickerDialog(
                                initial = parseHex(color) ?: MaterialTheme.colorScheme.primary,
                                title = "Couleur du lieu",
                                onDismiss = { pickingColor = false },
                                onPick = { hex -> color = hex; pickingColor = false },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Alertes d'arrivée et de départ", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (alerts) "Tu es prévenu quand quelqu'un arrive ou part." else "Simple point d'intérêt affiché sur la carte.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                            Switch(checked = alerts, onCheckedChange = { alerts = it })
                        }
                        if (alerts) {
                            // Qui est concerné par les alertes de ce lieu.
                            Text("Pour qui ?", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(selected = chosen == null, onClick = { chosen = null }, label = { Text("Tout le monde") })
                                members.forEach { m ->
                                    val on = chosen?.contains(m.id) == true
                                    FilterChip(
                                        selected = on,
                                        onClick = {
                                            val next = (chosen ?: emptySet()).let { if (on) it - m.id else it + m.id }
                                            chosen = next.ifEmpty { null }
                                        },
                                        label = { Text(m.name) },
                                    )
                                }
                            }
                            // Horaires : alertes seulement à certains moments (calendrier du serveur).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Seulement à certains horaires", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (scheduleOn) PlaceSchedule(days, fromMinutes, toMinutes).label() else "Alertes à toute heure.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                }
                                Switch(checked = scheduleOn, onCheckedChange = { scheduleOn = it })
                            }
                            if (scheduleOn) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, letter ->
                                        val day = i + 1
                                        val on = day in days
                                        Box(
                                            Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { days = if (on) days - day else days + day },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                letter,
                                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { pickingTime = "from" }, shape = CircleShape, modifier = Modifier.weight(1f)) {
                                        Text("De ${hhmmMinutes(fromMinutes)}")
                                    }
                                    OutlinedButton(onClick = { pickingTime = "to" }, shape = CircleShape, modifier = Modifier.weight(1f)) {
                                        Text("à ${hhmmMinutes(toMinutes)}")
                                    }
                                }
                                if (toMinutes <= fromMinutes) {
                                    Text("Le créneau passe minuit : il commence les jours choisis et finit le lendemain.", style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                                if (days.isEmpty()) {
                                    Text("Choisis au moins un jour.", style = MaterialTheme.typography.bodySmall, color = Danger)
                                }
                            }
                        }
                        Text("Rayon : ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                        Slider(value = radius, onValueChange = { radius = it }, valueRange = 30f..500f)
                        Button(
                            onClick = {
                                val c = center ?: return@Button
                                busy = true
                                scope.launch {
                                    val ids = members.map { it.id }
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            api.savePlace(
                                                token,
                                                Place(
                                                    editingId, name.ifBlank { "Lieu" }, c.latitude, c.longitude, radius.toDouble(), icon, color, alerts,
                                                    childIds = chosen?.toList(),
                                                    schedule = if (scheduleOn && days.isNotEmpty()) PlaceSchedule(days, fromMinutes, toMinutes) else null,
                                                ),
                                                ids,
                                            )
                                        }
                                    }.onSuccess {
                                        adding = false
                                        name = ""
                                        onChanged()
                                    }.onFailure { Toast.makeText(context, it.message ?: "Création impossible", Toast.LENGTH_LONG).show() }
                                    busy = false
                                }
                            },
                            enabled = !busy && center != null && name.isNotBlank() && !(alerts && scheduleOn && days.isEmpty()),
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(if (editingId == 0L) "Enregistrer le lieu" else "Enregistrer les modifications") }
                    }
                } else {
                    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (places.isEmpty()) {
                            item {
                                InfoCard(
                                    title = "Aucun lieu",
                                    detail = "Ajoute la maison, l'école… Tu seras prévenu quand un enfant y arrive ou en part.",
                                    container = SoftGreen,
                                    icon = Icons.Default.LocationOn,
                                )
                            }
                        }
                        items(places, key = { it.id }) { p ->
                            SectionCard(modifier = Modifier.clickable { detail = p }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(parseHex(p.color) ?: MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(placeEmoji(p.icon), style = MaterialTheme.typography.titleMedium) }
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (!p.alerts) {
                                                "Rayon ${p.radius.toInt()} m · point d'intérêt"
                                            } else {
                                                listOfNotNull(
                                                    p.childIds?.let { ids -> members.filter { it.id in ids }.joinToString(", ") { it.name } }?.ifBlank { null } ?: "Tout le monde",
                                                    p.schedule?.label() ?: "à toute heure",
                                                ).joinToString(" · ")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muted,
                                        )
                                    }
                                    IconButton(onClick = {
                                        state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 16.0))
                                    }) { Icon(Icons.Default.LocationOn, contentDescription = "Voir") }
                                    IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = Danger) }
                                }
                            }
                        }
                        // Lieux fréquents repérés dans les déplacements, pas encore enregistrés.
                        if (suggestions.isNotEmpty()) {
                            item { Text("Lieux fréquents suggérés", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                            items(suggestions, key = { "s-${it.latitude}-${it.longitude}" }) { s ->
                                SectionCard(container = SoftGreen) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(placeEmoji(s.icon), style = MaterialTheme.typography.headlineSmall)
                                        Column(Modifier.weight(1f)) {
                                            Text("${s.name} ?", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${s.visits} passages sur ${s.days} jours · ${durationText(s.totalMs)} · ${s.hint}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Muted,
                                            )
                                        }
                                        IconButton(onClick = {
                                            state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.latitude, s.longitude), 16.0))
                                        }) { Icon(Icons.Default.LocationOn, contentDescription = "Voir") }
                                        FilledTonalButton(onClick = { startAddAt(s) }, shape = CircleShape) { Text("Ajouter") }
                                    }
                                }
                            }
                        }
                        item {
                            Button(onClick = { startAdd() }, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Ajouter un lieu ou un point d'intérêt")
                            }
                        }
                    }
                }
            }
        }
    }

    // Fiche du lieu, par-dessus la liste (la carte reste chargée dessous).
    detail?.let { p ->
        PlaceDetailScreen(
            api = api,
            token = token,
            place = p,
            members = members,
            photos = photos,
            onEdit = { detail = null; startEdit(p) },
            onShowOnMap = {
                detail = null
                state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 16.0))
            },
            onBack = { detail = null },
        )
    }
    }

    pickingTime?.let { which ->
        TimePickDialog(
            minutes = if (which == "from") fromMinutes else toMinutes,
            title = if (which == "from") "Début du créneau" else "Fin du créneau",
            onDismiss = { pickingTime = null },
            onPick = { m -> if (which == "from") fromMinutes = m else toMinutes = m; pickingTime = null },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les alertes d'arrivée et de départ pour ce lieu s'arrêteront.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { api.deletePlace(token, p.id) } }
                            .onSuccess { onChanged() }
                            .onFailure { Toast.makeText(context, it.message ?: "Suppression impossible", Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Supprimer", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

/** Choix d'une heure (cadran 24 h). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(minutes: Int, title: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = androidx.compose.material3.rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { androidx.compose.material3.TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
