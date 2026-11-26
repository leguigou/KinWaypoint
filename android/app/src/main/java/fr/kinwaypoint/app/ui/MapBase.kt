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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.kinwaypoint.app.net.TracePoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
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

internal const val FAMILY_SOURCE = "family"
internal const val TRACE_SOURCE = "traces"
internal const val HIGHLIGHT_SOURCE = "highlight"
internal const val AVATAR_LAYER = "family-avatar"

/** Carte et style prêts à l'emploi, partagés par l'écran carte et l'historique. */
internal class KinMapState {
    var map by mutableStateOf<MapLibreMap?>(null)
    var style by mutableStateOf<Style?>(null)
}

/**
 * Carte MapLibre : création, cycle de vie, fond de carte avec secours, et
 * calques communs (tracés, points, avatars, point mis en avant).
 */
@Composable
internal fun KinMap(state: KinMapState, modifier: Modifier = Modifier, attributionTopDp: Int = 96) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                // Mention OpenStreetMap (obligatoire) en haut : le bas de l'écran est occupé.
                val d = resources.displayMetrics.density
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.attributionGravity = android.view.Gravity.TOP or android.view.Gravity.START
                m.uiSettings.setAttributionMargins((16 * d).toInt(), (attributionTopDp * d).toInt(), 0, 0)
                m.cameraPosition = CameraPosition.Builder().target(LatLng(46.6, 2.4)).zoom(5.0).build()
                state.map = m
                var fallbackTried = false
                addOnDidFailLoadingMapListener {
                    if (!fallbackTried) {
                        fallbackTried = true
                        m.setStyle(Style.Builder().fromJson(FALLBACK_STYLE)) { st -> state.style = prepareLayers(st) }
                    }
                }
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st -> state.style = prepareLayers(st) }
            }
        }
    }

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

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun prepareLayers(style: Style): Style {
    if (style.getSource(FAMILY_SOURCE) != null) return style

    // Tracés : liseré blanc dessous pour rester lisible sur tous les fonds.
    style.addSource(GeoJsonSource(TRACE_SOURCE))
    style.addLayer(
        LineLayer("traces-casing", TRACE_SOURCE).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("traces-line", TRACE_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )

    style.addSource(GeoJsonSource(FAMILY_SOURCE))
    // Point GPS exact, sous la pointe de l'avatar.
    style.addLayer(
        CircleLayer("family-dot", FAMILY_SOURCE).withProperties(
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    // Avatar posé au-dessus du point (ancré par sa pointe), plus grand s'il est sélectionné.
    style.addLayer(
        SymbolLayer(AVATAR_LAYER, FAMILY_SOURCE).withProperties(
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

    // Point mis en avant (historique : point touché dans la liste).
    style.addSource(GeoJsonSource(HIGHLIGHT_SOURCE))
    style.addLayer(
        CircleLayer("highlight", HIGHLIGHT_SOURCE).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    return style
}

private fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

/** Lignes des tracés : une par personne, dans sa couleur. */
internal fun Style.setTraces(traces: Map<Long, List<TracePoint>>, colors: Map<Long, Int>) {
    val features = traces.mapNotNull { (id, points) ->
        if (points.size < 2) return@mapNotNull null
        Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
            addStringProperty("color", hex(colors[id] ?: 0xFF5B5BD6.toInt()))
        }
    }
    getSourceAs<GeoJsonSource>(TRACE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

internal fun Style.setHighlight(point: TracePoint?, color: Int) {
    val features = point?.let {
        listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addStringProperty("color", hex(color)) })
    } ?: emptyList()
    getSourceAs<GeoJsonSource>(HIGHLIGHT_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** Longueur d'un tracé, en mètres. */
internal fun traceLength(points: List<TracePoint>): Float {
    val result = FloatArray(1)
    return points.zipWithNext().sumOf { (a, b) ->
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        result[0].toDouble()
    }.toFloat()
}

internal fun formatDistance(meters: Float): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters < 10_000 -> String.format(java.util.Locale.FRANCE, "%.1f km", meters / 1000)
    else -> "${(meters / 1000).toInt()} km"
}

internal fun frameAll(map: MapLibreMap, points: List<LatLng>, bottomPaddingPx: Int = 520) {
    if (points.isEmpty()) return
    val bounds = LatLngBounds.Builder().includes(points).build()
    // Points quasi confondus : un cadrage « bounds » zoomerait au maximum.
    if (points.size == 1 || bounds.latitudeSpan < 0.002 && bounds.longitudeSpan < 0.002) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 15.0))
    } else {
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120, 260, 120, bottomPaddingPx))
    }
}

/**
 * Dessine un marqueur : rond blanc avec ombre, photo ou initiale sur dégradé,
 * pastille d'état, et une pointe en bas qui désigne la position exacte.
 */
internal fun markerBitmap(
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

/** Position de ce téléphone, mise à jour toutes les 10 s tant que l'écran est affiché. */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberMyLocation(enabled: Boolean): Location? {
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
