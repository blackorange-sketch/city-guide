package com.cityguide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cityguide.data.Api
import com.cityguide.data.Category
import com.cityguide.data.City
import com.cityguide.data.LatLon
import com.cityguide.data.Place
import com.cityguide.data.PlaceDetail
import com.cityguide.data.RawPlace
import com.cityguide.data.Route
import com.cityguide.data.Suggestion
import com.cityguide.data.WikiInfo
import com.cityguide.domain.Geo
import com.cityguide.domain.Instructions
import com.cityguide.domain.RoutePlanner
import com.cityguide.domain.RouteTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class Announcement(val id: Int, val text: String)

/** Стан навігації всередині застосунку. */
data class NavState(
    val active: Boolean = false,
    val finished: Boolean = false,
    val user: LatLon? = null,
    val bearing: Float = 0f,
    /** Положення користувача, прив'язане до лінії маршруту, і номер сегмента. */
    val snapped: LatLon? = null,
    val snapSegment: Int = 0,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val nextStopIndex: Int = 0,
    /** Індекс наступного маневру в route.steps (steps.size, якщо поворотів більше немає). */
    val nextStepIndex: Int = 0,
    val distToNextStop: Double = 0.0,
    val arrow: String = "↑",
    val instruction: String = "",
    val distToManeuver: Double = 0.0,
    val remainingM: Double = 0.0,
    val remainingSec: Double = 0.0,
    val follow: Boolean = true,
    val voice: Boolean = true,
    val announcement: Announcement? = null
)

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
    val detail: PlaceDetail? = null,
    val nav: NavState = NavState()
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
        _state.update { it.copy(showFood = !s.showFood) }
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
        launchLoading("Шукаю заклад біля маршруту…") {
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
        val route = Route(stops, result.geometry, result.distance, result.duration, result.steps)
        _state.update { it.copy(route = route, selected = null, detail = null, status = null) }
    }

    // --- навігація ---

    private var tracker: RouteTracker? = null
    private var lastAnnouncedStep = -1
    private var lastAnnouncedStop = -1
    private var offRouteCount = 0
    private var lastRerouteAt = 0L
    private var announceCounter = 0

    private fun announcement(text: String) = Announcement(++announceCounter, text)

    fun startNavigation() {
        val route = _state.value.route ?: return
        if (route.geometry.size < 2) {
            showError("Маршрут занадто короткий для навігації")
            return
        }
        tracker = RouteTracker(route.geometry, route.steps, route.stops.map { it.pos }, 0)
        lastAnnouncedStep = -1
        lastAnnouncedStop = -1
        offRouteCount = 0
        lastRerouteAt = 0L
        _state.update {
            it.copy(
                selected = null, detail = null, error = null,
                nav = NavState(
                    active = true,
                    voice = it.nav.voice,
                    announcement = announcement("Навігацію розпочато")
                )
            )
        }
    }

    fun stopNavigation() {
        tracker = null
        _state.update { it.copy(nav = NavState(voice = it.nav.voice)) }
    }

    fun setVoice(value: Boolean) = _state.update { it.copy(nav = it.nav.copy(voice = value)) }

    fun setFollow(value: Boolean) = _state.update { it.copy(nav = it.nav.copy(follow = value)) }

    fun skipStop() {
        val s = _state.value
        val route = s.route ?: return
        val next = s.nav.nextStopIndex + 1
        _state.update { it.copy(nav = it.nav.copy(nextStopIndex = next, finished = next >= route.stops.size)) }
    }

    /** Нове положення з GPS: прогрес уздовж маршруту, підказки, прибуття, відхилення від маршруту. */
    fun onLocation(lat: Double, lon: Double, bearing: Float?) {
        val s = _state.value
        val nav = s.nav
        val route = s.route ?: return
        val tr = tracker ?: return
        if (!nav.active || nav.finished) return

        val user = LatLon(lat, lon)
        val snap = tr.snap(user, nav.snapSegment)
        val along = snap.along
        var announce: Announcement? = null

        // Прибуття на зупинку
        var nextStop = nav.nextStopIndex
        var finished = false
        val target = route.stops.getOrNull(nextStop)
        if (target != null && Geo.distance(user, target.pos) < ARRIVE_RADIUS_M) {
            announce = announcement("Ви прибули: ${target.name}")
            nextStop++
            if (nextStop >= route.stops.size) {
                finished = true
                announce = announcement("Маршрут завершено. Ви прибули: ${target.name}")
            }
        }

        val nextTarget = route.stops.getOrNull(nextStop)
        val distToStop = if (nextTarget != null) max(0.0, tr.alongToStop(nextStop) - along) else 0.0

        // Наступний маневр
        val stepIdx = tr.nextStepIndex(along)
        var arrow = "↑"
        var instruction = ""
        var distManeuver = distToStop
        if (stepIdx != null) {
            val step = tr.steps[stepIdx]
            arrow = Instructions.arrow(step)
            instruction = Instructions.text(step)
            distManeuver = max(0.0, tr.stepAlong[stepIdx] - along)
            if (announce == null && distManeuver <= ANNOUNCE_DISTANCE_M && lastAnnouncedStep != stepIdx) {
                lastAnnouncedStep = stepIdx
                val meters = (distManeuver / 10).roundToInt() * 10
                val text = instruction.replaceFirstChar { it.lowercase() }
                announce = announcement(if (meters >= 20) "Через $meters метрів $text" else text)
            }
        } else if (nextTarget != null) {
            instruction = "Прямуйте до: ${nextTarget.name}"
        }
        if (announce == null && nextTarget != null && distToStop <= 60.0 && lastAnnouncedStop != nextStop) {
            lastAnnouncedStop = nextStop
            announce = announcement("Попереду: ${nextTarget.name}")
        }

        val remaining = max(0.0, tr.length - along)
        val offRoute = snap.offRoute > OFF_ROUTE_M
        _state.update { cur ->
            cur.copy(
                nav = cur.nav.copy(
                    user = user,
                    bearing = bearing ?: cur.nav.bearing,
                    snapped = snap.point,
                    snapSegment = snap.segment,
                    offRoute = offRoute,
                    nextStopIndex = nextStop,
                    nextStepIndex = stepIdx ?: route.steps.size,
                    distToNextStop = distToStop,
                    arrow = arrow,
                    instruction = instruction,
                    distToManeuver = distManeuver,
                    remainingM = remaining,
                    remainingSec = route.durationSeconds * remaining / max(tr.length, 1.0),
                    finished = finished,
                    announcement = announce ?: cur.nav.announcement
                )
            )
        }

        // Відхилення від маршруту: після кількох підряд оновлень перераховуємо шлях від поточного місця
        offRouteCount = if (offRoute) offRouteCount + 1 else 0
        val now = System.currentTimeMillis()
        if (offRouteCount >= 3 && !finished && !nav.rerouting && now - lastRerouteAt > REROUTE_COOLDOWN_MS) {
            lastRerouteAt = now
            reroute(user, nextStop)
        }
    }

    private fun reroute(user: LatLon, nextStop: Int) {
        val route = _state.value.route ?: return
        val remaining = route.stops.drop(nextStop)
        if (remaining.isEmpty()) return
        _state.update { it.copy(nav = it.nav.copy(rerouting = true)) }
        viewModelScope.launch {
            try {
                val result = Api.route(listOf(user) + remaining.map { it.pos })
                    ?: error("Не вдалося перерахувати маршрут")
                tracker = RouteTracker(result.geometry, result.steps, remaining.map { it.pos }, nextStop)
                lastAnnouncedStep = -1
                offRouteCount = 0
                _state.update {
                    it.copy(
                        route = route.copy(
                            geometry = result.geometry,
                            distanceMeters = result.distance,
                            durationSeconds = result.duration,
                            steps = result.steps
                        ),
                        nav = it.nav.copy(
                            rerouting = false, offRoute = false, snapSegment = 0,
                            announcement = announcement("Маршрут перераховано")
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(nav = it.nav.copy(rerouting = false)) }
            }
        }
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

    private fun toPlace(r: RawPlace, wiki: WikiInfo?): Place? {
        val name = wiki?.label ?: r.name ?: return null
        return Place(
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
    }

    private fun toFood(r: RawPlace) = Place(
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

    /**
     * Один запит Overpass дає і місця, і заклади. Попередній список показуємо одразу,
     * а після відповіді Wikidata (паралельно, з кешем) уточнюємо назви й значущість.
     */
    private suspend fun loadPlaces(city: City) {
        _state.update {
            it.copy(
                city = city, route = null, selected = null, detail = null,
                places = emptyList(), foods = emptyList(),
                status = "Завантажую місця та заклади…"
            )
        }
        val extended = _state.value.extended
        val limit = if (extended) MAX_PLACES_EXTENDED else MAX_PLACES
        val around = Api.fetchAround(city, radiusM(), extended)
        if (around.sights.isEmpty()) {
            error("Поблизу не знайдено місць. Збільште радіус або увімкніть розширений пошук")
        }

        val foods = around.foods.map { toFood(it) }.sortedByDescending { it.score }.take(MAX_FOOD)
        val preliminary = around.sights.mapNotNull { toPlace(it, null) }.take(limit)
        _state.update {
            it.copy(places = preliminary, foods = foods, status = "Оцінюю значущість місць…")
        }

        val info = try {
            Api.fetchWikidata(around.sights.mapNotNull { it.wikidataId }, lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val places = around.sights.mapNotNull { toPlace(it, it.wikidataId?.let { id -> info[id] }) }
            .sortedByDescending { it.score }
            .take(limit)
        _state.update { it.copy(places = places, status = null) }
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
        const val ARRIVE_RADIUS_M = 35.0
        const val ANNOUNCE_DISTANCE_M = 150.0
        const val OFF_ROUTE_M = 50.0
        const val REROUTE_COOLDOWN_MS = 15_000L
    }
}
