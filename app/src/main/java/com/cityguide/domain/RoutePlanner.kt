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
        else -> Category.OTHER
    }

    /** Найзначущіші місця обраних категорій (порожній набір = усі категорії). */
    fun pick(places: List<Place>, categories: Set<Category>, count: Int): List<Place> =
        places
            .filter { categories.isEmpty() || it.category in categories }
            .sortedByDescending { it.score }
            .take(count)

    /** Порядок обходу: найближчий сусід від старту, потім покращення 2-opt. */
    fun order(start: LatLon, places: List<Place>): List<Place> {
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
        return twoOpt(path)
    }

    private fun twoOpt(path: MutableList<Place>): List<Place> {
        fun d(a: Place, b: Place) = Geo.distance(a.pos, b.pos)
        var improved = true
        while (improved) {
            improved = false
            for (i in 0 until path.size - 1) {
                for (j in i + 1 until path.size) {
                    val before = if (i > 0) path[i - 1] else null
                    val after = if (j < path.size - 1) path[j + 1] else null
                    val oldLen = (before?.let { d(it, path[i]) } ?: 0.0) +
                        (after?.let { d(path[j], it) } ?: 0.0)
                    val newLen = (before?.let { d(it, path[j]) } ?: 0.0) +
                        (after?.let { d(path[i], it) } ?: 0.0)
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
