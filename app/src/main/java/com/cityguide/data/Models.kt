package com.cityguide.data

import androidx.annotation.StringRes
import com.cityguide.R

data class LatLon(val lat: Double, val lon: Double)

data class City(val name: String, val lat: Double, val lon: Double) {
    val pos: LatLon get() = LatLon(lat, lon)
}

enum class Category(@StringRes val labelRes: Int, val color: String) {
    HISTORY(R.string.cat_history, "#8D6E63"),
    MUSEUMS(R.string.cat_museums, "#5E35B1"),
    VIEWS(R.string.cat_views, "#039BE5"),
    RELIGION(R.string.cat_religion, "#FB8C00"),
    PARK(R.string.cat_park, "#2E7D32"),
    OTHER(R.string.cat_other, "#E53935"),
    FOOD(R.string.cat_food, "#F9A825");

    companion object {
        /** Категорії визначних місць (без закладів харчування). */
        val sights: List<Category> get() = entries.filter { it != FOOD }
    }
}

data class Place(
    val id: String,
    val wikidataId: String?,
    val name: String,
    val description: String?,
    val lat: Double,
    val lon: Double,
    val category: Category,
    /**
     * Для визначних місць: кількість мовних версій статті у Вікіпедії.
     * Для закладів: кількість заповнених корисних тегів (кухня, години, сайт тощо).
     */
    val score: Int,
    val wikiTitle: String? = null,
    val wikiLang: String? = null,
    val website: String? = null,
    val hours: String? = null,
    /** Рейтинг закладу (0-10, Foursquare) і цінова категорія 1-4, якщо відомі. */
    val rating: Double? = null,
    val price: Int? = null,
    /** Відібрані теги OSM для екрана докладної інформації. */
    val tags: Map<String, String> = emptyMap()
) {
    val pos: LatLon get() = LatLon(lat, lon)
}

/** Тип факту на екрані докладної інформації. */
enum class FactKind(@StringRes val labelRes: Int) {
    ADDRESS(R.string.fact_address),
    PHONE(R.string.fact_phone),
    EMAIL(R.string.fact_email),
    HOURS(R.string.fact_hours),
    CUISINE(R.string.fact_cuisine),
    OUTDOOR(R.string.fact_outdoor),
    TAKEAWAY(R.string.fact_takeaway),
    DELIVERY(R.string.fact_delivery),
    VEGETARIAN(R.string.fact_vegetarian),
    WHEELCHAIR(R.string.fact_wheelchair),
    FEE(R.string.fact_fee),
    INCEPTION(R.string.fact_inception),
    ARCHITECT(R.string.fact_architect),
    STYLE(R.string.fact_style),
    HERITAGE(R.string.fact_heritage),
    HEIGHT(R.string.fact_height)
}

data class InfoFact(val kind: FactKind, val value: String)

/** Розділ статті Вікіпедії: заголовок (null для вступу) і текст з абзацами. */
data class ArticleSection(val title: String?, val text: String)

/** Фото й розгорнутий опис зі Вікіпедії, що завантажуються при виборі місця. */
data class PlaceDetail(val extract: String?, val imageUrl: String?, val pageUrl: String?)

/** Маневр на шляху (з OSRM): де саме й що робити. */
data class Step(
    val lat: Double,
    val lon: Double,
    val type: String,
    val modifier: String?,
    val street: String,
    val distance: Double,
    val exit: Int?
)

data class Route(
    val stops: List<Place>,
    val geometry: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<Step> = emptyList()
)
