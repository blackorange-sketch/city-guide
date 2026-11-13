package com.cityguide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cityguide.data.Api
import com.cityguide.data.Category
import com.cityguide.data.City
import com.cityguide.data.Place
import com.cityguide.data.PlaceDetail
import com.cityguide.data.Route
import com.cityguide.data.Suggestion
import com.cityguide.domain.RoutePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val suggestions: List<Suggestion> = emptyList(),
    val places: List<Place> = emptyList(),
    val foods: List<Place> = emptyList(),
    val showFood: Boolean = false,
    val extended: Boolean = false,
    val radiusKm: Double = 2.5,
    val categories: Set<Category> = emptySet(),
    val durationMin: Int = 120,
    val route: Route? = null,
    val selected: Place? = null,
    val detail: PlaceDetail? = null
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val lang: String = Locale.getDefault().language.ifBlank { "en" }
    private var suggestJob: Job? = null
    private var detailJob: Job? = null
    private var routeJob: Job? = null
    private val detailCache = mutableMapOf<String, PlaceDetail>()

    // --- введення й підказки ---

    fun onQuery(query: String) {
        _state.update { it.copy(query = query) }
        suggestJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(350)
            val list = try {
                Api.suggest(query.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(suggestions = list) }
        }
    }

    fun selectSuggestion(suggestion: Suggestion) {
        suggestJob?.cancel()
        _state.update { it.copy(query = suggestion.name, suggestions = emptyList()) }
        launchLoading("Завантажую визначні місця…") {
            loadPlaces(City(suggestion.name, suggestion.lat, suggestion.lon))
        }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        suggestJob?.cancel()
        val first = _state.value.suggestions.firstOrNull()
        if (first != null) {
            selectSuggestion(first)
            return
        }
        launchLoading("Шукаю місто…") {
            val city = Api.geocode(query, lang) ?: error("Місто не знайдено")
            loadPlaces(city)
        }
    }

    /** Пошук навколо поточного місцезнаходження; [provider] віддає (широта, довгота). */
    fun locate(provider: suspend () -> Pair<Double, Double>?) {
        suggestJob?.cancel()
        launchLoading("Визначаю місцезнаходження…") {
            val (lat, lon) = provider()
                ?: error("Не вдалося визначити місцезнаходження. Перевірте, чи увімкнена геолокація")
            val name = Api.reverse(lat, lon, lang) ?: "Моє місцезнаходження"
            _state.update { it.copy(query = name, suggestions = emptyList()) }
            loadPlaces(City(name, lat, lon))
        }
    }

    fun showError(message: String) = _state.update { it.copy(error = message, status = null) }

    // --- налаштування пошуку ---

    fun setDuration(minutes: Int) = _state.update { it.copy(durationMin = minutes) }

    fun toggleCategory(category: Category) = _state.update {
        val next = if (category in it.categories) it.categories - category else it.categories + category
        it.copy(categories = next)
    }

    fun setRadius(km: Double) {
        _state.update { it.copy(radiusKm = km) }
        reload()
    }

    fun setExtended(value: Boolean) {
        _state.update { it.copy(extended = value) }
        reload()
    }

    fun toggleFood() {
        val s = _state.value
        val turnOn = !s.showFood
        _state.update { it.copy(showFood = turnOn) }
        val city = s.city ?: return
        if (turnOn && s.foods.isEmpty()) {
            launchLoading("Шукаю заклади харчування…") { loadFood(city) }
        }
    }

    // --- вибране місце ---

    fun selectPlace(id: String) {
        val s = _state.value
        val place = s.places.firstOrNull { it.id == id }
            ?: s.foods.firstOrNull { it.id == id }
            ?: s.route?.stops?.firstOrNull { it.id == id }
            ?: return
        _state.update { it.copy(selected = place, detail = detailCache[place.id]) }

        detailJob?.cancel()
        val title = place.wikiTitle
        if (title != null && detailCache[place.id] == null) {
            detailJob = viewModelScope.launch {
                val detail = Api.placeDetail(place.wikiLang ?: lang, title) ?: return@launch
                detailCache[place.id] = detail
                _state.update { if (it.selected?.id == place.id) it.copy(detail = detail) else it }
            }
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = null, detail = null) }

    // --- побудова й редагування маршруту ---

    fun buildRoute() {
        val s = _state.value
        val city = s.city ?: return
        launchLoading("Будую маршрут…") {
            val chosen = RoutePlanner.pick(
                s.places, s.categories, RoutePlanner.stopsForDuration(s.durationMin)
            )
            if (chosen.size < 2) error("Замало місць для маршруту. Оберіть більше категорій")
            recompute(RoutePlanner.order(city.pos, chosen))
        }
    }

    fun clearRoute() = _state.update { it.copy(route = null) }

    fun removeStop(id: String) {
        val route = _state.value.route ?: return
        editStops(route.stops.filter { it.id != id })
    }

    fun moveStop(index: Int, delta: Int) {
        val route = _state.value.route ?: return
        val target = index + delta
        if (index !in route.stops.indices || target !in route.stops.indices) return
        val stops = route.stops.toMutableList()
        stops[index] = stops[target].also { stops[target] = stops[index] }
        editStops(stops)
    }

    fun addToRoute(place: Place) {
        val route = _state.value.route ?: return
        if (route.stops.any { it.id == place.id }) return
        editStops(RoutePlanner.insertBest(route.stops, place))
    }

    fun optimizeOrder() {
        val s = _state.value
        val route = s.route ?: return
        val city = s.city ?: return
        editStops(RoutePlanner.order(city.pos, route.stops))
    }

    /** Додає заклад харчування біля середини маршруту. */
    fun addLunch() {
        val s = _state.value
        val route = s.route ?: return
        val city = s.city ?: return
        launchLoading("Шукаю заклад біля маршруту…") {
            if (_state.value.foods.isEmpty()) loadFood(city)
            val food = RoutePlanner.lunchCandidate(route.stops, _state.value.foods)
                ?: error("Поблизу середини маршруту не знайдено закладів")
            val stops = route.stops.toMutableList()
            stops.add((stops.size - 1) / 2 + 1, food)
            _state.update { it.copy(showFood = true) }
            recompute(stops)
        }
    }

    // --- внутрішнє ---

    private fun radiusM(): Int = (_state.value.radiusKm * 1000).toInt()

    private fun reload() {
        val city = _state.value.city ?: return
        launchLoading("Оновлюю місця…") { loadPlaces(city) }
    }

    private suspend fun recompute(stops: List<Place>) {
        val result = Api.route(stops.map { it.pos }) ?: error("Не вдалося побудувати маршрут")
        val route = Route(stops, result.geometry, result.distance, result.duration)
        _state.update { it.copy(route = route, selected = null, detail = null, status = null) }
    }

    private fun editStops(stops: List<Place>) {
        if (stops.size < 2) {
            showError("У маршруті має лишатися щонайменше 2 зупинки")
            return
        }
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                recompute(stops)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "Помилка мережі")
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun loadPlaces(city: City) {
        _state.update {
            it.copy(
                city = city, route = null, selected = null, detail = null,
                places = emptyList(), foods = emptyList(),
                status = "Завантажую визначні місця…"
            )
        }
        val extended = _state.value.extended
        val raw = Api.fetchPlaces(city, radiusM(), extended)
        if (raw.isEmpty()) error("Поблизу не знайдено місць. Збільште радіус або увімкніть розширений пошук")

        _state.update { it.copy(status = "Оцінюю значущість місць…") }
        val info = Api.fetchWikidata(raw.mapNotNull { it.wikidataId }, lang)

        val places = raw.mapNotNull { r ->
            val wiki = r.wikidataId?.let { info[it] }
            val name = wiki?.label ?: r.name ?: return@mapNotNull null
            Place(
                id = r.id,
                wikidataId = r.wikidataId,
                name = name,
                description = wiki?.description,
                lat = r.lat,
                lon = r.lon,
                category = RoutePlanner.categorize(r.tags),
                score = wiki?.sitelinks ?: 0,
                wikiTitle = wiki?.wikiTitle,
                wikiLang = wiki?.wikiLang,
                website = r.tags["website"] ?: r.tags["contact:website"]
            )
        }.sortedByDescending { it.score }.take(if (extended) MAX_PLACES_EXTENDED else MAX_PLACES)

        _state.update { it.copy(places = places, status = null) }
        if (_state.value.showFood) loadFood(city)
    }

    private suspend fun loadFood(city: City) {
        _state.update { it.copy(status = "Шукаю заклади харчування…") }
        val foods = Api.fetchFood(city, radiusM()).map { r ->
            Place(
                id = r.id,
                wikidataId = null,
                name = r.name ?: "Заклад",
                description = RoutePlanner.foodDescription(r.tags),
                lat = r.lat,
                lon = r.lon,
                category = Category.FOOD,
                score = RoutePlanner.foodScore(r.tags),
                website = r.tags["website"] ?: r.tags["contact:website"],
                hours = r.tags["opening_hours"]
            )
        }.sortedByDescending { it.score }.take(MAX_FOOD)
        _state.update { it.copy(foods = foods, status = null) }
    }

    private fun launchLoading(status: String, block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, error = null, status = status, suggestions = emptyList())
            }
            try {
                block()
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
        const val MAX_PLACES = 60
        const val MAX_PLACES_EXTENDED = 150
        const val MAX_FOOD = 120
    }
}
