package com.cityguide.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cityguide.NavState
import com.cityguide.data.City
import com.cityguide.data.LatLon
import com.cityguide.data.Place
import com.cityguide.data.Route
import com.cityguide.domain.Instructions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.MapLibre
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
import kotlin.math.hypot

/** Стилі карти OpenFreeMap (безкоштовні, без ключів): світлі й темні. */
enum class MapStyle(val url: String, val label: String, val dark: Boolean) {
    LIBERTY("https://tiles.openfreemap.org/styles/liberty", "Liberty", false),
    BRIGHT("https://tiles.openfreemap.org/styles/bright", "Bright", false),
    POSITRON("https://tiles.openfreemap.org/styles/positron", "Positron", false),
    DARK("https://tiles.openfreemap.org/styles/dark", "Dark", true),
    FIORD("https://tiles.openfreemap.org/styles/fiord", "Fiord", true);

    companion object {
        fun forTheme(dark: Boolean): List<MapStyle> = entries.filter { it.dark == dark }
        fun default(dark: Boolean): MapStyle = if (dark) DARK else LIBERTY
    }
}
private const val ROUTE_SRC = "route-src"
private const val TRAVELED_SRC = "traveled-src"
private const val PLACES_SRC = "places-src"
private const val FOOD_SRC = "food-src"
private const val STOPS_SRC = "stops-src"
private const val MANEUVER_SRC = "maneuver-src"
private const val SELECTED_SRC = "selected-src"
private const val USER_SRC = "user-src"
private const val START_SRC = "start-src"
private const val TRANSIT_SRC = "transit-src"
private const val GREEN = "#2E7D32"
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

private const val TEAL = "#00796B"
private const val GREY = "#9E9E9E"
private const val ORANGE = "#FF6F00"
private const val BLUE = "#4285F4"

@Composable
fun MapPane(
    city: City?,
    places: List<Place>,
    foods: List<Place>,
    route: Route?,
    selected: Place?,
    nav: NavState,
    start: LatLon?,
    pickingStart: Boolean,
    startLabel: String,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    onPlaceClick: (String) -> Unit,
    onMapPick: (Double, Double) -> Unit,
    onUserPan: () -> Unit,
    styleUrl: String,
    onStyleFailed: () -> Unit,
    onMapLongPress: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = context.resources.displayMetrics.density
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleVersion by remember { mutableIntStateOf(0) }
    val dark = LocalDarkTheme.current
    val latestPlaces by rememberUpdatedState(places + foods)
    val latestOnClick by rememberUpdatedState(onPlaceClick)
    val latestOnPan by rememberUpdatedState(onUserPan)
    val latestPicking by rememberUpdatedState(pickingStart)
    val latestOnPick by rememberUpdatedState(onMapPick)
    val latestStyleFailed by rememberUpdatedState(onStyleFailed)
    val latestLongPress by rememberUpdatedState(onMapLongPress)

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false

            // Тап по карті: шукаємо найближче місце в межах ~28dp
            m.addOnMapClickListener { latLng ->
                if (latestPicking) {
                    latestOnPick(latLng.latitude, latLng.longitude)
                    return@addOnMapClickListener true
                }
                val tap = m.projection.toScreenLocation(latLng)
                val limit = density * 28f
                val hit = latestPlaces
                    .map { p ->
                        val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
                        p to hypot((pt.x - tap.x).toDouble(), (pt.y - tap.y).toDouble())
                    }
                    .minByOrNull { it.second }
                    ?.takeIf { it.second < limit }
                if (hit != null) {
                    latestOnClick(hit.first.id)
                    true
                } else {
                    false
                }
            }

            // Довге натискання: нова точка пошуку й старту
            m.addOnMapLongClickListener { latLng ->
                latestLongPress(latLng.latitude, latLng.longitude)
                true
            }

            // Стиль не завантажився (наприклад, немає такого URL): повертаємося до типового
            mapView.addOnDidFailLoadingMapListener { _ -> latestStyleFailed() }

            // Користувач зрушив карту руками: у навігації вимикаємо слідування за ним
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    latestOnPan()
                }
            }

            map = m
        }
    }

    // Стиль карти залежить від теми: після перемикання шари додаються заново
    LaunchedEffect(map, dark, styleUrl) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(styleUrl) { style ->
            installLayers(style, dark, startLabel)
            styleVersion++
        }
    }

    // Відступи камери й підписів під плаваючі панелі
    LaunchedEffect(map, topPaddingPx, bottomPaddingPx) {
        val m = map ?: return@LaunchedEffect
        m.setPadding(0, topPaddingPx, 0, bottomPaddingPx)
        m.uiSettings.setAttributionMargins(
            (12 * density).toInt(), 0, 0, bottomPaddingPx + (8 * density).toInt()
        )
    }

    // Місця, заклади, вибране
    LaunchedEffect(styleVersion, places, foods, selected) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(PLACES_SRC)?.setGeoJson(placesJson(places))
        style.getSourceAs<GeoJsonSource>(FOOD_SRC)?.setGeoJson(placesJson(foods))
        style.getSourceAs<GeoJsonSource>(SELECTED_SRC)?.setGeoJson(selectedJson(selected))
    }

    LaunchedEffect(styleVersion, start) {
        val style = map?.style ?: return@LaunchedEffect
        val json = if (start == null) EMPTY_FC
        else featureCollection(listOf(pointFeature(start.lat, start.lon)))
        style.getSourceAs<GeoJsonSource>(START_SRC)?.setGeoJson(json)
    }

    // Маршрут, зупинки, повороти, користувач (оновлюється з кожним GPS-фіксом)
    LaunchedEffect(styleVersion, route, nav) {
        val style = map?.style ?: return@LaunchedEffect
        val (traveled, remaining) = routeSplit(route, nav)
        style.getSourceAs<GeoJsonSource>(TRAVELED_SRC)?.setGeoJson(lineJson(traveled))
        style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(lineJson(remaining))
        style.getSourceAs<GeoJsonSource>(TRANSIT_SRC)?.setGeoJson(transitJson(route))
        style.getSourceAs<GeoJsonSource>(STOPS_SRC)?.setGeoJson(stopsJson(route, nav))
        style.getSourceAs<GeoJsonSource>(MANEUVER_SRC)?.setGeoJson(maneuversJson(route, nav))
        style.getSourceAs<GeoJsonSource>(USER_SRC)?.setGeoJson(userJson(nav))
    }

    // Камера: на місто, на весь маршрут, на вибране місце
    LaunchedEffect(map, city) {
        val m = map ?: return@LaunchedEffect
        if (city != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(city.lat, city.lon), 13.5))
        }
    }
    LaunchedEffect(map, route) {
        val m = map ?: return@LaunchedEffect
        if (nav.active) return@LaunchedEffect
        val points = route?.geometry?.map { LatLng(it.lat, it.lon) }
        if (points != null && points.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(points).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        }
    }
    LaunchedEffect(map, selected) {
        val m = map ?: return@LaunchedEffect
        if (selected != null) {
            m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(selected.lat, selected.lon)))
        }
    }

    // Навігація: камера слідує за користувачем, повернута за напрямком руху
    LaunchedEffect(map, nav.user, nav.follow, nav.active) {
        val m = map ?: return@LaunchedEffect
        val user = nav.user
        if (nav.active && nav.follow && user != null) {
            val position = CameraPosition.Builder()
                .target(LatLng(user.lat, user.lon))
                .zoom(17.0)
                .bearing(nav.bearing.toDouble())
                .tilt(45.0)
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(position), 900)
        }
    }
    // Після завершення навігації повертаємо карту в нормальне положення
    LaunchedEffect(nav.active) {
        val m = map ?: return@LaunchedEffect
        if (!nav.active) {
            val position = CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(position))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Додає джерела й шари застосунку поверх щойно завантаженого стилю карти. */
private fun installLayers(style: Style, dark: Boolean, startLabel: String) {
    style.addSource(GeoJsonSource(TRAVELED_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(ROUTE_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(PLACES_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(FOOD_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(STOPS_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(MANEUVER_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(SELECTED_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(USER_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(START_SRC, EMPTY_FC))
    style.addSource(GeoJsonSource(TRANSIT_SRC, EMPTY_FC))

    // Пройдений відрізок (сірий) під активним маршрутом
    style.addLayer(
        LineLayer("traveled-layer", TRAVELED_SRC).withProperties(
            PropertyFactory.lineColor(Color.parseColor(GREY)),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    style.addLayer(
        LineLayer("route-layer", ROUTE_SRC).withProperties(
            PropertyFactory.lineColor(Color.parseColor(if (dark) "#26C6B0" else TEAL)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    // Ділянки, де треба їхати громадським транспортом: фіолетова лінія поверх маршруту
    style.addLayer(
        LineLayer("transit-layer", TRANSIT_SRC).withProperties(
            PropertyFactory.lineColor(Color.parseColor(if (dark) "#CE93D8" else "#7B1FA2")),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    // Місця: колір залежить від категорії
    style.addLayer(
        CircleLayer("places-layer", PLACES_SRC).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f)
        )
    )
    // Заклади харчування: менші маркери
    style.addLayer(
        CircleLayer("food-layer", FOOD_SRC).withProperties(
            PropertyFactory.circleRadius(5.5f),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f)
        )
    )
    // Зупинки маршруту: колір залежить від стану (пройдена, наступна, попереду)
    style.addLayer(
        CircleLayer("stops-layer", STOPS_SRC).withProperties(
            PropertyFactory.circleRadius(13f),
            PropertyFactory.circleColor(Expression.get("c")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f)
        )
    )
    style.addLayer(
        SymbolLayer("numbers-layer", STOPS_SRC).withProperties(
            PropertyFactory.textField(Expression.get("n")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor(Color.WHITE),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true)
        )
    )
    // Позначки поворотів
    style.addLayer(
        CircleLayer("maneuver-layer", MANEUVER_SRC).withProperties(
            PropertyFactory.circleRadius(12f),
            PropertyFactory.circleColor(Color.WHITE),
            PropertyFactory.circleStrokeColor(Color.parseColor(TEAL)),
            PropertyFactory.circleStrokeWidth(2.5f)
        )
    )
    style.addLayer(
        SymbolLayer("maneuver-arrows-layer", MANEUVER_SRC).withProperties(
            PropertyFactory.textField(Expression.get("a")),
            PropertyFactory.textSize(15f),
            PropertyFactory.textColor(Color.parseColor(TEAL)),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true)
        )
    )
    style.addLayer(
        CircleLayer("selected-layer", SELECTED_SRC).withProperties(
            PropertyFactory.circleRadius(17f),
            PropertyFactory.circleColor(Color.TRANSPARENT),
            PropertyFactory.circleStrokeColor(Color.parseColor(ORANGE)),
            PropertyFactory.circleStrokeWidth(3.5f)
        )
    )
    // Положення користувача: напівпрозорий ореол і крапка
    style.addLayer(
        CircleLayer("user-halo-layer", USER_SRC).withProperties(
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleColor(Color.parseColor(BLUE)),
            PropertyFactory.circleOpacity(0.25f)
        )
    )
    style.addLayer(
        CircleLayer("user-dot-layer", USER_SRC).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Color.parseColor(BLUE)),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f)
        )
    )
    // Старт маршруту, заданий користувачем
    style.addLayer(
        CircleLayer("start-layer", START_SRC).withProperties(
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleColor(Color.parseColor(GREEN)),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f)
        )
    )
    style.addLayer(
        SymbolLayer("start-label-layer", START_SRC).withProperties(
            PropertyFactory.textField(startLabel),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(Color.parseColor(if (dark) "#81C784" else GREEN)),
            PropertyFactory.textHaloColor(Color.parseColor(if (dark) "#101414" else "#FFFFFF")),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAllowOverlap(true)
        )
    )
    // Підписи з'являються при наближенні
    style.addLayer(
        SymbolLayer("labels-layer", PLACES_SRC).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(Color.parseColor(if (dark) "#E8EEEC" else "#1B1F1E")),
            PropertyFactory.textHaloColor(Color.parseColor(if (dark) "#101414" else "#FFFFFF")),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
            PropertyFactory.textOptional(true)
        ).apply { setMinZoom(14f) }
    )
    style.addLayer(
        SymbolLayer("food-labels-layer", FOOD_SRC).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(Color.parseColor(if (dark) "#FFCC80" else "#5D4037")),
            PropertyFactory.textHaloColor(Color.parseColor(if (dark) "#101414" else "#FFFFFF")),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
            PropertyFactory.textOptional(true)
        ).apply { setMinZoom(15f) }
    )
}

// --- GeoJSON ---

private fun featureCollection(features: List<JsonObject>): String = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(features))
}.toString()

private fun pointFeature(lat: Double, lon: Double, props: Map<String, String> = emptyMap()) =
    buildJsonObject {
        put("type", "Feature")
        putJsonObject("geometry") {
            put("type", "Point")
            putJsonArray("coordinates") {
                add(lon)
                add(lat)
            }
        }
        putJsonObject("properties") {
            props.forEach { (k, v) -> put(k, v) }
        }
    }

private fun placesJson(places: List<Place>): String =
    featureCollection(
        places.map {
            pointFeature(
                it.lat, it.lon,
                mapOf("id" to it.id, "name" to it.name, "color" to it.category.color)
            )
        }
    )

private fun selectedJson(place: Place?): String =
    if (place == null) EMPTY_FC else featureCollection(listOf(pointFeature(place.lat, place.lon)))

/** Зупинки: пройдені сірі, наступна помаранчева, решта бірюзові. */
private fun stopsJson(route: Route?, nav: NavState): String =
    featureCollection(
        route?.stops.orEmpty().mapIndexed { i, p ->
            val color = when {
                !nav.active -> TEAL
                i < nav.nextStopIndex -> GREY
                i == nav.nextStopIndex -> ORANGE
                else -> TEAL
            }
            pointFeature(p.lat, p.lon, mapOf("n" to (i + 1).toString(), "c" to color))
        }
    )

/** Найближчі повороти, які ще попереду (початок і прибуття не показуємо). */
private fun maneuversJson(route: Route?, nav: NavState): String {
    if (route == null || !nav.active) return EMPTY_FC
    val features = route.steps
        .withIndex()
        .filter { (i, s) -> i >= nav.nextStepIndex && s.type != "depart" && s.type != "arrive" }
        .take(8)
        .map { (_, s) -> pointFeature(s.lat, s.lon, mapOf("a" to Instructions.mapArrow(s))) }
    return featureCollection(features)
}

private fun userJson(nav: NavState): String {
    val user = nav.user
    return if (nav.active && user != null) {
        featureCollection(listOf(pointFeature(user.lat, user.lon)))
    } else {
        EMPTY_FC
    }
}

/** Розбиває маршрут на пройдену й залишену частини за положенням користувача. */
private fun routeSplit(route: Route?, nav: NavState): Pair<List<LatLon>, List<LatLon>> {
    val geometry = route?.geometry ?: return emptyList<LatLon>() to emptyList()
    val snapped = nav.snapped
    if (!nav.active || snapped == null || geometry.size < 2) return emptyList<LatLon>() to geometry
    val i = nav.snapSegment.coerceIn(0, geometry.size - 2)
    val traveled = geometry.subList(0, i + 1) + snapped
    val remaining = listOf(snapped) + geometry.subList(i + 1, geometry.size)
    return traveled to remaining
}

private fun transitJson(route: Route?): String {
    val lines = route?.transit.orEmpty().flatMap { leg -> leg.segments.map { it.geometry } }
        .filter { it.size >= 2 }
    if (lines.isEmpty()) return EMPTY_FC
    val features = lines.map { points ->
        buildJsonObject {
            put("type", "Feature")
            putJsonObject("geometry") {
                put("type", "LineString")
                putJsonArray("coordinates") {
                    points.forEach { p ->
                        add(JsonArray(listOf(JsonPrimitive(p.lon), JsonPrimitive(p.lat))))
                    }
                }
            }
            putJsonObject("properties") {}
        }
    }
    return featureCollection(features)
}

private fun lineJson(points: List<LatLon>): String {
    if (points.size < 2) return EMPTY_FC
    val feature = buildJsonObject {
        put("type", "Feature")
        putJsonObject("geometry") {
            put("type", "LineString")
            putJsonArray("coordinates") {
                points.forEach { p ->
                    add(JsonArray(listOf(JsonPrimitive(p.lon), JsonPrimitive(p.lat))))
                }
            }
        }
        putJsonObject("properties") {}
    }
    return featureCollection(listOf(feature))
}
