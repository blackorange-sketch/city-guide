package com.cityguide.domain

import com.cityguide.data.Category
import com.cityguide.data.LatLon
import com.cityguide.data.Place
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Відстань між двома точками в метрах (формула гаверсинуса). */
    fun distance(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

object RoutePlanner {

    /** Скільки зупинок вміщається в прогулянку: орієнтовно 30 хв на зупинку з дорогою. */
    fun stopsForDuration(minutes: Int): Int = (minutes / 30).coerceIn(2, 10)

    fun categorize(tags: Map<String, String>): Category = when {
        tags["tourism"] == "museum" || tags["tourism"] == "gallery" -> Category.MUSEUMS
        tags["tourism"] == "viewpoint" -> Category.VIEWS
        tags["amenity"] == "place_of_worship" -> Category.RELIGION
        tags.containsKey("historic") -> Category.HISTORY
        tags["leisure"] in setOf("park", "garden", "nature_reserve") -> Category.PARK
        else -> Category.OTHER
    }

    // --- заклади харчування ---

    private val FOOD_TYPES = mapOf(
        "cafe" to "Кафе",
        "restaurant" to "Ресторан",
        "fast_food" to "Фастфуд",
        "pub" to "Паб",
        "bar" to "Бар",
        "ice_cream" to "Морозиво"
    )

    private val USEFUL_FOOD_TAGS = listOf(
        "cuisine", "opening_hours", "website", "contact:website", "phone", "contact:phone",
        "diet:vegetarian", "outdoor_seating", "wheelchair", "takeaway", "delivery"
    )

    /** Рейтингів у OSM немає, тож "якість" заклади оцінюємо за повнотою даних про них. */
    fun foodScore(tags: Map<String, String>): Int = USEFUL_FOOD_TAGS.count { tags.containsKey(it) }

    fun foodDescription(tags: Map<String, String>): String {
        val type = FOOD_TYPES[tags["amenity"]] ?: "Заклад"
        val cuisine = tags["cuisine"]?.split(';')?.firstOrNull()?.replace('_', ' ')?.trim()
        return if (cuisine.isNullOrEmpty()) type else "$type · $cuisine"
    }

    /** Найкращий заклад біля середини маршруту: до 600 м, спершу з повнішими даними, потім найближчий. */
    /**
     * Заклад не далі ніж [maxM] метрів від лінії маршруту. Перевага закладам із середньої частини
     * маршруту (25-75% шляху), далі за повнотою даних і близькістю до маршруту.
     */
    fun lunchCandidate(
        stops: List<Place>,
        foods: List<Place>,
        geometry: List<LatLon>,
        maxM: Double
    ): Place? {
        if (geometry.size < 2) return null
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) cum[i] = cum[i - 1] + Geo.distance(geometry[i - 1], geometry[i])
        val total = cum.last()

        class Hit(val place: Place, val dist: Double, val frac: Double)

        val hits = foods
            .filter { f -> stops.none { it.id == f.id } }
            .mapNotNull { f ->
                var bestD = Double.MAX_VALUE
                var bestI = 0
                for (i in geometry.indices) {
                    val d = Geo.distance(geometry[i], f.pos)
                    if (d < bestD) {
                        bestD = d
                        bestI = i
                    }
                }
                if (bestD <= maxM) Hit(f, bestD, if (total > 0) cum[bestI] / total else 0.5) else null
            }
        if (hits.isEmpty()) return null
        val middle = hits.filter { it.frac in 0.25..0.75 }.ifEmpty { hits }
        return middle
            .sortedWith(compareByDescending<Hit> { it.place.score }.thenBy { it.dist })
            .first().place
    }

    // --- підбір і порядок ---

    /** Найзначущіші місця обраних категорій (порожній набір = усі категорії). */
    fun pick(places: List<Place>, categories: Set<Category>, count: Int): List<Place> =
        places
            .filter { categories.isEmpty() || it.category in categories }
            .sortedByDescending { it.score }
            .take(count)

    /**
     * Порядок обходу: найближчий сусід від [start], потім покращення 2-opt.
     * Якщо [fixedStart], маршрут обов'язково починається зі [start] (заданий користувачем).
     */
    fun order(start: LatLon, places: List<Place>, fixedStart: Boolean = false): List<Place> {
        if (places.size <= 2) return places
        val remaining = places.toMutableList()
        val path = mutableListOf<Place>()
        var current = start
        while (remaining.isNotEmpty()) {
            val next = remaining.minBy { Geo.distance(current, it.pos) }
            remaining.remove(next)
            path.add(next)
            current = next.pos
        }
        return twoOpt(path, start.takeIf { fixedStart })
    }

    /** Додає місце в позицію, що найменше подовжує маршрут. */
    fun insertBest(stops: List<Place>, place: Place, start: LatLon? = null): List<Place> {
        if (stops.size < 2) return stops + place
        var bestIndex = stops.size
        var bestCost = Geo.distance(stops.last().pos, place.pos)
        if (start == null) {
            val startCost = Geo.distance(place.pos, stops.first().pos)
            if (startCost < bestCost) {
                bestCost = startCost
                bestIndex = 0
            }
        } else {
            // Старт зафіксований: вставка на початок рахується від нього
            val cost = Geo.distance(start, place.pos) + Geo.distance(place.pos, stops.first().pos) -
                Geo.distance(start, stops.first().pos)
            if (cost < bestCost) {
                bestCost = cost
                bestIndex = 0
            }
        }
        for (i in 1 until stops.size) {
            val cost = Geo.distance(stops[i - 1].pos, place.pos) +
                Geo.distance(place.pos, stops[i].pos) -
                Geo.distance(stops[i - 1].pos, stops[i].pos)
            if (cost < bestCost) {
                bestCost = cost
                bestIndex = i
            }
        }
        return stops.toMutableList().apply { add(bestIndex, place) }
    }

    private fun twoOpt(path: MutableList<Place>, fixed: LatLon?): List<Place> {
        fun d(a: LatLon, b: LatLon) = Geo.distance(a, b)
        var improved = true
        while (improved) {
            improved = false
            for (i in 0 until path.size - 1) {
                for (j in i + 1 until path.size) {
                    val before = if (i > 0) path[i - 1].pos else fixed
                    val after = if (j < path.size - 1) path[j + 1].pos else null
                    val oldLen = (before?.let { d(it, path[i].pos) } ?: 0.0) +
                        (after?.let { d(path[j].pos, it) } ?: 0.0)
                    val newLen = (before?.let { d(it, path[j].pos) } ?: 0.0) +
                        (after?.let { d(path[i].pos, it) } ?: 0.0)
                    if (newLen + 1e-6 < oldLen) {
                        path.subList(i, j + 1).reverse()
                        improved = true
                    }
                }
            }
        }
        return path
    }
}
