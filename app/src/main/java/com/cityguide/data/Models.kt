package com.cityguide.data

data class LatLon(val lat: Double, val lon: Double)

data class City(val name: String, val lat: Double, val lon: Double) {
    val pos: LatLon get() = LatLon(lat, lon)
}

enum class Category(val label: String, val color: String) {
    HISTORY("Історія", "#8D6E63"),
    MUSEUMS("Музеї", "#5E35B1"),
    VIEWS("Краєвиди", "#039BE5"),
    RELIGION("Храми", "#FB8C00"),
    OTHER("Інше", "#E53935")
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
