package com.cityguide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Місце з OpenStreetMap до збагачення даними Wikidata. */
data class RawPlace(
    val id: String,
    val wikidataId: String?,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>
)

data class WikiInfo(
    val label: String?,
    val description: String?,
    val sitelinks: Int,
    val wikiTitle: String?,
    val wikiLang: String?
)

data class RouteResult(
    val geometry: List<LatLon>,
    val distance: Double,
    val duration: Double,
    val steps: List<Step>
)

data class Suggestion(val name: String, val subtitle: String, val lat: Double, val lon: Double)

/**
 * Усі джерела безкоштовні й без ключів:
 * Nominatim/Photon (геокодування), Overpass (місця й заклади OSM), Wikidata і Wikipedia
 * (значущість, описи, фото), routing.openstreetmap.de (пішохідні маршрути).
 * Для продакшну потрібен власний кеш-проксі й повага до лімітів цих сервісів.
 */
object Api {

    /** Спільний клієнт із User-Agent (його вимагають Nominatim і Wikimedia); його ж використовує Coil. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "CityGuide/0.2 (open-source prototype)")
                    .build()
            )
        }
        .build()

    // Публічні Overpass-сервери часто перевантажені (502/504), тож пробуємо кілька дзеркал
    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    private fun execute(request: Request): JsonElement =
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Помилка ${resp.code} від ${request.url.host}")
            val text = resp.body?.string() ?: error("Порожня відповідь від ${request.url.host}")
            Json.parseToJsonElement(text)
        }

    private fun overpass(query: String): JsonObject {
        for (endpoint in OVERPASS_ENDPOINTS) {
            try {
                val body = FormBody.Builder().add("data", query).build()
                return execute(Request.Builder().url(endpoint).post(body).build()).jsonObject
            } catch (e: Exception) {
                // пробуємо наступне дзеркало
            }
        }
        error("Сервери карт перевантажені. Спробуйте ще раз за хвилину")
    }

    // --- пошук міста ---

    suspend fun geocode(query: String, lang: String): City? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .addQueryParameter("accept-language", lang)
            .build()
        val first = execute(Request.Builder().url(url).build()).jsonArray.firstOrNull()?.jsonObject
            ?: return@withContext null
        City(
            name = first["display_name"]?.jsonPrimitive?.content?.substringBefore(",") ?: query,
            lat = first["lat"]!!.jsonPrimitive.double,
            lon = first["lon"]!!.jsonPrimitive.double
        )
    }

    /** Підказки назв міст під час введення (Photon, розрахований на автодоповнення). */
    suspend fun suggest(query: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "6")
            .addQueryParameter("osm_tag", "place:city")
            .addQueryParameter("osm_tag", "place:town")
            .addQueryParameter("osm_tag", "place:village")
            .build()
        val features = execute(Request.Builder().url(url).build())
            .jsonObject["features"]?.jsonArray.orEmpty()
        features.mapNotNull { f ->
            val o = f.jsonObject
            val props = o["properties"] as? JsonObject ?: return@mapNotNull null
            val name = props["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
                ?: return@mapNotNull null
            val subtitle = listOf("state", "country")
                .mapNotNull { props[it]?.jsonPrimitive?.contentOrNull }
                .joinToString(", ")
            Suggestion(
                name = name,
                subtitle = subtitle,
                lat = coords[1].jsonPrimitive.double,
                lon = coords[0].jsonPrimitive.double
            )
        }.distinctBy { it.name + it.subtitle }
    }

    /** Назва населеного пункту за координатами (best-effort). */
    suspend fun reverse(lat: Double, lon: Double, lang: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl().newBuilder()
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lon.toString())
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("zoom", "10")
                .addQueryParameter("accept-language", lang)
                .build()
            val o = execute(Request.Builder().url(url).build()).jsonObject
            val address = o["address"] as? JsonObject
            listOf("city", "town", "village", "municipality")
                .firstNotNullOfOrNull { address?.get(it)?.jsonPrimitive?.contentOrNull }
                ?: o["name"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    // --- місця й заклади (Overpass) ---

    private fun parseElements(root: JsonObject, requireWikidata: Boolean): List<RawPlace> =
        root["elements"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val tags = o["tags"] as? JsonObject ?: return@mapNotNull null
            val wikidata = tags["wikidata"]?.jsonPrimitive?.contentOrNull
            if (requireWikidata && wikidata == null) return@mapNotNull null
            val center = o["center"] as? JsonObject
            val lat = (o["lat"] ?: center?.get("lat"))?.jsonPrimitive?.doubleOrNull
                ?: return@mapNotNull null
            val lon = (o["lon"] ?: center?.get("lon"))?.jsonPrimitive?.doubleOrNull
                ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val osmId = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val tagMap = tags.mapValues { it.value.jsonPrimitive.content }
            RawPlace(
                id = "$type/$osmId",
                wikidataId = wikidata,
                name = tagMap["name"],
                lat = lat,
                lon = lon,
                tags = tagMap
            )
        }.distinctBy { it.wikidataId ?: it.id }

    /**
     * Звичайний режим: лише об'єкти з тегом wikidata (відсікає лавки й таблички).
     * Розширений: додатково іменовані атракції, замки, пам'ятники тощо без запису у Wikidata.
     */
    suspend fun fetchPlaces(city: City, radiusM: Int, extended: Boolean): List<RawPlace> =
        withContext(Dispatchers.IO) {
            val around = "(around:$radiusM,${city.lat},${city.lon})"
            val clauses = mutableListOf(
                "nwr[\"wikidata\"][\"tourism\"~\"^(attraction|museum|gallery|viewpoint|artwork)\$\"]$around;",
                "nwr[\"wikidata\"][\"historic\"]$around;",
                "nwr[\"wikidata\"][\"amenity\"=\"place_of_worship\"]$around;",
                "nwr[\"wikidata\"][\"leisure\"~\"^(park|garden|nature_reserve)\$\"]$around;"
            )
            if (extended) {
                clauses += "nwr[\"name\"][\"tourism\"~\"^(attraction|museum|gallery|viewpoint|zoo|theme_park)\$\"]$around;"
                clauses += "nwr[\"name\"][\"historic\"~\"^(castle|monument|memorial|ruins|fort|archaeological_site|city_gate|manor)\$\"]$around;"
            }
            val limit = if (extended) 500 else 300
            val query = "[out:json][timeout:30];(" + clauses.joinToString("") + ");out center tags $limit;"
            parseElements(overpass(query), requireWikidata = false)
        }

    suspend fun fetchFood(city: City, radiusM: Int): List<RawPlace> = withContext(Dispatchers.IO) {
        val around = "(around:$radiusM,${city.lat},${city.lon})"
        val query = "[out:json][timeout:30];(" +
            "nwr[\"amenity\"~\"^(restaurant|cafe|fast_food|pub|bar|ice_cream)\$\"][\"name\"]$around;" +
            ");out center tags 400;"
        parseElements(overpass(query), requireWikidata = false)
    }

    // --- Wikidata / Wikipedia ---

    suspend fun fetchWikidata(ids: List<String>, lang: String): Map<String, WikiInfo> =
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, WikiInfo>()
            for (chunk in ids.chunked(50)) {
                val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
                    .addQueryParameter("action", "wbgetentities")
                    .addQueryParameter("ids", chunk.joinToString("|"))
                    .addQueryParameter("props", "sitelinks|labels|descriptions")
                    .addQueryParameter("languages", "$lang|en")
                    .addQueryParameter("format", "json")
                    .build()
                val entities = execute(Request.Builder().url(url).build())
                    .jsonObject["entities"] as? JsonObject ?: continue
                for ((id, entity) in entities) {
                    val o = entity as? JsonObject ?: continue
                    val sitelinks = o["sitelinks"] as? JsonObject
                    val local = sitelinks?.get("${lang}wiki") as? JsonObject
                    val english = sitelinks?.get("enwiki") as? JsonObject
                    val page = local ?: english
                    result[id] = WikiInfo(
                        label = o.localized("labels", lang),
                        description = o.localized("descriptions", lang),
                        sitelinks = sitelinks?.size ?: 0,
                        wikiTitle = page?.get("title")?.jsonPrimitive?.contentOrNull,
                        wikiLang = if (local != null) lang else "en"
                    )
                }
            }
            result
        }

    private fun JsonObject.localized(field: String, lang: String): String? {
        val map = this[field] as? JsonObject ?: return null
        val entry = (map[lang] ?: map["en"]) as? JsonObject ?: return null
        return entry["value"]?.jsonPrimitive?.contentOrNull
    }

    /** Короткий опис і фото зі статті Вікіпедії (best-effort). */
    suspend fun placeDetail(wikiLang: String, title: String): PlaceDetail? = withContext(Dispatchers.IO) {
        try {
            val url = "https://$wikiLang.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl()
                .newBuilder()
                .addPathSegment(title.replace(' ', '_'))
                .build()
            val o = execute(Request.Builder().url(url).build()).jsonObject
            PlaceDetail(
                extract = o["extract"]?.jsonPrimitive?.contentOrNull,
                imageUrl = (o["thumbnail"] as? JsonObject)?.get("source")?.jsonPrimitive?.contentOrNull,
                pageUrl = ((o["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)
                    ?.get("page")?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- маршрут ---

    suspend fun route(points: List<LatLon>): RouteResult? = withContext(Dispatchers.IO) {
        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords".toHttpUrl()
            .newBuilder()
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "true")
            .build()
        val route = execute(Request.Builder().url(url).build())
            .jsonObject["routes"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@withContext null
        val line = route["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map {
            val p = it.jsonArray
            LatLon(lat = p[1].jsonPrimitive.double, lon = p[0].jsonPrimitive.double)
        }
        val steps = route["legs"]?.jsonArray.orEmpty().flatMap { leg ->
            leg.jsonObject["steps"]?.jsonArray.orEmpty().mapNotNull { st ->
                val so = st.jsonObject
                val maneuver = so["maneuver"] as? JsonObject ?: return@mapNotNull null
                val loc = maneuver["location"]?.jsonArray ?: return@mapNotNull null
                Step(
                    lat = loc[1].jsonPrimitive.double,
                    lon = loc[0].jsonPrimitive.double,
                    type = maneuver["type"]?.jsonPrimitive?.contentOrNull ?: "turn",
                    modifier = maneuver["modifier"]?.jsonPrimitive?.contentOrNull,
                    street = so["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    distance = so["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    exit = maneuver["exit"]?.jsonPrimitive?.intOrNull
                )
            }
        }
        RouteResult(
            geometry = line,
            distance = route["distance"]!!.jsonPrimitive.double,
            duration = route["duration"]!!.jsonPrimitive.double,
            steps = steps
        )
    }
}
