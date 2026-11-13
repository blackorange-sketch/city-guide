package com.cityguide.data

data class LatLon(val lat: Double, val lon: Double)

data class City(val name: String, val lat: Double, val lon: Double) {
    val pos: LatLon get() = LatLon(lat, lon)
}

enum class Category(val label: String) {
    HISTORY("Історія"),
    MUSEUMS("Музеї"),
    VIEWS("Краєвиди"),
    RELIGION("Храми"),
    OTHER("Інше")
}

data class Place(
    val id: String,
    val wikidataId: String,
    val name: String,
    val description: String?,
    val lat: Double,
    val lon: Double,
    val category: Category,
    /** Кількість мовних версій статті у Вікіпедії: проста міра "значущості" місця. */
    val score: Int
) {
    val pos: LatLon get() = LatLon(lat, lon)
}

data class Route(
    val stops: List<Place>,
    val geometry: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Double
)
