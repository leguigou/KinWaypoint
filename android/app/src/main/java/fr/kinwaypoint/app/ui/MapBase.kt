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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.geojson.Polygon
import fr.kinwaypoint.app.net.Place
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
internal const val PLACE_AREA_SOURCE = "places-area"
internal const val PLACE_PIN_SOURCE = "places-pin"

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

    // Lieux : zone teintée + contour, sous tout le reste.
    style.addSource(GeoJsonSource(PLACE_AREA_SOURCE))
    style.addLayer(
        FillLayer("places-fill", PLACE_AREA_SOURCE).withProperties(
            PropertyFactory.fillColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.fillOpacity(0.12f),
        ),
    )
    style.addLayer(
        LineLayer("places-outline", PLACE_AREA_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(1.5f),
            PropertyFactory.lineOpacity(0.6f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addSource(GeoJsonSource(PLACE_PIN_SOURCE))
    style.addLayer(
        SymbolLayer("places-pin", PLACE_PIN_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
            PropertyFactory.textColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textOptional(true),
        ),
    )

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

/** Zones de la carte masquées (barre du haut, fiches du bas), en pixels. */
internal data class MapInsets(val top: Int, val bottom: Int, val side: Int)

/**
 * Cadre tous les points dans la partie visible de la carte : les marges
 * (« zones mortes ») tiennent compte des éléments posés par-dessus.
 */
internal fun frameAll(map: MapLibreMap, points: List<LatLng>, bottomPaddingPx: Int = 520, insets: MapInsets? = null) {
    if (points.isEmpty()) return
    val pad = insets ?: MapInsets(top = 260, bottom = bottomPaddingPx, side = 120)
    val bounds = LatLngBounds.Builder().includes(points).build()
    // Points quasi confondus : un cadrage « bounds » zoomerait au maximum.
    if (points.size == 1 || bounds.latitudeSpan < 0.002 && bounds.longitudeSpan < 0.002) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 15.0))
    } else {
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad.side, pad.top, pad.side, pad.bottom))
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
    /** Texte au-dessus de l'avatar, ex. « École · 12 min » (null = aucun). */
    label: String? = null,
    /** SOS en cours : contour rouge et étiquette rouge. */
    sos: Boolean = false,
    /** Petit badge en haut à gauche de l'avatar (🚗 au-delà de 15 km/h, 🚲, 🚶). */
    badge: String? = null,
): Bitmap {
    val d = context.resources.displayMetrics.density
    val circle = 52 * d
    val border = 3 * d
    val pointer = 10 * d
    val pad = 5 * d

    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (sos) android.graphics.Color.WHITE else 0xFF16172B.toInt()
        textSize = 12 * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val labelText = label?.let { if (it.length > 28) it.take(27) + "…" else it }
    val labelW = labelText?.let { labelPaint.measureText(it) + 20 * d } ?: 0f
    val labelH = if (labelText != null) 24 * d else 0f
    val gap = if (labelText != null) 4 * d else 0f

    val w = maxOf(circle + pad * 2, labelW + pad * 2).toInt()
    val top = pad + labelH + gap
    val h = (top + circle + pointer).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.density = context.resources.displayMetrics.densityDpi
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val cy = top + circle / 2

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (sos) 0xFFE5484D.toInt() else android.graphics.Color.WHITE
        setShadowLayer(3 * d, 0f, 1 * d, 0x55000000)
    }
    if (labelText != null) {
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (sos) 0xFFE5484D.toInt() else android.graphics.Color.WHITE
            setShadowLayer(3 * d, 0f, 1 * d, 0x44000000)
        }
        val rect = RectF(cx - labelW / 2, pad, cx + labelW / 2, pad + labelH)
        canvas.drawRoundRect(rect, labelH / 2, labelH / 2, pill)
        canvas.drawText(labelText, cx, rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
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

    if (badge != null) {
        val bx = cx - r * 0.78f
        val by = cy - r * 0.78f
        canvas.drawCircle(bx, by, 11 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            setShadowLayer(2 * d, 0f, 1 * d, 0x44000000)
        })
        val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; textAlign = Paint.Align.CENTER }
        canvas.drawText(badge, bx, by - (emoji.descent() + emoji.ascent()) / 2, emoji)
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

// --- Lieux ------------------------------------------------------------------------

/** Emoji d'un type de lieu (dessiné sur la carte) et libellé. */
internal fun placeEmoji(icon: String) = PlaceIcons.firstOrNull { it.key == icon }?.emoji ?: "📍"

/** Types de lieux : clé enregistrée sur le serveur, emoji affiché, libellé proposé. */
internal data class PlaceIcon(val key: String, val emoji: String, val label: String)

internal val PlaceIcons = listOf(
    PlaceIcon("home", "🏠", "Maison"),
    PlaceIcon("school", "🏫", "École"),
    PlaceIcon("college", "🎓", "Collège / lycée"),
    PlaceIcon("work", "💼", "Travail"),
    PlaceIcon("sport", "⚽", "Sport"),
    PlaceIcon("music", "🎵", "Musique"),
    PlaceIcon("friends", "👫", "Amis"),
    PlaceIcon("family", "👵", "Famille"),
    PlaceIcon("doctor", "🏥", "Santé"),
    PlaceIcon("park", "🌳", "Parc"),
    PlaceIcon("library", "📚", "Bibliothèque"),
    PlaceIcon("shop", "🛒", "Courses"),
    PlaceIcon("food", "🍽️", "Restaurant"),
    PlaceIcon("station", "🚉", "Gare / arrêt"),
    PlaceIcon("star", "⭐", "Favori"),
    PlaceIcon("other", "📍", "Autre"),
)

/** Compatibilité avec l'ancien écran (couples clé / libellé). */
internal val PlaceTypes = PlaceIcons.map { it.key to it.label }

private fun circlePolygon(lat: Double, lon: Double, radius: Double): Polygon {
    val points = (0..64).map { i ->
        val angle = Math.toRadians(i * 360.0 / 64)
        val dLat = radius / 111_320.0 * kotlin.math.cos(angle)
        val dLon = radius / (111_320.0 * kotlin.math.cos(Math.toRadians(lat))) * kotlin.math.sin(angle)
        Point.fromLngLat(lon + dLon, lat + dLat)
    }
    return Polygon.fromLngLats(listOf(points))
}

/** Pastille ronde blanche avec l'emoji du lieu. */
private fun placeBitmap(context: Context, icon: String, colorHex: String): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (34 * d).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.density = context.resources.displayMetrics.densityDpi
    val canvas = Canvas(bitmap)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = runCatching { android.graphics.Color.parseColor(colorHex) }.getOrDefault(0xFF5B5BD6.toInt())
        setShadowLayer(2 * d, 0f, 1 * d, 0x44000000)
    }
    canvas.drawRoundRect(RectF(2 * d, 2 * d, size - 2 * d, size - 2 * d), 10 * d, 10 * d, bg)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17 * d; textAlign = Paint.Align.CENTER }
    canvas.drawText(placeEmoji(icon), size / 2f, size / 2f - (text.descent() + text.ascent()) / 2, text)
    return bitmap
}

/** Affiche les lieux sur la carte. */
internal fun Style.setPlaces(context: Context, places: List<Place>) {
    places.forEach { addImage("place-${it.id}", placeBitmap(context, it.icon, it.color)) }
    getSourceAs<GeoJsonSource>(PLACE_AREA_SOURCE)?.setGeoJson(
        FeatureCollection.fromFeatures(
            places.map {
                Feature.fromGeometry(circlePolygon(it.latitude, it.longitude, it.radius)).apply { addStringProperty("color", it.color) }
            },
        ),
    )
    getSourceAs<GeoJsonSource>(PLACE_PIN_SOURCE)?.setGeoJson(
        FeatureCollection.fromFeatures(
            places.map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
                    addStringProperty("icon", "place-${p.id}")
                    addStringProperty("name", p.name)
                    addStringProperty("color", p.color)
                }
            },
        ),
    )
}

/** Lieu dans lequel se trouve ce point (le plus proche s'il y en a plusieurs). */
internal fun placeAt(latitude: Double, longitude: Double, places: List<Place>): Place? {
    val result = FloatArray(1)
    return places.mapNotNull { p ->
        Location.distanceBetween(latitude, longitude, p.latitude, p.longitude, result)
        if (result[0] <= p.radius) p to result[0] else null
    }.minByOrNull { it.second }?.first
}

/** Depuis quand le tracé est-il dans ce lieu (en remontant le temps), ou null. */
internal fun inPlaceSince(trace: List<TracePoint>, place: Place): Long? {
    val result = FloatArray(1)
    var since: Long? = null
    for (p in trace.asReversed()) {
        Location.distanceBetween(p.latitude, p.longitude, place.latitude, place.longitude, result)
        if (result[0] > place.radius) break
        since = p.time
    }
    return since
}

/** « 12 min », « 2 h 05 ». */
internal fun shortDuration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${String.format(java.util.Locale.FRANCE, "%02d", minutes % 60)}"
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
