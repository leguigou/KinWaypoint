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
@Composable
fun PlacesScreen(
    api: TraccarApi,
    token: String,
    places: List<Place>,
    members: List<Member>,
    permissions: PermissionSnapshot,
    onChanged: () -> Unit,
    onBack: () -> Unit,
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
    var alerts by remember { mutableStateOf(true) }

    fun startAdd() {
        editingId = 0L; name = ""; icon = "home"; color = "#5B5BD6"; alerts = true; radius = 150f
        adding = true
    }

    fun startEdit(p: Place) {
        editingId = p.id; name = p.name; icon = p.icon; color = p.color; alerts = p.alerts
        radius = p.radius.toFloat().coerceIn(30f, 500f)
        adding = true
        state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 16.0))
    }

    BackHandler { if (adding) adding = false else onBack() }

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
                                                Place(editingId, name.ifBlank { "Lieu" }, c.latitude, c.longitude, radius.toDouble(), icon, color, alerts),
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
                            enabled = !busy && center != null && name.isNotBlank(),
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
                            SectionCard(modifier = Modifier.clickable { startEdit(p) }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(parseHex(p.color) ?: MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(placeEmoji(p.icon), style = MaterialTheme.typography.titleMedium) }
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Rayon ${p.radius.toInt()} m · " + if (p.alerts) "alertes activées" else "point d'intérêt",
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
