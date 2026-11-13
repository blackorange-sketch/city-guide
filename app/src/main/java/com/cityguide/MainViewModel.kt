package com.cityguide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cityguide.data.Api
import com.cityguide.data.Category
import com.cityguide.data.City
import com.cityguide.data.Place
import com.cityguide.data.Route
import com.cityguide.domain.RoutePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class UiState(
    val query: String = "",
    val loading: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val city: City? = null,
    val places: List<Place> = emptyList(),
    val categories: Set<Category> = emptySet(),
    val durationMin: Int = 120,
    val route: Route? = null
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val lang: String = Locale.getDefault().language.ifBlank { "en" }

    fun onQuery(query: String) = _state.update { it.copy(query = query) }

    fun setDuration(minutes: Int) = _state.update { it.copy(durationMin = minutes) }

    fun toggleCategory(category: Category) = _state.update {
        val next = if (category in it.categories) it.categories - category else it.categories + category
        it.copy(categories = next)
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty() || _state.value.loading) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true, error = null, route = null,
                    places = emptyList(), status = "Шукаю місто…"
                )
            }
            try {
                val city = Api.geocode(query, lang) ?: error("Місто не знайдено")
                _state.update { it.copy(city = city, status = "Завантажую визначні місця…") }

                val raw = Api.fetchPlaces(city, SEARCH_RADIUS_M)
                if (raw.isEmpty()) error("Поблизу не знайдено місць з описом у Вікіпедії")

                _state.update { it.copy(status = "Оцінюю значущість місць…") }
                val info = Api.fetchWikidata(raw.map { it.wikidataId }, lang)

                val places = raw.mapNotNull { r ->
                    val wiki = info[r.wikidataId]
                    val name = wiki?.label ?: r.name ?: return@mapNotNull null
                    Place(
                        id = r.id,
                        wikidataId = r.wikidataId,
                        name = name,
                        description = wiki?.description,
                        lat = r.lat,
                        lon = r.lon,
                        category = RoutePlanner.categorize(r.tags),
                        score = wiki?.sitelinks ?: 0
                    )
                }.sortedByDescending { it.score }.take(MAX_PLACES)

                _state.update { it.copy(places = places, status = "Знайдено місць: ${places.size}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Помилка мережі", status = null) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun buildRoute() {
        val s = _state.value
        val city = s.city ?: return
        if (s.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, status = "Будую маршрут…") }
            try {
                val chosen = RoutePlanner.pick(
                    s.places, s.categories, RoutePlanner.stopsForDuration(s.durationMin)
                )
                if (chosen.size < 2) error("Замало місць для маршруту. Оберіть більше категорій")
                val ordered = RoutePlanner.order(city.pos, chosen)
                val result = Api.route(ordered.map { it.pos }) ?: error("Не вдалося побудувати маршрут")
                val route = Route(ordered, result.geometry, result.distance, result.duration)
                _state.update { it.copy(route = route, status = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Помилка мережі", status = null) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private companion object {
        const val SEARCH_RADIUS_M = 2500
        const val MAX_PLACES = 60
    }
}
