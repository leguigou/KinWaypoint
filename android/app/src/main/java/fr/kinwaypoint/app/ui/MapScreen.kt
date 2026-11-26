package fr.kinwaypoint.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.kinwaypoint.app.net.Member
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Fond de carte vectoriel OpenStreetMap (OpenFreeMap : gratuit, sans clé, sans compte). */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Secours si OpenFreeMap est injoignable : tuiles raster OpenStreetMap. */
private const val FALLBACK_STYLE = """
{"version":8,
 "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
   "tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]}
"""

private const val SOURCE_ID = "family"
private const val AVATAR_LAYER = "family-avatar"
private const val ME_ID = -1L
private const val COLOR_ONLINE = "#12B886"
private const val COLOR_OFFLINE = "#9AA0B4"
private const val COLOR_ME = "#5B5BD6"

/** Un point affiché : un membre de la famille, ou « Moi » (ce téléphone). */
private data class MapEntry(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val member: Member?,
) {
    val isMe get() = id == ME_ID
    val latLng get() = LatLng(latitude, longitude)
}

/**
 * Carte de la famille.
 * - Au-dessus de chaque point GPS : l'avatar (photo, ou initiale par défaut).
 * - En bas : une carte par personne, à faire glisser ; la carte suit.
 * - Toucher un avatar sur la carte fait défiler jusqu'à sa fiche.
 */
@Composable
fun FamilyMapScreen(
    members: List<Member>,
    photos: Map<Long, Bitmap>,
    focusId: Long?,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val me = rememberMyLocation(enabled = permissions.fineLocation)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var framed by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    // « Moi » en dernier : sa position arrive après coup, elle ne doit pas décaler les pages.
    val entries: List<MapEntry> = buildList {
        members.forEach { m -> m.position?.let { add(MapEntry(m.id, m.name, it.latitude, it.longitude, m)) } }
        me?.let { add(MapEntry(ME_ID, "Moi", it.latitude, it.longitude, null)) }
    }
    val currentEntries by rememberUpdatedState(entries)

    val pagerState = rememberPagerState(
        initialPage = entries.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
    ) { entries.size }
    val selectedId = entries.getOrNull(pagerState.currentPage)?.id

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                // Mention OpenStreetMap (obligatoire) en haut, sous la barre : les fiches occupent le bas.
                val d = resources.displayMetrics.density
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.attributionGravity = android.view.Gravity.TOP or android.view.Gravity.START
                m.uiSettings.setAttributionMargins((16 * d).toInt(), (96 * d).toInt(), 0, 0)
                m.cameraPosition = CameraPosition.Builder().target(LatLng(46.6, 2.4)).zoom(5.0).build()
                map = m
                var fallbackTried = false
                addOnDidFailLoadingMapListener {
                    if (!fallbackTried) {
                        fallbackTried = true
                        m.setStyle(Style.Builder().fromJson(FALLBACK_STYLE)) { st -> style = prepare(st) }
                    }
                }
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st -> style = prepare(st) }
            }
        }
    }

    // Cycle de vie de la carte calé sur celui de l'écran.
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Toucher un avatar → sa fiche passe au premier plan.
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        m.addOnMapClickListener { point ->
            val screen = m.projection.toScreenLocation(point)
            val id = m.queryRenderedFeatures(screen, AVATAR_LAYER).firstOrNull()
                ?.getNumberProperty("id")?.toLong() ?: return@addOnMapClickListener false
            val index = currentEntries.indexOfFirst { it.id == id }
            if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
            true
        }
    }

    // Images des avatars (photo ou initiale), régénérées quand une photo ou un statut change.
    val markerKey = entries.map { Triple(it.id, it.name, it.member?.online) } to photos.keys + photos.values.map { it.generationId }
    LaunchedEffect(style, markerKey) {
        val st = style ?: return@LaunchedEffect
        entries.forEach { e ->
            val bitmap = markerBitmap(
                context = context,
                name = e.name,
                photo = photos[e.id],
                gradient = if (e.isMe) listOf(0xFF5B5BD6.toInt(), 0xFF8B5CF6.toInt()) else avatarColors(e.name).map { it.toArgb() },
                status = when {
                    e.isMe -> null
                    e.member?.online == true -> 0xFF12B886.toInt()
                    else -> 0xFF9AA0B4.toInt()
                },
                isMe = e.isMe,
            )
            st.addImage(iconName(e.id), bitmap)
        }
    }

    // Données géographiques : positions, avatar sélectionné mis en avant.
    LaunchedEffect(style, entries, selectedId, markerKey) {
        val st = style ?: return@LaunchedEffect
        val features = entries.map { e ->
            Feature.fromGeometry(Point.fromLngLat(e.longitude, e.latitude)).apply {
                addNumberProperty("id", e.id)
                addStringProperty("name", e.name)
                addStringProperty("icon", iconName(e.id))
                addStringProperty(
                    "color",
                    when {
                        e.isMe -> COLOR_ME
                        e.member?.online == true -> COLOR_ONLINE
                        else -> COLOR_OFFLINE
                    },
                )
                addBooleanProperty("selected", e.id == selectedId)
                addNumberProperty("sort", if (e.id == selectedId) 1 else 0)
            }
        }
        st.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Cadrage initial : la personne demandée, sinon toute la famille.
    LaunchedEffect(map, entries.size) {
        val m = map ?: return@LaunchedEffect
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
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
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
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

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
                Text(
                    "Ma famille",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (me == null) {
                FilledTonalIconButton(onClick = actions.requestLocation) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Afficher ma position")
                }
            }
            FilledTonalIconButton(onClick = { map?.let { frameAll(it, entries.map { e -> e.latLng }) } }) {
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
                EntryCard(entry = entry, photo = photos[entry.id], me = me, actions = actions)
            }
        }
    }
}

/** Fiche d'une personne, en bas de la carte. */
@Composable
private fun EntryCard(entry: MapEntry, photo: Bitmap?, me: Location?, actions: PlatformActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (entry.isMe) {
                    IconBadge(Icons.Default.MyLocation, MaterialTheme.colorScheme.primary, size = 48.dp)
                } else {
                    Avatar(entry.name, size = 48.dp, photo = photo)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

private fun formatDistance(meters: Float): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 10_000 -> String.format(java.util.Locale.FRANCE, "%.1f km", meters / 1000)
    else -> "${(meters / 1000).toInt()} km"
}

private fun iconName(id: Long) = "avatar-$id"

/** Ajoute au style la source et les calques des marqueurs. */
private fun prepare(style: Style): Style {
    if (style.getSource(SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(SOURCE_ID))
        // Point GPS exact, sous la pointe de l'avatar.
        style.addLayer(
            CircleLayer("family-dot", SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        // Avatar posé au-dessus du point (ancré par sa pointe), plus grand s'il est sélectionné.
        style.addLayer(
            SymbolLayer(AVATAR_LAYER, SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(
                    Expression.switchCase(
                        Expression.toBool(Expression.get("selected")),
                        Expression.literal(1.15f),
                        Expression.literal(0.9f),
                    ),
                ),
                PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("sort"))),
            ),
        )
    }
    return style
}

/**
 * Dessine un marqueur : rond blanc avec ombre, photo ou initiale sur dégradé,
 * pastille d'état, et une pointe en bas qui désigne la position exacte.
 */
private fun markerBitmap(
    context: Context,
    name: String,
    photo: Bitmap?,
    gradient: List<Int>,
    status: Int?,
    isMe: Boolean,
): Bitmap {
    val d = context.resources.displayMetrics.density
    val circle = 52 * d
    val border = 3 * d
    val pointer = 10 * d
    val pad = 5 * d
    val w = (circle + pad * 2).toInt()
    val h = (pad + circle + pointer).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.density = context.resources.displayMetrics.densityDpi
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val cy = pad + circle / 2

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(3 * d, 0f, 1 * d, 0x55000000)
    }
    val tip = Path().apply {
        moveTo(cx - 8 * d, cy + circle / 2 - 6 * d)
        lineTo(cx + 8 * d, cy + circle / 2 - 6 * d)
        lineTo(cx, h.toFloat())
        close()
    }
    canvas.drawPath(tip, white)
    canvas.drawCircle(cx, cy, circle / 2 - d, white)

    val r = circle / 2 - border - d
    if (photo != null) {
        val save = canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        canvas.drawBitmap(photo, null, RectF(cx - r, cy - r, cx + r, cy + r), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(save)
    } else {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, gradient[0], gradient[1], Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = if (isMe) 14 * d else 22 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val label = if (isMe) "Moi" else name.trim().firstOrNull()?.uppercase() ?: "?"
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    if (status != null) {
        val dot = 6 * d
        val dx = cx + r * 0.72f
        val dy = cy + r * 0.72f
        canvas.drawCircle(dx, dy, dot + 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        canvas.drawCircle(dx, dy, dot, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = status })
    }
    return bitmap
}

private fun frameAll(map: MapLibreMap, points: List<LatLng>) {
    if (points.isEmpty()) return
    val bounds = LatLngBounds.Builder().includes(points).build()
    // Points quasi confondus : un cadrage « bounds » zoomerait au maximum.
    if (points.size == 1 || bounds.latitudeSpan < 0.002 && bounds.longitudeSpan < 0.002) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 15.0))
    } else {
        // Marge basse plus grande : les fiches occupent le bas de l'écran.
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120, 220, 120, 520))
    }
}

/** Position de ce téléphone, mise à jour toutes les 10 s tant que la carte est ouverte. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberMyLocation(enabled: Boolean): Location? {
    val context = LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }

    DisposableEffect(enabled) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Méthodes toutes implémentées : abstraites jusqu'à Android 10.
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                location = l
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Requis jusqu'à Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        if (enabled) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { location = it }
            providers.forEach { p ->
                runCatching { manager.requestLocationUpdates(p, 10_000L, 5f, listener, Looper.getMainLooper()) }
            }
        }
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
    return location
}
