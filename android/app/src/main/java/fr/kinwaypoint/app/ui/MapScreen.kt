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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import fr.kinwaypoint.app.motion.Eta
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.VolumeOff
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
import androidx.compose.ui.layout.onSizeChanged
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
    places: List<fr.kinwaypoint.app.net.Place> = emptyList(),
    /** Membres avec un SOS en cours (non acquitté). */
    sosIds: Set<Long> = emptySet(),
    /** Fermer l'alerte SOS d'un membre (= « J'ai vu »). Null : pas de bouton fermer. */
    onDismissSos: ((Long) -> Unit)? = null,
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

    // Étiquette au-dessus de chaque avatar : SOS, ou lieu et durée (« École · 12 min »).
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); clock = System.currentTimeMillis() } }
    val placeOf: Map<Long, fr.kinwaypoint.app.net.Place?> = entries.associate { it.id to placeAt(it.latitude, it.longitude, places) }
    val etas: Map<Long, Eta.Arrival?> = entries.associate { e ->
        val kmh = if (e.isMe) me?.speed?.let { it * 3.6 } else e.member?.position?.kmh
        e.id to if (placeOf[e.id] != null) null else Eta.estimate(mapTraces[e.id].orEmpty(), e.latitude, e.longitude, kmh, places, clock)
    }
    val labels: Map<Long, String?> = entries.associate { e ->
        val place = placeOf[e.id]
        e.id to when {
            e.id in sosIds -> "SOS"
            place != null -> place.name + (inPlaceSince(mapTraces[e.id].orEmpty(), place)?.let { " · " + shortDuration(clock - it) } ?: "")
            // En route vers un lieu : temps d'arrivée estimé.
            etas[e.id] != null -> etas[e.id]!!.let { "→ ${it.place.name} ~${it.minutes} min" }
            // En déplacement rapide : la vitesse au-dessus de l'avatar.
            e.member?.position?.activity == "vehicle" -> e.member?.position?.kmh?.let { "${it.toInt()} km/h" }
            else -> null
        }
    }

    // Hauteur commune des fiches, ajustée à ce qu'elles contiennent vraiment
    // (pas d'espace vide quand aucune fiche n'affiche de pas ni d'arrivée).
    // Estimation de départ, puis hauteur réellement mesurée : la plus haute des
    // fiches (lieu, pas, arrivée, grande police…) fixe la hauteur de toutes.
    val density0 = LocalDensity.current
    val neededHeights = remember { androidx.compose.runtime.mutableStateMapOf<Long, Int>() }
    val estimated = 196.dp +
        (if (labels.values.any { it != null && it != "SOS" && !it.startsWith("→") }) 24.dp else 0.dp) +
        (if (entries.any { it.member?.position?.steps != null }) 46.dp else 0.dp) +
        (if (etas.values.any { it != null }) 28.dp else 0.dp)
    val maxCard = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val measured = entries.mapNotNull { neededHeights[it.id] }.maxOrNull()
    val cardHeight = (measured?.let { with(density0) { it.toDp() } } ?: estimated).coerceIn(150.dp, maxCard)

    // Zones mortes : la barre du haut (+ hauteur d'un avatar, dessiné AU-DESSUS du
    // point) et les fiches du bas. Le cadrage et le centrage restent dans la partie visible.
    val density = LocalDensity.current
    val insets = with(density) {
        MapInsets(
            top = WindowInsets.statusBars.getTop(density) + (72.dp + 96.dp).roundToPx(),
            bottom = WindowInsets.navigationBars.getBottom(density) +
                (if (entries.isEmpty()) 24.dp else cardHeight + 16.dp + 24.dp).roundToPx(),
            side = 56.dp.roundToPx(),
        )
    }
    // Marge appliquée à la caméra : un centrage sur une personne la place au milieu de la zone visible.
    val screenHeightPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.roundToPx() }

    val pagerState = rememberPagerState(
        initialPage = entries.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
    ) { entries.size }
    val selectedId = entries.getOrNull(pagerState.currentPage)?.id
    // Dès que l'utilisateur bouge la carte (doigt, fiche glissée, avatar touché),
    // on ne recadre plus automatiquement.
    var userMoved by remember { mutableStateOf(false) }

    // Toucher un avatar → sa fiche passe au premier plan.
    LaunchedEffect(state.map) {
        val m = state.map ?: return@LaunchedEffect
        m.addOnMapClickListener { point ->
            val screen = m.projection.toScreenLocation(point)
            val id = runCatching { m.queryRenderedFeatures(screen, AVATAR_LAYER).firstOrNull() }.getOrNull()
                ?.getNumberProperty("id")?.toLong() ?: return@addOnMapClickListener false
            val index = currentEntries.indexOfFirst { it.id == id }
            if (index >= 0) { userMoved = true; scope.launch { pagerState.animateScrollToPage(index) } }
            true
        }
    }

    // Images des avatars (photo ou initiale), régénérées quand une photo, une couleur ou un statut change.
    val markerKey = entries.map { listOf(it.id, it.name, it.member?.online, it.colorHex, labels[it.id]) } to photos.values.map { it.generationId }
    LaunchedEffect(state.style, markerKey) {
        val st = state.style ?: return@LaunchedEffect
        st.safely { entries.forEach { e ->
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
                label = labels[e.id],
                sos = e.id in sosIds,
                badge = activityEmoji(e.member?.position?.activity),
            )
            addImage("avatar-${e.id}", bitmap)
        } }
    }

    LaunchedEffect(state.style, places) {
        state.style?.setPlaces(context, places)
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
        st.safely { getSourceAs<GeoJsonSource>(FAMILY_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features)) }
        st.setTraces(mapTraces, colors)
    }

    LaunchedEffect(state.map) {
        state.map?.addOnCameraMoveStartedListener { reason ->
            if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved = true
        }
    }

    // Marges de la caméra (zones mortes), puis cadrage : la personne demandée,
    // sinon toute la famille. Changer les marges interrompt une animation en
    // cours (d'où l'ancien « pas de zoom sur la personne ») : on recadre donc
    // après chaque changement de marges tant que l'utilisateur n'a pas bougé.
    LaunchedEffect(state.map, insets, entries.size) {
        val m = state.map ?: return@LaunchedEffect
        // Seulement si les marges laissent une vraie zone visible (sinon cadrages invalides).
        if (insets.top + insets.bottom <= screenHeightPx * 0.8) {
            runCatching {
                m.moveCamera(
                    CameraUpdateFactory.paddingTo(insets.side.toDouble(), insets.top.toDouble(), insets.side.toDouble(), insets.bottom.toDouble()),
                )
            }
        }
        if (userMoved || entries.isEmpty()) return@LaunchedEffect
        val target = entries.firstOrNull { it.id == focusId }
        runCatching {
            if (target != null) {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(target.latLng, 16.0), 500)
            } else {
                frameAll(m, entries.map { it.latLng }, insets = insets)
            }
        }
    }

    // Faire glisser les fiches → la carte se recentre sur la personne affichée.
    LaunchedEffect(state.map) {
        val m = state.map ?: return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { page ->
                userMoved = true
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
            FilledTonalIconButton(onClick = { state.map?.let { frameAll(it, entries.map { e -> e.latLng }, insets = insets) } }) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Voir tout le monde")
            }
        }

        // SOS en cours : bandeau rouge, toucher = centrer sur la personne.
        val sosEntries = entries.filter { it.id in sosIds }
        if (sosEntries.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sosEntries.forEach { e ->
                    Card(
                        onClick = {
                            val index = entries.indexOf(e)
                            scope.launch { pagerState.animateScrollToPage(index) }
                            state.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(e.latLng, 16.0))
                        },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = Danger, contentColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(e.name, size = 40.dp, photo = photos[e.id], colorHex = e.colorHex)
                            Column(Modifier.weight(1f)) {
                                Text("${e.name} a besoin d'aide", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text("SOS actif · touche pour centrer", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                            }
                            if (onDismissSos != null) {
                                FilledTonalIconButton(
                                    onClick = { onDismissSos(e.id) },
                                    colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.22f),
                                        contentColor = Color.White,
                                    ),
                                ) { Icon(Icons.Default.Close, contentDescription = "Fermer l'alerte (j'ai vu)") }
                            }
                        }
                    }
                }
            }
        }

        if (entries.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 28.dp),
                pageSpacing = 12.dp,
                // Hauteur commune à toutes les fiches, alignées en bas.
                verticalAlignment = Alignment.Bottom,
                // Toutes les fiches sont mesurées (même hors écran) pour une hauteur commune.
                beyondViewportPageCount = entries.size,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)
                    .height(cardHeight),
            ) { page ->
                // La liste peut changer pendant le défilement (position reçue en direct).
                val entry = entries.getOrNull(page) ?: return@HorizontalPager
                EntryCard(
                    place = placeOf[entry.id],
                    placeLabel = labels[entry.id],
                    entry = entry,
                    photo = if (entry.isMe) ownMember?.let { photos[it.id] } else photos[entry.id],
                    color = Color(colors[entry.id] ?: 0xFF5B5BD6.toInt()),
                    trace = mapTraces[entry.id].orEmpty(),
                    me = me,
                    actions = actions,
                    arrival = etas[entry.id],
                    onNeededHeight = { px ->
                        val old = neededHeights[entry.id]
                        if (old == null || kotlin.math.abs(old - px) > 2) neededHeights[entry.id] = px
                    },
                )
            }
        }
    }
}

/** Fiche d'une personne, en bas de la carte. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(
    place: fr.kinwaypoint.app.net.Place? = null,
    placeLabel: String? = null,
    entry: MapEntry,
    photo: Bitmap?,
    color: Color,
    trace: List<TracePoint>,
    me: Location?,
    actions: PlatformActions,
    arrival: Eta.Arrival? = null,
    /** Hauteur (px) dont la fiche a besoin pour tout afficher sans défiler. */
    onNeededHeight: (Int) -> Unit = {},
) {
    var cardPx by remember { mutableStateOf(0) }
    var viewportPx by remember { mutableStateOf(0) }
    var contentPx by remember { mutableStateOf(0) }
    LaunchedEffect(cardPx, viewportPx, contentPx) {
        if (cardPx > 0 && viewportPx > 0 && contentPx > 0) onNeededHeight(cardPx - viewportPx + contentPx)
    }
    Card(
        modifier = Modifier.fillMaxWidth().fillMaxHeight().onSizeChanged { cardPx = it.height },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        // Liseré de la couleur du profil : la même que son tracé.
        Box(Modifier.fillMaxWidth().height(5.dp).background(color))
        Column(Modifier.fillMaxSize().padding(18.dp)) {
        // Contenu trop long (beaucoup de pastilles) : il défile ; le bouton reste en bas.
        Column(
            Modifier.weight(1f)
                .onSizeChanged { viewportPx = it.height }
                .verticalScroll(rememberScrollState())
                // Mesurée après le défilement : hauteur naturelle du contenu.
                .onSizeChanged { contentPx = it.height },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (entry.isMe && photo == null) {
                    IconBadge(Icons.Default.MyLocation, color, size = 48.dp)
                } else {
                    Avatar(entry.name, size = 48.dp, photo = photo, colorHex = entry.colorHex)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (entry.isMe) "Ma position" else entry.name, style = MaterialTheme.typography.titleMedium)
                    if (place != null && placeLabel != null && placeLabel != "SOS") {
                        Text("${placeEmoji(place.icon)} $placeLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                // Pas du jour et déplacement en cours.
                activityLabel(member?.position?.activity, member?.position?.kmh)?.let { StatusPill(it, Warning) }
                // État du téléphone : sonnerie coupée, Wi-Fi.
                when (member?.position?.ringer) {
                    "silent" -> StatusPill("Silencieux", Warning, icon = Icons.AutoMirrored.Filled.VolumeOff)
                    "vibrate" -> StatusPill("Vibreur", Muted, icon = Icons.Default.Vibration)
                }
                member?.position?.wifi?.let { StatusPill(it, Muted, icon = Icons.Default.Wifi) }
                if (trace.size >= 2) {
                    StatusPill(formatDistance(traceLength(trace)) + " aujourd'hui", color, icon = Icons.Default.Route)
                }
            }
            arrival?.let {
                Text(
                    "${placeEmoji(it.place.icon)} En route vers ${it.place.name} · arrivée dans ~${it.minutes} min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Objectif de pas du jour.
            entry.member?.let { m -> m.position?.steps?.let { StepGoalBar(it, m.stepGoal, color) } }
        }
            // Le bouton reste en bas, à la même place sur toutes les fiches.
            if (!entry.isMe) {
                Spacer(Modifier.height(10.dp))
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
