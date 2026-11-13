package com.cityguide.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Відповідь сервера з кодом помилки (текст для користувача формує ViewModel). */
class HttpException(val code: Int, val host: String) : IOException("HTTP $code from $host")

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

/** Результат одного запиту: визначні місця й заклади харчування. */
data class AroundResult(
    val sights: List<RawPlace>,
    val foods: List<RawPlace>,
    /** Заклади не вдалося завантажити (Overpass недоступний), але місця з Вікіпедії є. */
    val foodsUnavailable: Boolean = false
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
 *
 * Прискорення: один Overpass-запит на місця й заклади, перегони дзеркал Overpass,
 * паралельні запити до Wikidata, кеш на диску (відповіді Overpass на добу, дані Wikidata на 2 тижні).
 */
object Api {

    /** Спільний клієнт із User-Agent (його вимагають Nominatim і Wikimedia); його ж використовує Coil. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "CityGuide/0.3 (open-source prototype)")
                    .build()
            )
        }
        .build()

    // Публічні Overpass-сервери часто перевантажені (502/504), тож опитуємо кілька дзеркал
    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://z.overpass-api.de/api/interpreter"
    )
    private const val MIRROR_STAGGER_MS = 2_000L
    private const val OVERPASS_TOTAL_TIMEOUT_MS = 30_000L
    private const val OVERPASS_TTL_MS = 24L * 60 * 60 * 1000
    private const val WIKI_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val WIKI_CACHE_MAX = 8_000
    private const val FOOD_RADIUS_MAX_M = 3_000
    private val FOOD_AMENITIES = setOf("restaurant", "cafe", "fast_food", "pub", "bar", "ice_cream")

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "api").apply { mkdirs() }
    }

    // --- мережа ---

    private suspend fun executeText(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        if (!resp.isSuccessful) throw HttpException(resp.code, request.url.host)
                        val text = resp.body?.string() ?: throw IOException("Empty response from ${request.url.host}")
                        if (cont.isActive) cont.resume(text)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        })
    }

    private suspend fun execute(request: Request): JsonElement =
        Json.parseToJsonElement(executeText(request))

    /**
     * Запускає завдання по черзі: наступне стартує, якщо попереднє зазнало невдачі
     * або не відповіло за [staggerMs]. Повертає першу успішну відповідь, решту скасовує.
     */
    private suspend fun <T> firstSuccess(staggerMs: Long, tasks: List<suspend () -> T>): T =
        coroutineScope {
            val results = Channel<Result<T>>(tasks.size)
            val starts = List(tasks.size) { CompletableDeferred<Unit>() }
            starts[0].complete(Unit)
            val jobs = tasks.mapIndexed { i, task ->
                launch {
                    if (i > 0) {
                        launch {
                            delay(i * staggerMs)
                            starts[i].complete(Unit)
                        }
                    }
                    starts[i].await()
                    val result = runCatching { task() }
                    if (result.isFailure && i + 1 < starts.size) starts[i + 1].complete(Unit)
                    results.send(result)
                }
            }
            var last: Throwable? = null
            repeat(tasks.size) {
                val result = results.receive()
                if (result.isSuccess) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope result.getOrThrow()
                }
                last = result.exceptionOrNull()
            }
            throw last ?: IOException("No response")
        }

    private fun overpassCacheFile(key: String): File? =
        cacheDir?.let { File(it, "ov_${key.hashCode().toUInt().toString(16)}.json") }

    /** Overpass із дисковим кешем (на добу) та перегонами дзеркал. */
    private suspend fun overpass(query: String, cacheKey: String): JsonObject {
        val file = overpassCacheFile(cacheKey)
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < OVERPASS_TTL_MS) {
            try {
                return Json.parseToJsonElement(file.readText()).jsonObject
            } catch (e: Exception) {
                file.delete()
            }
        }
        val text = try {
            withTimeout(OVERPASS_TOTAL_TIMEOUT_MS) {
                firstSuccess(
                    MIRROR_STAGGER_MS,
                    OVERPASS_ENDPOINTS.map { endpoint ->
                        suspend {
                            val body = FormBody.Builder().add("data", query).build()
                            executeText(Request.Builder().url(endpoint).post(body).build())
                        }
                    }
                )
            }
        } catch (e: TimeoutCancellationException) {
            val stale = file?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            stale ?: throw IOException("Overpass timeout")
        } catch (e: IOException) {
            // Сервери недоступні: краще застарілі дані з кешу, ніж помилка
            val stale = file?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            stale ?: throw e
        }
        val root = Json.parseToJsonElement(text).jsonObject
        // Відповідь із "remark" означає таймаут або обрізані дані: не кешуємо
        if (root["remark"] == null) runCatching { file?.writeText(text) }
        return root
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

    private val suggestCache = ConcurrentHashMap<String, List<Suggestion>>()

    /** Підказки назв міст під час введення (Photon, розрахований на автодоповнення). */
    suspend fun suggest(query: String): List<Suggestion> {
        val key = query.lowercase(Locale.ROOT)
        suggestCache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) {
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
        if (suggestCache.size > 100) suggestCache.clear()
        suggestCache[key] = result
        return result
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

    private fun parseElements(root: JsonObject): List<RawPlace> =
        root["elements"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val tags = o["tags"] as? JsonObject ?: return@mapNotNull null
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
                wikidataId = tagMap["wikidata"],
                name = tagMap["name"],
                lat = lat,
                lon = lon,
                tags = tagMap
            )
        }.distinctBy { it.wikidataId ?: it.id }

    /**
     * Один запит на все: визначні місця (з тегом wikidata; у розширеному режимі ще й іменовані
     * атракції, замки, пам'ятники) та заклади харчування (до 3 км від центру).
     */
    private suspend fun overpassAround(city: City, radiusM: Int, extended: Boolean): AroundResult =
        withContext(Dispatchers.IO) {
            val around = "(around:$radiusM,${city.lat},${city.lon})"
            val foodAround = "(around:${min(radiusM, FOOD_RADIUS_MAX_M)},${city.lat},${city.lon})"
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
            val query = "[out:json][timeout:25];(" + clauses.joinToString("") + ")->.s;" +
                "nwr[\"amenity\"~\"^(restaurant|cafe|fast_food|pub|bar|ice_cream)\$\"][\"name\"]$foodAround->.f;" +
                ".s out center tags $limit;.f out center tags 400;"

            val key = String.format(Locale.US, "v2_%.3f_%.3f_%d_%b", city.lat, city.lon, radiusM, extended)
            val (foods, sights) = parseElements(overpass(query, key))
                .partition { it.tags["amenity"] in FOOD_AMENITIES }
            AroundResult(sights = sights, foods = foods)
        }

    /**
     * Геопошук статей Вікіпедії навколо точки: швидке й стабільне друге джерело визначних місць.
     * Одразу дає Wikidata-id, тож додатковий запит за значущістю не потрібен для пошуку.
     */
    private suspend fun wikiGeosearch(city: City, radiusM: Int, wikiLang: String): List<RawPlace> =
        withContext(Dispatchers.IO) {
            val url = "https://$wikiLang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .addQueryParameter("generator", "geosearch")
                .addQueryParameter("ggscoord", "${city.lat}|${city.lon}")
                .addQueryParameter("ggsradius", min(radiusM, 10_000).toString())
                .addQueryParameter("ggslimit", "150")
                .addQueryParameter("prop", "coordinates|pageprops")
                .addQueryParameter("ppprop", "wikibase_item")
                .addQueryParameter("colimit", "150")
                .build()
            val pages = execute(Request.Builder().url(url).build())
                .jsonObject["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty()
            pages.mapNotNull { page ->
                val o = page.jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val qid = (o["pageprops"] as? JsonObject)?.get("wikibase_item")
                    ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val coord = o["coordinates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@mapNotNull null
                val lat = coord["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = coord["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                RawPlace(
                    id = "wd/$qid",
                    wikidataId = qid,
                    name = title,
                    lat = lat,
                    lon = lon,
                    // Позначка джерела: такі місця беремо лише якщо вони достатньо відомі
                    tags = mapOf(WIKI_ONLY_TAG to "1")
                )
            }
        }

    /**
     * Місця й заклади з двох незалежних джерел: Overpass (категорії, заклади) і геопошук Вікіпедії.
     * Достатньо, щоб відповіло хоч одне з них.
     */
    suspend fun fetchAround(
        city: City,
        radiusM: Int,
        extended: Boolean,
        lang: String
    ): AroundResult = coroutineScope {
        val overpass = async { runCatching { overpassAround(city, radiusM, extended) } }
        val wikiLangs = listOf(lang, "en").distinct()
        val wikis = wikiLangs.map { l -> async { runCatching { wikiGeosearch(city, radiusM, l) } } }

        val ov = overpass.await()
        val wikiResults = wikis.map { it.await() }
        val wikiPlaces = wikiResults.flatMap { it.getOrNull().orEmpty() }

        if (ov.isFailure && wikiResults.all { it.isFailure }) {
            throw ov.exceptionOrNull() ?: IOException("No data")
        }
        val sights = ov.getOrNull()?.sights.orEmpty()
        val known = sights.mapNotNull { it.wikidataId }.toSet()
        val merged = sights + wikiPlaces.filter { it.wikidataId !in known }.distinctBy { it.wikidataId }
        AroundResult(
            sights = merged,
            foods = ov.getOrNull()?.foods.orEmpty(),
            foodsUnavailable = ov.isFailure
        )
    }

    const val WIKI_ONLY_TAG = "_wiki_only"

    // --- Wikidata / Wikipedia ---

    private data class CachedWiki(val info: WikiInfo, val ts: Long)

    private val wikiCache = ConcurrentHashMap<String, CachedWiki>()

    @Volatile
    private var wikiCacheLoaded = false

    private fun wikiCacheFile(): File? = cacheDir?.let { File(it, "wikidata-cache-v1.json") }

    @Synchronized
    private fun loadWikiCache() {
        if (wikiCacheLoaded) return
        wikiCacheLoaded = true
        val file = wikiCacheFile() ?: return
        try {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            for ((key, value) in obj) {
                val o = value as? JsonObject ?: continue
                wikiCache[key] = CachedWiki(
                    WikiInfo(
                        label = o["l"]?.jsonPrimitive?.contentOrNull,
                        description = o["d"]?.jsonPrimitive?.contentOrNull,
                        sitelinks = o["s"]?.jsonPrimitive?.intOrNull ?: 0,
                        wikiTitle = o["t"]?.jsonPrimitive?.contentOrNull,
                        wikiLang = o["w"]?.jsonPrimitive?.contentOrNull
                    ),
                    o["ts"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            }
        } catch (e: Exception) {
            // немає або пошкоджений кеш: починаємо з порожнього
        }
    }

    @Synchronized
    private fun saveWikiCache() {
        val file = wikiCacheFile() ?: return
        try {
            val newest = wikiCache.entries.sortedByDescending { it.value.ts }.take(WIKI_CACHE_MAX)
            val obj = buildJsonObject {
                for ((key, cached) in newest) {
                    putJsonObject(key) {
                        cached.info.label?.let { put("l", it) }
                        cached.info.description?.let { put("d", it) }
                        put("s", cached.info.sitelinks)
                        cached.info.wikiTitle?.let { put("t", it) }
                        cached.info.wikiLang?.let { put("w", it) }
                        put("ts", cached.ts)
                    }
                }
            }
            file.writeText(obj.toString())
        } catch (e: Exception) {
            // кеш необов'язковий
        }
    }

    /** Кешовані записи беремо з диска, відсутні запитуємо паралельними пачками по 50. */
    suspend fun fetchWikidata(ids: List<String>, lang: String): Map<String, WikiInfo> =
        withContext(Dispatchers.IO) {
            loadWikiCache()
            val now = System.currentTimeMillis()
            val result = mutableMapOf<String, WikiInfo>()
            val missing = mutableListOf<String>()
            for (id in ids.distinct()) {
                val cached = wikiCache["$lang:$id"]
                if (cached != null && now - cached.ts < WIKI_TTL_MS) {
                    result[id] = cached.info
                } else {
                    missing += id
                }
            }
            if (missing.isNotEmpty()) {
                val fetched = coroutineScope {
                    missing.chunked(50).map { chunk ->
                        async { runCatching { fetchWikidataChunk(chunk, lang) }.getOrDefault(emptyMap()) }
                    }.awaitAll()
                }
                for (map in fetched) {
                    for ((id, info) in map) {
                        result[id] = info
                        wikiCache["$lang:$id"] = CachedWiki(info, now)
                    }
                }
                saveWikiCache()
            }
            result
        }

    private suspend fun fetchWikidataChunk(ids: List<String>, lang: String): Map<String, WikiInfo> {
        val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", ids.joinToString("|"))
            .addQueryParameter("props", "sitelinks|labels|descriptions")
            .addQueryParameter("languages", "$lang|en")
            .addQueryParameter("format", "json")
            .build()
        val entities = execute(Request.Builder().url(url).build())
            .jsonObject["entities"] as? JsonObject ?: return emptyMap()
        val result = mutableMapOf<String, WikiInfo>()
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
        return result
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
