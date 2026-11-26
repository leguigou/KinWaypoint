package fr.kinwaypoint.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.kinwaypoint.app.net.Member
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
private const val ME_ID = -1L
private const val COLOR_ME = "#1565C0"
private const val COLOR_ONLINE = "#2E7D32"
private const val COLOR_OFFLINE = "#757575"

/**
 * Carte de la famille : chaque membre (vert = en ligne, gris = hors ligne) et
 * la position de ce téléphone (bleu, « Moi »).
 */
@Composable
fun FamilyMapScreen(
    members: List<Member>,
    focusId: Long?,
    permissions: PermissionSnapshot,
    actions: PlatformActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val me = rememberMyLocation(enabled = permissions.fineLocation)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var selected by remember { mutableStateOf(focusId) }
    var framed by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
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

    val points: List<Pair<Long, LatLng>> = buildList {
        members.forEach { m -> m.position?.let { add(m.id to LatLng(it.latitude, it.longitude)) } }
        me?.let { add(ME_ID to LatLng(it.latitude, it.longitude)) }
    }

    // Données affichées : rafraîchies à chaque actualisation des membres ou de ma position.
    LaunchedEffect(style, members, me) {
        val st = style ?: return@LaunchedEffect
        val features = buildList {
            members.forEach { m ->
                val p = m.position ?: return@forEach
                add(
                    Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
                        addStringProperty("name", m.name)
                        addStringProperty("color", if (m.online) COLOR_ONLINE else COLOR_OFFLINE)
                    },
                )
            }
            me?.let {
                add(
                    Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply {
                        addStringProperty("name", "Moi")
                        addStringProperty("color", COLOR_ME)
                    },
                )
            }
        }
        st.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Cadrage initial : le membre demandé, sinon toute la famille.
    LaunchedEffect(map, points.size) {
        val m = map ?: return@LaunchedEffect
        if (framed || points.isEmpty()) return@LaunchedEffect
        val target = points.firstOrNull { it.first == focusId }?.second
        if (target != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16.0))
        } else {
            frameAll(m, points.map { it.second })
        }
        framed = true
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier.statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text(
                    "Carte de la famille",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val member = members.firstOrNull { it.id == selected }
            member?.position?.let { p ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(member.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(
                            "Position du ${formatDateTime(p.fixTime)}" + (p.battery?.let { " · 🔋 $it %" } ?: ""),
                            fontSize = 13.sp,
                            color = Muted,
                        )
                        OutlinedButton(onClick = { actions.openMap(p.latitude, p.longitude, member.name) }) {
                            Text("Itinéraire (autre appli)")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { map?.let { frameAll(it, points.map { p -> p.second }) }; selected = null },
                    label = { Text("Tout le monde") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color.White),
                )
                if (me != null) {
                    AssistChip(
                        onClick = {
                            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.latitude, me.longitude), 16.0))
                            selected = null
                        },
                        label = { Text("● Moi", color = Color(0xFF1565C0)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color.White),
                    )
                } else {
                    AssistChip(
                        onClick = actions.requestLocation,
                        label = { Text("Afficher ma position") },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color.White),
                    )
                }
                members.forEach { m ->
                    val p = m.position ?: return@forEach
                    AssistChip(
                        onClick = {
                            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 16.0))
                            selected = m.id
                        },
                        label = { Text("● ${m.name}", color = if (m.online) Ok else Idle) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color.White),
                    )
                }
            }
        }
    }
}

/** Ajoute au style la source et les calques des marqueurs. */
private fun prepare(style: Style): Style {
    if (style.getSource(SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(SOURCE_ID))
        style.addLayer(
            CircleLayer("family-dot", SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            SymbolLayer("family-name", SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(14f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textColor("#1F2A27"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ),
        )
    }
    return style
}

private fun frameAll(map: MapLibreMap, points: List<LatLng>) {
    if (points.isEmpty()) return
    val bounds = LatLngBounds.Builder().includes(points).build()
    // Points quasi confondus : un cadrage « bounds » zoomerait au maximum.
    if (points.size == 1 || bounds.latitudeSpan < 0.002 && bounds.longitudeSpan < 0.002) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 15.0))
    } else {
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160))
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
