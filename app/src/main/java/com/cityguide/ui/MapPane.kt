package com.cityguide.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.cityguide.data.City
import com.cityguide.data.Place
import com.cityguide.data.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SRC = "route-src"
private const val PLACES_SRC = "places-src"
private const val STOPS_SRC = "stops-src"
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

@Composable
fun MapPane(city: City?, places: List<Place>, route: Route?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

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
            m.setStyle(STYLE_URL) { style ->
                style.addSource(GeoJsonSource(ROUTE_SRC, EMPTY_FC))
                style.addSource(GeoJsonSource(PLACES_SRC, EMPTY_FC))
                style.addSource(GeoJsonSource(STOPS_SRC, EMPTY_FC))

                style.addLayer(
                    LineLayer("route-layer", ROUTE_SRC).withProperties(
                        PropertyFactory.lineColor(Color.parseColor("#1E88E5")),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
                style.addLayer(
                    CircleLayer("places-layer", PLACES_SRC).withProperties(
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleColor(Color.parseColor("#E53935")),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(1.5f)
                    )
                )
                style.addLayer(
                    CircleLayer("stops-layer", STOPS_SRC).withProperties(
                        PropertyFactory.circleRadius(12f),
                        PropertyFactory.circleColor(Color.parseColor("#1E88E5")),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2f)
                    )
                )
                style.addLayer(
                    SymbolLayer("numbers-layer", STOPS_SRC).withProperties(
                        PropertyFactory.textField(Expression.get("n")),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(Color.WHITE),
                        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true)
                    )
                )
                map = m
                styleReady = true
            }
        }
    }

    // Оновлення даних на карті
    LaunchedEffect(styleReady, places, route) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(PLACES_SRC)?.setGeoJson(placesJson(places))
        style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeJson(route))
        style.getSourceAs<GeoJsonSource>(STOPS_SRC)?.setGeoJson(stopsJson(route))
    }

    // Камера: на місто, а після побудови маршруту на весь маршрут
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
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
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
    featureCollection(places.map { pointFeature(it.lat, it.lon) })

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
                    add(
                        JsonArray(
                            listOf(
                                kotlinx.serialization.json.JsonPrimitive(p.lon),
                                kotlinx.serialization.json.JsonPrimitive(p.lat)
                            )
                        )
                    )
                }
            }
        }
        putJsonObject("properties") {}
    }
    return featureCollection(listOf(feature))
}
