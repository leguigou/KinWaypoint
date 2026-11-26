package fr.kinwaypoint.app.ui

import android.graphics.Bitmap
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TracePoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val ME_ID = -1L

/** Un point affiché : un membre de la famille, ou « Moi » (ce téléphone). */
private data class MapEntry(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val member: Member?,
    val colorHex: String?,
) {
    val isMe get() = id == ME_ID
    val latLng get() = LatLng(latitude, longitude)
}

/**
 * Carte de la famille.
 * - Au-dessus de chaque point GPS : l'avatar (photo, ou initiale par défaut).
 * - Le tracé du jour de chacun, dans sa couleur, qui s'allonge au fil de la journée.
 * - En bas : une fiche par personne, à faire glisser ; la carte suit.
 * - Toucher un avatar sur la carte fait défiler jusqu'à sa fiche.
 *
 * @param ownDeviceId appareil Traccar de ce téléphone (0 = aucun) : affiché comme « Moi ».
 * @param traces tracés du jour par appareil (vide = pas de tracé, ex. côté enfant)
 */
@Composable
fun FamilyMapScreen(
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    focusId: Long?,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onBack: () -> Unit,
    traces: Map<Long, List<TracePoint>> = emptyMap(),
    ownDeviceId: Long = 0L,
    onOpenHistory: (() -> Unit)? = null,
    title: String = "Ma famille",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = rememberMyLocation(enabled = permissions.fineLocation)
    val state = remember { KinMapState() }
    val ownMember = members.firstOrNull { it.id == ownDeviceId && ownDeviceId != 0L }

    BackHandler(onBack = onBack)

    // « Moi » en dernier : sa position arrive après coup, elle ne doit pas décaler les pages.
    val entries: List<MapEntry> = buildList {
        members.filter { it.id != ownDeviceId || ownDeviceId == 0L }.forEach { m ->
            m.position?.let { add(MapEntry(m.id, m.name, it.latitude, it.longitude, m, m.color)) }
        }
        me?.let { add(MapEntry(ME_ID, "Moi", it.latitude, it.longitude, null, ownMember?.color)) }
    }
    val currentEntries by rememberUpdatedState(entries)

    // Couleur de chaque tracé ; celui de ce téléphone est rattaché à « Moi ».
    val colors: Map<Long, Int> = entries.associate { e ->
        e.id to profileColor(if (e.isMe) (ownMember?.name ?: "Moi") else e.name, e.colorHex).toArgb()
    }
    val mapTraces: Map<Long, List<TracePoint>> = traces.mapKeys { (id, _) -> if (id == ownDeviceId && ownDeviceId != 0L) ME_ID else id }

    val pagerState = rememberPagerState(
        initialPage = entries.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
    ) { entries.size }
    val selectedId = entries.getOrNull(pagerState.currentPage)?.id

    // Toucher un avatar → sa fiche passe au premier plan.
    LaunchedEffect(state.map) {
        val m = state.map ?: return@LaunchedEffect
        m.addOnMapClickListener { point ->
            val screen = m.projection.toScreenLocation(point)
            val id = m.queryRenderedFeatures(screen, AVATAR_LAYER).firstOrNull()
                ?.getNumberProperty("id")?.toLong() ?: return@addOnMapClickListener false
            val index = currentEntries.indexOfFirst { it.id == id }
            if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
            true
        }
    }

    // Images des avatars (photo ou initiale), régénérées quand une photo, une couleur ou un statut change.
    val markerKey = entries.map { listOf(it.id, it.name, it.member?.online, it.colorHex) } to photos.values.map { it.generationId }
    LaunchedEffect(state.style, markerKey) {
        val st = state.style ?: return@LaunchedEffect
        entries.forEach { e ->
            val bitmap = markerBitmap(
                context = context,
                name = e.name,
                photo = if (e.isMe) ownMember?.let { photos[it.id] } else photos[e.id],
                gradient = profileGradient(if (e.isMe) "Moi" else e.name, e.colorHex).map { it.toArgb() },
                status = when {
                    e.isMe -> null
                    e.member?.online == true -> 0xFF12B886.toInt()
                    else -> 0xFF9AA0B4.toInt()
                },
                isMe = e.isMe && ownMember?.let { photos[it.id] } == null,
            )
            st.addImage("avatar-${e.id}", bitmap)
        }
    }

    // Points, avatars et tracés.
    LaunchedEffect(state.style, entries, selectedId, markerKey, mapTraces) {
        val st = state.style ?: return@LaunchedEffect
        val features = entries.map { e ->
            Feature.fromGeometry(Point.fromLngLat(e.longitude, e.latitude)).apply {
                addNumberProperty("id", e.id)
                addStringProperty("icon", "avatar-${e.id}")
                addStringProperty("color", String.format("#%06X", 0xFFFFFF and (colors[e.id] ?: 0)))
                addBooleanProperty("selected", e.id == selectedId)
                addNumberProperty("sort", if (e.id == selectedId) 1 else 0)
            }
        }
        st.getSourceAs<GeoJsonSource>(FAMILY_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
        st.setTraces(mapTraces, colors)
    }

    // Cadrage initial : la personne demandée, sinon toute la famille.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(state.map, entries.size) {
        val m = state.map ?: return@LaunchedEffect
        if (framed || entries.isEmpty()) return@LaunchedEffect
        val target = entries.firstOrNull { it.id == focusId }
        if (target != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target.latLng, 16.0))
        } else {
            frameAll(m, entries.map { it.latLng })
        }
        framed = true
    }

    // Faire glisser les fiches → la carte se recentre sur la personne affichée.
    LaunchedEffect(state.map) {
        val m = state.map ?: return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { page ->
                currentEntries.getOrNull(page)?.let { e ->
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(e.latLng, maxOf(m.cameraPosition.zoom, 15.0)), 600)
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        KinMap(state, Modifier.fillMaxSize())

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            if (onOpenHistory != null) {
                FilledTonalIconButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, contentDescription = "Historique")
                }
            }
            if (me == null) {
                FilledTonalIconButton(onClick = actions.requestLocation) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Afficher ma position")
                }
            }
            FilledTonalIconButton(onClick = { state.map?.let { frameAll(it, entries.map { e -> e.latLng }) } }) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Voir tout le monde")
            }
        }

        if (entries.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 28.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
            ) { page ->
                val entry = entries[page]
                EntryCard(
                    entry = entry,
                    photo = if (entry.isMe) ownMember?.let { photos[it.id] } else photos[entry.id],
                    color = Color(colors[entry.id] ?: 0xFF5B5BD6.toInt()),
                    trace = mapTraces[entry.id].orEmpty(),
                    me = me,
                    actions = actions,
                )
            }
        }
    }
}

/** Fiche d'une personne, en bas de la carte. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(
    entry: MapEntry,
    photo: Bitmap?,
    color: Color,
    trace: List<TracePoint>,
    me: Location?,
    actions: PlatformActions,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        // Liseré de la couleur du profil : la même que son tracé.
        Box(Modifier.fillMaxWidth().height(5.dp).background(color))
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (entry.isMe && photo == null) {
                    IconBadge(Icons.Default.MyLocation, color, size = 48.dp)
                } else {
                    Avatar(entry.name, size = 48.dp, photo = photo, colorHex = entry.colorHex)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (entry.isMe) "Ma position" else entry.name, style = MaterialTheme.typography.titleMedium)
                    val p = entry.member?.position
                    Text(
                        when {
                            entry.isMe -> "Ce téléphone"
                            p != null -> "Vu ${relativeTime(p.fixTime)} · ${formatDateTime(p.fixTime)}"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            // Pastilles sur plusieurs lignes si besoin (statut, batterie, distance, tracé).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val member = entry.member
                if (member != null) {
                    StatusPill(if (member.online) "En ligne" else "Hors ligne", if (member.online) Ok else Idle)
                    member.position?.battery?.let { b ->
                        val charging = member.position?.charging == true
                        StatusPill(
                            "$b %",
                            if (b < 20 && !charging) Danger else MaterialTheme.colorScheme.primary,
                            icon = if (charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                        )
                    }
                    // Distance depuis ce téléphone, quand on connaît sa position.
                    me?.let { here ->
                        val result = FloatArray(1)
                        Location.distanceBetween(here.latitude, here.longitude, entry.latitude, entry.longitude, result)
                        StatusPill(formatDistance(result[0]), Muted, icon = Icons.Default.GpsFixed)
                    }
                } else {
                    me?.let { StatusPill("± ${it.accuracy.toInt()} m", Muted, icon = Icons.Default.GpsFixed) }
                }
                if (trace.size >= 2) {
                    StatusPill(formatDistance(traceLength(trace)) + " aujourd'hui", color, icon = Icons.Default.Route)
                }
            }
            if (!entry.isMe) {
                FilledTonalButton(
                    onClick = { actions.openMap(entry.latitude, entry.longitude, entry.name) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Itinéraire")
                }
            }
        }
    }
}
