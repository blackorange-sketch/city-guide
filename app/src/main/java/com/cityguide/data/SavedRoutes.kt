package com.cityguide.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Збережений маршрут: працює без інтернету (зупинки, лінія, підказки, переходи транспортом). */
data class SavedRoute(
    val id: String,
    val title: String,
    val savedAt: Long,
    val start: LatLon?,
    val route: Route
)

/** Збереження маршрутів у файлі застосунку (JSON). */
object SavedRoutes {
    private const val FILE = "saved_routes.json"

    fun list(context: Context): List<SavedRoute> = try {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) emptyList()
        else Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { runCatching { read(it.jsonObject) }.getOrNull() }
            .sortedByDescending { it.savedAt }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(context: Context, title: String, start: LatLon?, route: Route): SavedRoute {
        val saved = SavedRoute(
            id = System.currentTimeMillis().toString(),
            title = title,
            savedAt = System.currentTimeMillis(),
            start = start,
            route = route
        )
        write(context, (list(context) + saved).takeLast(30))
        return saved
    }

    fun delete(context: Context, id: String) = write(context, list(context).filter { it.id != id })

    private fun write(context: Context, items: List<SavedRoute>) {
        val text = JsonArray(items.map { toJson(it) }).toString()
        File(context.filesDir, FILE).writeText(text)
    }

    // --- JSON ---

    private fun str(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun points(list: List<LatLon>): JsonArray =
        JsonArray(list.flatMap { listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon)) })

    private fun readPoints(array: JsonArray?): List<LatLon> {
        val flat = array.orEmpty().map { it.jsonPrimitive.double }
        return flat.chunked(2).filter { it.size == 2 }.map { LatLon(it[0], it[1]) }
    }

    private fun toJson(s: SavedRoute): JsonObject = buildJsonObject {
        put("id", s.id)
        put("title", s.title)
        put("savedAt", s.savedAt)
        put("start", s.start?.let { points(listOf(it)) } ?: JsonNull)
        put("distance", s.route.distanceMeters)
        put("duration", s.route.durationSeconds)
        put("geometry", points(s.route.geometry))
        put("stops", JsonArray(s.route.stops.map { placeJson(it) }))
        put("steps", JsonArray(s.route.steps.map { st ->
            buildJsonObject {
                put("lat", st.lat); put("lon", st.lon); put("type", st.type)
                put("modifier", str(st.modifier)); put("street", st.street)
                put("distance", st.distance); put("exit", st.exit?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }))
        put("transit", JsonArray(s.route.transit.map { t ->
            buildJsonObject {
                put("toStop", t.toStop); put("durationSec", t.durationSec)
                put("walkingSec", t.walkingSec); put("transfers", t.transfers)
                put("segments", JsonArray(t.segments.map { g ->
                    buildJsonObject {
                        put("mode", g.mode); put("line", g.line); put("minutes", g.minutes)
                        put("geometry", points(g.geometry))
                    }
                }))
            }
        }))
    }

    private fun placeJson(p: Place): JsonObject = buildJsonObject {
        put("id", p.id); put("wikidataId", str(p.wikidataId)); put("name", p.name)
        put("description", str(p.description)); put("lat", p.lat); put("lon", p.lon)
        put("category", p.category.name); put("score", p.score)
        put("wikiTitle", str(p.wikiTitle)); put("wikiLang", str(p.wikiLang))
        put("website", str(p.website)); put("hours", str(p.hours))
        put("rating", p.rating?.let { JsonPrimitive(it) } ?: JsonNull)
        put("price", p.price?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tags", JsonObject(p.tags.mapValues { JsonPrimitive(it.value) }))
    }

    private fun JsonObject.s(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun read(o: JsonObject): SavedRoute {
        val stops = o["stops"]!!.jsonArray.map { e ->
            val p = e.jsonObject
            Place(
                id = p.s("id")!!,
                wikidataId = p.s("wikidataId"),
                name = p.s("name").orEmpty(),
                description = p.s("description"),
                lat = p["lat"]!!.jsonPrimitive.double,
                lon = p["lon"]!!.jsonPrimitive.double,
                category = Category.entries.firstOrNull { it.name == p.s("category") } ?: Category.OTHER,
                score = p["score"]?.jsonPrimitive?.intOrNull ?: 0,
                wikiTitle = p.s("wikiTitle"),
                wikiLang = p.s("wikiLang"),
                website = p.s("website"),
                hours = p.s("hours"),
                rating = (p["rating"] as? JsonPrimitive)?.doubleOrNull,
                price = (p["price"] as? JsonPrimitive)?.intOrNull,
                tags = (p["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            )
        }
        val steps = o["steps"]?.jsonArray.orEmpty().map { e ->
            val s = e.jsonObject
            Step(
                lat = s["lat"]!!.jsonPrimitive.double,
                lon = s["lon"]!!.jsonPrimitive.double,
                type = s.s("type") ?: "turn",
                modifier = s.s("modifier"),
                street = s.s("street").orEmpty(),
                distance = s["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                exit = (s["exit"] as? JsonPrimitive)?.intOrNull
            )
        }
        val transit = o["transit"]?.jsonArray.orEmpty().map { e ->
            val t = e.jsonObject
            TransitLeg(
                toStop = t["toStop"]!!.jsonPrimitive.intOrNull ?: 0,
                durationSec = t["durationSec"]!!.jsonPrimitive.double,
                walkingSec = t["walkingSec"]!!.jsonPrimitive.double,
                transfers = t["transfers"]?.jsonPrimitive?.intOrNull ?: 0,
                segments = t["segments"]?.jsonArray.orEmpty().map { g ->
                    val gs = g.jsonObject
                    TransitSegment(
                        mode = gs.s("mode").orEmpty(),
                        line = gs.s("line").orEmpty(),
                        minutes = gs["minutes"]?.jsonPrimitive?.intOrNull ?: 1,
                        geometry = readPoints(gs["geometry"] as? JsonArray)
                    )
                }
            )
        }
        val route = Route(
            stops = stops,
            geometry = readPoints(o["geometry"] as? JsonArray),
            distanceMeters = o["distance"]!!.jsonPrimitive.double,
            durationSeconds = o["duration"]!!.jsonPrimitive.double,
            steps = steps,
            transit = transit
        )
        return SavedRoute(
            id = o.s("id")!!,
            title = o.s("title").orEmpty(),
            savedAt = o["savedAt"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L,
            start = readPoints(o["start"] as? JsonArray).firstOrNull(),
            route = route
        )
    }
}
