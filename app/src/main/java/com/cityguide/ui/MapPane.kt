package com.cityguide.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.cityguide.data.City
import com.cityguide.data.Place
import com.cityguide.data.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.hypot

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SRC = "route-src"
private const val PLACES_SRC = "places-src"
private const val FOOD_SRC = "food-src"
private const val STOPS_SRC = "stops-src"
private const val SELECTED_SRC = "selected-src"
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

@Composable
fun MapPane(
    city: City?,
    places: List<Place>,
    foods: List<Place>,
    route: Route?,
    selected: Place?,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    onPlaceClick: (String) -> Unit,
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
    var styleReady by remember { mutableStateOf(false) }
    val latestPlaces by rememberUpdatedState(places + foods)
    val latestOnClick by rememberUpdatedState(onPlaceClick)

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

            m.setStyle(STYLE_URL) { style ->
                style.addSource(GeoJsonSource(ROUTE_SRC, EMPTY_FC))
                style.addSource(GeoJsonSource(PLACES_SRC, EMPTY_FC))
                style.addSource(GeoJsonSource(FOOD_SRC, EMPTY_FC))
                style.addSource(GeoJsonSource(STOPS_SRC, EMPTY_FC))
                style.addSource(GeoJsonSource(SELECTED_SRC, EMPTY_FC))

                style.addLayer(
                    LineLayer("route-layer", ROUTE_SRC).withProperties(
                        PropertyFactory.lineColor(Color.parseColor("#00796B")),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
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
                style.addLayer(
                    CircleLayer("stops-layer", STOPS_SRC).withProperties(
                        PropertyFactory.circleRadius(13f),
                        PropertyFactory.circleColor(Color.parseColor("#00796B")),
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
                style.addLayer(
                    CircleLayer("selected-layer", SELECTED_SRC).withProperties(
                        PropertyFactory.circleRadius(17f),
                        PropertyFactory.circleColor(Color.TRANSPARENT),
                        PropertyFactory.circleStrokeColor(Color.parseColor("#FF6F00")),
                        PropertyFactory.circleStrokeWidth(3.5f)
                    )
                )
                // Підписи з'являються при наближенні
                style.addLayer(
                    SymbolLayer("labels-layer", PLACES_SRC).withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(Color.parseColor("#1B1F1E")),
                        PropertyFactory.textHaloColor(Color.WHITE),
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
                        PropertyFactory.textColor(Color.parseColor("#5D4037")),
                        PropertyFactory.textHaloColor(Color.WHITE),
                        PropertyFactory.textHaloWidth(1.6f),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                        PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
                        PropertyFactory.textOptional(true)
                    ).apply { setMinZoom(15f) }
                )
                map = m
                styleReady = true
            }
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

    // Дані на карті
    LaunchedEffect(styleReady, places, foods, route, selected) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(PLACES_SRC)?.setGeoJson(placesJson(places))
        style.getSourceAs<GeoJsonSource>(FOOD_SRC)?.setGeoJson(placesJson(foods))
        style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeJson(route))
        style.getSourceAs<GeoJsonSource>(STOPS_SRC)?.setGeoJson(stopsJson(route))
        style.getSourceAs<GeoJsonSource>(SELECTED_SRC)?.setGeoJson(selectedJson(selected))
    }

    // Камера: на місто, на весь маршрут, на вибране місце
    LaunchedEffect(styleReady, city) {
        val m = map ?: return@LaunchedEffect
        if (city != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(city.lat, city.lon), 13.5))
        }
    }
    LaunchedEffect(styleReady, route) {
        val m = map ?: return@LaunchedEffect
        val points = route?.geometry?.map { LatLng(it.lat, it.lon) }
        if (points != null && points.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(points).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        }
    }
    LaunchedEffect(styleReady, selected) {
        val m = map ?: return@LaunchedEffect
        if (selected != null) {
            m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(selected.lat, selected.lon)))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
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

private fun stopsJson(route: Route?): String =
    featureCollection(
        route?.stops.orEmpty().mapIndexed { i, p ->
            pointFeature(p.lat, p.lon, mapOf("n" to (i + 1).toString()))
        }
    )

private fun routeJson(route: Route?): String {
    val geometry = route?.geometry
    if (geometry.isNullOrEmpty()) return EMPTY_FC
    val feature = buildJsonObject {
        put("type", "Feature")
        putJsonObject("geometry") {
            put("type", "LineString")
            putJsonArray("coordinates") {
                geometry.forEach { p ->
                    add(JsonArray(listOf(JsonPrimitive(p.lon), JsonPrimitive(p.lat))))
                }
            }
        }
        putJsonObject("properties") {}
    }
    return featureCollection(listOf(feature))
}
