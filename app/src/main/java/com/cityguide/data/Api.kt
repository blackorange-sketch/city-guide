package com.cityguide.data

import android.content.Context
import android.util.Base64
import com.cityguide.BuildConfig
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.booleanOrNull
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    val wikiLang: String?,
    /** Англійський опис (для відсіювання адміністративних одиниць, регіонів тощо). */
    val descriptionEn: String? = null
)

/** Результат одного запиту: визначні місця й заклади харчування. */
data class AroundResult(
    val sights: List<RawPlace>,
    val foods: List<RawPlace>,
    /** Заклади не вдалося завантажити (Overpass недоступний), але місця з Вікіпедії є. */
    val foodsUnavailable: Boolean = false
)

data class RatedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val rating: Double,
    val price: Int?,
    val website: String?
)

data class RouteResult(
    val geometry: List<LatLon>,
    val distance: Double,
    val duration: Double,
    val steps: List<Step>
)

/** Результат розпізнавання фото (відповідь сервера-проксі). */
data class Identified(
    val found: Boolean,
    val name: String?,
    val kind: String?,
    val wikiEn: String?,
    val place: String?,
    val confidence: String?,
    val description: String?
)

/** Стаття Вікіпедії, знайдена для розпізнаного об'єкта. */
data class WikiHit(
    val title: String,
    val lang: String,
    val wikidataId: String?,
    val description: String?,
    val lat: Double?,
    val lon: Double?
)

/** Пошук за фото не налаштований: немає проксі або на ньому немає ключа ШІ. */
class IdentifyUnavailableException : IOException("Photo search is not configured")

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
    /** Кеш-проксі (Cloudflare Worker) з BuildConfig; порожній рядок = працюємо напряму. */
    private val PROXY: String = BuildConfig.PROXY_URL

    private val PROXY_KEY: String = BuildConfig.PROXY_KEY

    /** Пошук за фото працює лише через проксі, де зберігається ключ ШІ. */
    val canIdentify: Boolean get() = PROXY.isNotEmpty()

    /** Рейтинги закладів доступні лише через проксі (там зберігається ключ Foursquare). */
    val hasRatings: Boolean get() = PROXY.isNotEmpty()

    private val overpassEndpoints: List<String>
        get() = (if (PROXY.isNotEmpty()) listOf("$PROXY/overpass") else emptyList()) + OVERPASS_ENDPOINTS

    private const val MIRROR_STAGGER_MS = 2_000L
    private const val OVERPASS_TOTAL_TIMEOUT_MS = 30_000L
    private const val OVERPASS_TTL_MS = 24L * 60 * 60 * 1000
    private const val WIKI_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val WIKI_CACHE_MAX = 8_000
    private const val FOOD_RADIUS_MAX_M = 5_000
    private val FOOD_AMENITIES = setOf(
        "restaurant", "cafe", "fast_food", "pub", "bar", "ice_cream", "food_court", "biergarten"
    )

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
                    overpassEndpoints.map { endpoint ->
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
            // Розваги: кіно, театри, зоопарки, боулінг, квести тощо (без вимоги wikidata)
            val entertainment = "(" +
                "nwr[\"name\"][\"tourism\"~\"^(zoo|theme_park|aquarium)\$\"]$around;" +
                "nwr[\"name\"][\"amenity\"~\"^(cinema|theatre|arts_centre|planetarium)\$\"]$around;" +
                "nwr[\"name\"][\"leisure\"~\"^(bowling_alley|escape_game|amusement_arcade|water_park|" +
                "miniature_golf|ice_rink|trampoline_park|adventure_park)\$\"]$around;" +
                ")->.e;"
            val query = "[out:json][timeout:25];(" + clauses.joinToString("") + ")->.s;" + entertainment +
                "nwr[\"amenity\"~\"^(restaurant|cafe|fast_food|pub|bar|ice_cream|food_court|biergarten)\$\"]" +
                "[~\"^(name|brand)\$\"~\".\"]$foodAround->.f;" +
                ".s out center tags $limit;.f out center tags 1500;.e out center tags 250;"

            val key = String.format(Locale.US, "v4_%.3f_%.3f_%d_%b", city.lat, city.lon, radiusM, extended)
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

    // --- докладна інформація: стаття, фото, факти ---

    // Службові розділи статей, які не потрібні в застосунку (різними мовами)
    private val SKIP_SECTIONS = setOf(
        "see also", "references", "external links", "notes", "further reading", "bibliography", "sources",
        "дивись також", "примітки", "посилання", "література", "джерела", "виноски",
        "zobacz też", "przypisy", "linki zewnętrzne", "bibliografia", "uwagi",
        "siehe auch", "einzelnachweise", "weblinks", "literatur", "anmerkungen", "quellen"
    )
    private val HEADING = Regex("^(={2,6})\\s*(.+?)\\s*\\1\\s*$")
    private const val ARTICLE_MAX_CHARS = 40_000

    /** Повний текст статті Вікіпедії, розбитий за розділами (без довідкових розділів). */
    suspend fun fullArticle(wikiLang: String, title: String): List<ArticleSection> =
        withContext(Dispatchers.IO) {
            val url = "https://$wikiLang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .addQueryParameter("prop", "extracts")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("exsectionformat", "wiki")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("titles", title)
                .build()
            val page = execute(Request.Builder().url(url).build())
                .jsonObject["query"]?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject
            val text = page?.get("extract")?.jsonPrimitive?.contentOrNull ?: return@withContext emptyList()
            parseSections(text.take(ARTICLE_MAX_CHARS))
        }

    private fun parseSections(text: String): List<ArticleSection> {
        val result = mutableListOf<ArticleSection>()
        var title: String? = null
        var skipping = false
        val buffer = StringBuilder()
        fun flush() {
            val body = buffer.toString().trim()
            if (body.isNotEmpty() && !skipping) result += ArticleSection(title, body)
            buffer.clear()
        }
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line.trim())
            if (m != null) {
                flush()
                val heading = m.groupValues[2]
                title = heading
                skipping = heading.lowercase() in SKIP_SECTIONS
            } else {
                buffer.appendLine(line)
            }
        }
        flush()
        return result.map { it.copy(text = it.text.replace(Regex("\n{3,}"), "\n\n")) }
    }

    private val GALLERY_EXCLUDE = Regex(
        "icon|logo|flag|symbol|map|commons|wikidata|button|ambox|blank|coa_|coat|locator|sign|diagram|plan|stub|edit|question|disambig",
        RegexOption.IGNORE_CASE
    )

    /** Мініатюри фото зі статті Вікіпедії (Wikimedia Commons), без значків і схем. */
    suspend fun gallery(wikiLang: String, title: String): List<String> = withContext(Dispatchers.IO) {
        val url = "https://$wikiLang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "images")
            .addQueryParameter("gimlimit", "40")
            .addQueryParameter("titles", title)
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|mime")
            .addQueryParameter("iiurlwidth", "720")
            .build()
        val pages = execute(Request.Builder().url(url).build())
            .jsonObject["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty()
        pages.mapNotNull { p ->
            val o = p.jsonObject
            val name = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (GALLERY_EXCLUDE.containsMatchIn(name)) return@mapNotNull null
            val info = o["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val mime = info["mime"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (mime != "image/jpeg" && mime != "image/png" && mime != "image/webp") return@mapNotNull null
            info["thumburl"]?.jsonPrimitive?.contentOrNull ?: info["url"]?.jsonPrimitive?.contentOrNull
        }.take(12)
    }

    private fun JsonObject.claimValues(pid: String): List<JsonObject> =
        (this["claims"] as? JsonObject)?.get(pid)?.jsonArray.orEmpty().mapNotNull {
            (((it.jsonObject["mainsnak"] as? JsonObject)?.get("datavalue") as? JsonObject)
                ?.get("value")) as? JsonObject
        }

    /** Факти з Wikidata: рік побудови, архітектор, стиль, статус пам'ятки, висота. */
    suspend fun wikidataFacts(qid: String, lang: String): List<InfoFact> = withContext(Dispatchers.IO) {
        val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", qid)
            .addQueryParameter("props", "claims")
            .addQueryParameter("format", "json")
            .build()
        val entity = (execute(Request.Builder().url(url).build())
            .jsonObject["entities"] as? JsonObject)?.get(qid) as? JsonObject
            ?: return@withContext emptyList()

        val facts = mutableListOf<InfoFact>()
        entity.claimValues("P571").firstOrNull()?.get("time")?.jsonPrimitive?.contentOrNull?.let { time ->
            Regex("^\\+?(\\d{1,4})-").find(time)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it > 0) facts += InfoFact(FactKind.INCEPTION, it.toString())
            }
        }
        entity.claimValues("P2048").firstOrNull()?.get("amount")?.jsonPrimitive?.contentOrNull?.let {
            it.toDoubleOrNull()?.let { h -> facts += InfoFact(FactKind.HEIGHT, "%.0f m".format(h)) }
        }

        val linked = listOf(
            FactKind.ARCHITECT to "P84",
            FactKind.STYLE to "P149",
            FactKind.HERITAGE to "P1435"
        ).associate { (kind, pid) ->
            kind to entity.claimValues(pid)
                .mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
                .distinct().take(3)
        }
        val ids = linked.values.flatten().distinct()
        if (ids.isNotEmpty()) {
            val labels = runCatching {
                val labelUrl = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
                    .addQueryParameter("action", "wbgetentities")
                    .addQueryParameter("ids", ids.joinToString("|"))
                    .addQueryParameter("props", "labels")
                    .addQueryParameter("languages", "$lang|en")
                    .addQueryParameter("format", "json")
                    .build()
                val entities = execute(Request.Builder().url(labelUrl).build())
                    .jsonObject["entities"] as? JsonObject
                ids.associateWith { id ->
                    (entities?.get(id) as? JsonObject)?.localized("labels", lang)
                }
            }.getOrDefault(emptyMap())
            for ((kind, list) in linked) {
                val names = list.mapNotNull { labels[it] }
                if (names.isNotEmpty()) facts += InfoFact(kind, names.joinToString(", "))
            }
        }
        facts
    }

    // --- рейтинги закладів (Foursquare через проксі) ---

    private suspend fun fsqSearch(city: City, radiusM: Int, query: String): List<RatedPlace> {
        val url = "$PROXY/fsq".toHttpUrl().newBuilder()
            .addQueryParameter("ll", "${city.lat},${city.lon}")
            .addQueryParameter("radius", min(radiusM, FOOD_RADIUS_MAX_M).toString())
            .addQueryParameter("query", query)
            .addQueryParameter("sort", "RATING")
            .addQueryParameter("limit", "50")
            .addQueryParameter("fields", "fsq_place_id,name,latitude,longitude,rating,price,website")
            .build()
        val results = execute(Request.Builder().url(url).build())
            .jsonObject["results"]?.jsonArray.orEmpty()
        return results.mapNotNull { r ->
            val o = r.jsonObject
            RatedPlace(
                id = o["fsq_place_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                rating = o["rating"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                price = o["price"]?.jsonPrimitive?.intOrNull,
                website = o["website"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    /** Найвище оцінені ресторани й кафе (порожньо, якщо проксі чи ключ не налаштовано). */
    suspend fun fetchRated(city: City, radiusM: Int): List<RatedPlace> {
        if (PROXY.isEmpty()) return emptyList()
        return coroutineScope {
            listOf("restaurant", "cafe")
                .map { q -> async { runCatching { fsqSearch(city, radiusM, q) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
                .distinctBy { it.id }
        }
    }

    // --- Wikidata / Wikipedia ---

    private data class CachedWiki(val info: WikiInfo, val ts: Long)

    private val wikiCache = ConcurrentHashMap<String, CachedWiki>()

    @Volatile
    private var wikiCacheLoaded = false

    private fun wikiCacheFile(): File? = cacheDir?.let { File(it, "wikidata-cache-v2.json") }

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
                        wikiLang = o["w"]?.jsonPrimitive?.contentOrNull,
                        descriptionEn = o["e"]?.jsonPrimitive?.contentOrNull
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
                        cached.info.descriptionEn?.let { put("e", it) }
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
        if (PROXY.isNotEmpty()) {
            try {
                return fetchWikidataChunkFrom("$PROXY/wikidata", ids, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // проксі недоступний: йдемо напряму
            }
        }
        return fetchWikidataChunkFrom("https://www.wikidata.org/w/api.php", ids, lang)
    }

    private suspend fun fetchWikidataChunkFrom(
        base: String,
        ids: List<String>,
        lang: String
    ): Map<String, WikiInfo> {
        val url = base.toHttpUrl().newBuilder()
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
                wikiLang = if (local != null) lang else "en",
                descriptionEn = o.localized("descriptions", "en")
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
    /** Розпізнає пам'ятник, будівлю чи картину на фото через проксі (ШІ з розпізнаванням зображень). */
    suspend fun identify(jpeg: ByteArray, lang: String, hint: String): Identified =
        withContext(Dispatchers.IO) {
            if (PROXY.isEmpty()) throw IdentifyUnavailableException()
            val payload = buildJsonObject {
                put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                put("lang", lang)
                put("hint", hint)
            }.toString()
            val builder = Request.Builder()
                .url("$PROXY/identify")
                .post(payload.toRequestBody("application/json".toMediaType()))
            if (PROXY_KEY.isNotEmpty()) builder.header("x-app-key", PROXY_KEY)
            val text = try {
                executeText(builder.build())
            } catch (e: HttpException) {
                // 404: на проксі немає ключа ШІ; 403: невірний ключ застосунку
                if (e.code == 404 || e.code == 403) throw IdentifyUnavailableException()
                throw e
            }
            val o = Json.parseToJsonElement(text).jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            Identified(
                found = o["found"]?.jsonPrimitive?.booleanOrNull ?: (str("name") != null),
                name = str("name"),
                kind = str("kind"),
                wikiEn = str("wikipedia_en"),
                place = str("place"),
                confidence = str("confidence"),
                description = str("description")
            )
        }

    /** Знаходить статтю Вікіпедії (бажано мовою інтерфейсу) за назвою англійською статті або текстовим пошуком. */
    suspend fun resolveWiki(enTitle: String?, query: String, lang: String): WikiHit? =
        withContext(Dispatchers.IO) {
            try {
                val title = enTitle ?: searchWikiTitle(query) ?: return@withContext null
                var wikiLang = "en"
                var local = title
                if (lang != "en") {
                    val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                        .addQueryParameter("action", "query")
                        .addQueryParameter("format", "json")
                        .addQueryParameter("formatversion", "2")
                        .addQueryParameter("redirects", "1")
                        .addQueryParameter("prop", "langlinks")
                        .addQueryParameter("lllang", lang)
                        .addQueryParameter("titles", title)
                        .build()
                    val page = execute(Request.Builder().url(url).build()).jsonObject["query"]
                        ?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject
                    val link = page?.get("langlinks")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("title")?.jsonPrimitive?.contentOrNull
                    if (link != null) {
                        wikiLang = lang
                        local = link
                    }
                }
                val summaryUrl = "https://$wikiLang.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl()
                    .newBuilder().addPathSegment(local.replace(' ', '_')).build()
                val o = execute(Request.Builder().url(summaryUrl).build()).jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") return@withContext null
                val coords = o["coordinates"] as? JsonObject
                WikiHit(
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: local,
                    lang = wikiLang,
                    wikidataId = o["wikibase_item"]?.jsonPrimitive?.contentOrNull,
                    description = o["description"]?.jsonPrimitive?.contentOrNull,
                    lat = coords?.get("lat")?.jsonPrimitive?.doubleOrNull,
                    lon = coords?.get("lon")?.jsonPrimitive?.doubleOrNull
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun searchWikiTitle(query: String): String? {
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("list", "search")
            .addQueryParameter("srlimit", "1")
            .addQueryParameter("srsearch", query)
            .build()
        return execute(Request.Builder().url(url).build()).jsonObject["query"]
            ?.jsonObject?.get("search")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("title")?.jsonPrimitive?.contentOrNull
    }

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
