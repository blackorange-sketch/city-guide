package com.cityguide

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cityguide.data.Api
import com.cityguide.data.ArticleSection
import com.cityguide.data.AroundResult
import com.cityguide.data.Category
import com.cityguide.data.City
import com.cityguide.data.FactKind
import com.cityguide.data.HttpException
import com.cityguide.data.InfoFact
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
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

/** Екран докладної інформації про місце: стаття, фото, факти. */
data class InfoState(
    val place: Place,
    val loading: Boolean = true,
    val detail: PlaceDetail? = null,
    val article: List<ArticleSection> = emptyList(),
    val gallery: List<String> = emptyList(),
    val facts: List<InfoFact> = emptyList()
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
    /** Свій старт маршруту; null = авто (маршрут починається з першої зупинки). */
    val start: LatLon? = null,
    val pickingStart: Boolean = false,
    /** Макс. відстань закладу від лінії маршруту для «Обід поруч», м. */
    val foodMaxM: Int = 600,
    val route: Route? = null,
    val selected: Place? = null,
    val detail: PlaceDetail? = null,
    val nav: NavState = NavState(),
    val info: InfoState? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** Текст помилки для користувача: власні помилки вже локалізовані, мережеві перекладаємо тут. */
    private fun errorText(e: Exception): String = when (e) {
        is HttpException -> str(R.string.err_http, e.code, e.host)
        is IOException -> str(R.string.err_network) +
            "\n(${e.javaClass.simpleName}: ${e.message.orEmpty().take(90)})"
        else -> e.message ?: str(R.string.err_network)
    }

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
        launchLoading(str(R.string.status_loading_places)) {
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
        launchLoading(str(R.string.status_searching_city)) {
            val city = Api.geocode(query, lang) ?: error(str(R.string.err_city_not_found))
            loadPlaces(city)
        }
    }

    /** Пошук навколо поточного місцезнаходження; [provider] віддає (широта, довгота). */
    fun locate(provider: suspend () -> Pair<Double, Double>?) {
        suggestJob?.cancel()
        launchLoading(str(R.string.status_locating)) {
            val (lat, lon) = provider()
                ?: error(str(R.string.err_location_failed))
            val name = Api.reverse(lat, lon, lang) ?: str(R.string.my_location_name)
            _state.update { it.copy(query = name, suggestions = emptyList()) }
            loadPlaces(City(name, lat, lon))
        }
    }

    fun showError(message: String) = _state.update { it.copy(error = message, status = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    // --- налаштування пошуку ---

    fun setDuration(minutes: Int) = _state.update { it.copy(durationMin = minutes) }

    fun setFoodMax(meters: Int) = _state.update { it.copy(foodMaxM = meters) }

    fun setStartAuto() = _state.update { it.copy(start = null, pickingStart = false) }

    fun pickStartOnMap(on: Boolean) =
        _state.update { it.copy(pickingStart = on, selected = null, detail = null) }

    fun setStart(lat: Double, lon: Double) =
        _state.update { it.copy(start = LatLon(lat, lon), pickingStart = false) }

    /** Старт маршруту від поточного місцезнаходження; [provider] віддає (широта, довгота). */
    fun useMyLocationAsStart(provider: suspend () -> Pair<Double, Double>?) {
        launchLoading(str(R.string.status_locating)) {
            val (lat, lon) = provider()
                ?: error(str(R.string.err_location_failed))
            _state.update { it.copy(start = LatLon(lat, lon), pickingStart = false, status = null) }
        }
    }

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

    // --- докладна інформація ---

    private var infoJob: Job? = null

    private fun yesNo(value: String): String = when (value) {
        "yes" -> str(R.string.info_yes)
        "no" -> str(R.string.info_no)
        "limited" -> str(R.string.info_limited)
        else -> value
    }

    /** Факти з тегів OSM (адреса, телефон, години, кухня тощо). */
    private fun osmFacts(place: Place): List<InfoFact> {
        val t = place.tags
        val out = mutableListOf<InfoFact>()
        fun add(kind: FactKind, value: String?) {
            if (!value.isNullOrBlank()) out += InfoFact(kind, value)
        }
        val street = t["addr:street"]?.let { s -> t["addr:housenumber"]?.let { "$s $it" } ?: s }
        add(FactKind.ADDRESS, listOfNotNull(street, t["addr:city"]).joinToString(", "))
        add(FactKind.PHONE, t["phone"] ?: t["contact:phone"])
        add(FactKind.EMAIL, t["email"] ?: t["contact:email"])
        add(FactKind.HOURS, place.hours ?: t["opening_hours"])
        add(
            FactKind.CUISINE,
            t["cuisine"]?.split(';')?.joinToString(", ") {
                it.replace('_', ' ').trim().replaceFirstChar { c -> c.uppercase() }
            }
        )
        add(FactKind.OUTDOOR, t["outdoor_seating"]?.let(::yesNo))
        add(FactKind.TAKEAWAY, t["takeaway"]?.let(::yesNo))
        add(FactKind.DELIVERY, t["delivery"]?.let(::yesNo))
        add(FactKind.VEGETARIAN, t["diet:vegetarian"]?.let(::yesNo))
        add(FactKind.WHEELCHAIR, t["wheelchair"]?.let(::yesNo))
        add(FactKind.FEE, t["fee"]?.let(::yesNo))
        add(FactKind.INCEPTION, t["start_date"])
        add(FactKind.ARCHITECT, t["architect"])
        add(FactKind.HEIGHT, t["height"]?.let { "$it m" })
        return out
    }

    /** Відкриває екран докладної інформації: одразу з тим, що вже є, решта підвантажується. */
    fun openInfo(place: Place) {
        infoJob?.cancel()
        val cached = detailCache[place.id]
            ?: _state.value.detail?.takeIf { _state.value.selected?.id == place.id }
        _state.update {
            it.copy(info = InfoState(place = place, detail = cached, facts = osmFacts(place)))
        }
        infoJob = viewModelScope.launch {
            val wikiLang = place.wikiLang ?: lang
            val title = place.wikiTitle
            val osm = osmFacts(place)
            val (article, gallery, wikiFacts) = coroutineScope {
                val a = async {
                    if (title == null) emptyList<ArticleSection>()
                    else runCatching { Api.fullArticle(wikiLang, title) }.getOrDefault(emptyList())
                }
                val g = async {
                    if (title == null) emptyList<String>()
                    else runCatching { Api.gallery(wikiLang, title) }.getOrDefault(emptyList())
                }
                val f = async {
                    place.wikidataId?.let { id ->
                        runCatching { Api.wikidataFacts(id, lang) }.getOrDefault(emptyList())
                    } ?: emptyList<InfoFact>()
                }
                Triple(a.await(), g.await(), f.await())
            }
            val detail = cached ?: title?.let { Api.placeDetail(wikiLang, it) }
            val known = osm.map { it.kind }.toSet()
            val facts = osm + wikiFacts.filter { it.kind !in known }
            _state.update { cur ->
                val info = cur.info ?: return@update cur
                if (info.place.id != place.id) return@update cur
                cur.copy(
                    info = info.copy(
                        loading = false,
                        detail = detail ?: info.detail,
                        article = article,
                        gallery = gallery,
                        facts = facts
                    )
                )
            }
        }
    }

    fun closeInfo() {
        infoJob?.cancel()
        _state.update { it.copy(info = null) }
    }

    fun clearSelection() = _state.update { it.copy(selected = null, detail = null) }

    // --- побудова й редагування маршруту ---

    fun buildRoute() {
        val s = _state.value
        val city = s.city ?: return
        launchLoading(str(R.string.status_building_route)) {
            val chosen = RoutePlanner.plan(
                s.places, s.categories, s.durationMin, s.start ?: city.pos, s.start != null
            )
            if (chosen.size < 2) error(str(R.string.err_few_places))
            recompute(chosen)
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
        editStops(RoutePlanner.insertBest(route.stops, place, _state.value.start))
    }

    fun optimizeOrder() {
        val s = _state.value
        val route = s.route ?: return
        val city = s.city ?: return
        editStops(RoutePlanner.order(s.start ?: city.pos, route.stops, s.start != null))
    }

    /** Додає заклад харчування біля середини маршруту. */
    fun addLunch() {
        val s = _state.value
        val route = s.route ?: return
        launchLoading(str(R.string.status_finding_lunch)) {
            val max = _state.value.foodMaxM
            val food = RoutePlanner.lunchCandidate(
                route.stops, _state.value.foods, route.geometry, max.toDouble()
            ) ?: error(str(R.string.err_no_food, max))
            _state.update { it.copy(showFood = true) }
            recompute(RoutePlanner.insertBest(route.stops, food, _state.value.start))
        }
    }

    // --- внутрішнє ---

    private fun radiusM(): Int = (_state.value.radiusKm * 1000).toInt()

    private fun reload() {
        val city = _state.value.city ?: return
        launchLoading(str(R.string.status_refreshing)) { loadPlaces(city) }
    }

    private suspend fun recompute(stops: List<Place>) {
        val points = listOfNotNull(_state.value.start) + stops.map { it.pos }
        val result = Api.route(points) ?: error(str(R.string.err_route_failed))
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
            showError(str(R.string.err_route_too_short))
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
                    announcement = announcement(str(R.string.nav_started))
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
            announce = announcement(str(R.string.nav_arrived, target.name))
            nextStop++
            if (nextStop >= route.stops.size) {
                finished = true
                announce = announcement(str(R.string.nav_finished, target.name))
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
            instruction = Instructions.text(getApplication<Application>(), step)
            distManeuver = max(0.0, tr.stepAlong[stepIdx] - along)
            if (announce == null && distManeuver <= ANNOUNCE_DISTANCE_M && lastAnnouncedStep != stepIdx) {
                lastAnnouncedStep = stepIdx
                val meters = (distManeuver / 10).roundToInt() * 10
                val text = Instructions.text(getApplication<Application>(), step, capitalize = false)
                announce = announcement(
                    if (meters >= 20) str(R.string.nav_in_meters, meters, text)
                    else text.replaceFirstChar { it.uppercase() }
                )
            }
        } else if (nextTarget != null) {
            instruction = str(R.string.nav_head_to, nextTarget.name)
        }
        if (announce == null && nextTarget != null && distToStop <= 60.0 && lastAnnouncedStop != nextStop) {
            lastAnnouncedStop = nextStop
            announce = announcement(str(R.string.nav_ahead, nextTarget.name))
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
                    ?: error(str(R.string.err_route_failed))
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
                            announcement = announcement(str(R.string.nav_rerouted))
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
            showError(str(R.string.err_min_stops))
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
                showError(errorText(e))
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Теги OSM, потрібні екрану докладної інформації (решту не зберігаємо). */
    private fun keepTags(tags: Map<String, String>): Map<String, String> =
        tags.filterKeys { it in INFO_TAG_KEYS }

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
            website = r.tags["website"] ?: r.tags["contact:website"],
            hours = r.tags["opening_hours"],
            tags = keepTags(r.tags)
        )
    }

    private fun toFood(r: RawPlace) = Place(
        id = r.id,
        wikidataId = null,
        name = r.name ?: r.tags["brand"] ?: str(R.string.food_generic),
        description = RoutePlanner.foodDescription(getApplication<Application>(), r.tags),
        lat = r.lat,
        lon = r.lon,
        category = Category.FOOD,
        score = RoutePlanner.foodScore(r.tags),
        website = r.tags["website"] ?: r.tags["contact:website"],
        hours = r.tags["opening_hours"],
        tags = keepTags(r.tags)
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
                start = null, pickingStart = false,
                status = str(R.string.status_loading_places)
            )
        }
        val extended = _state.value.extended
        val limit = if (extended) MAX_PLACES_EXTENDED else MAX_PLACES
        val fullRadius = radiusM()

        // Великий радіус вантажимо порціями: спершу центр (швидко), потім весь радіус
        if (fullRadius > QUICK_RADIUS_M) {
            val quick = try {
                Api.fetchAround(city, QUICK_RADIUS_M, extended, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (quick != null) publish(quick, limit, strict = false)
        }
        val around = Api.fetchAround(city, fullRadius, extended, lang)
        publish(around, limit, strict = true)
        if (Api.hasRatings) viewModelScope.launch { mergeRatings(city) }
    }

    /** Показує завантажені дані: спершу попередній список, потім з оцінкою значущості. */
    private suspend fun publish(around: AroundResult, limit: Int, strict: Boolean) {

        val foods = around.foods.map { toFood(it) }.sortedByDescending { it.score }.take(MAX_FOOD)
        // Попередній список без місць з Вікіпедії, чия значущість ще невідома
        val preliminary = around.sights
            .filter { it.tags[Api.WIKI_ONLY_TAG] == null }
            .mapNotNull { toPlace(it, null) }.take(limit)
        _state.update {
            it.copy(places = preliminary, foods = foods, status = str(R.string.status_scoring))
        }

        val info = try {
            Api.fetchWikidata(around.sights.mapNotNull { it.wikidataId }, lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val places = around.sights
            // Місця лише з геопошуку Вікіпедії беремо, якщо вони достатньо відомі
            .filter { r ->
                if (r.tags[Api.WIKI_ONLY_TAG] == null) return@filter true
                val wiki = r.wikidataId?.let { info[it] }
                // З геопошуку Вікіпедії лише достатньо відомі об'єкти, без адмінодиниць і природних зон
                (wiki?.sitelinks ?: 0) >= MIN_WIKI_ONLY_SITELINKS &&
                    wiki?.descriptionEn?.let { ADMIN_DESCRIPTION.containsMatchIn(it) } != true
            }
            .mapNotNull { toPlace(it, it.wikidataId?.let { id -> info[id] }) }
            .sortedByDescending { it.score }
            .take(limit)
        if (places.isEmpty()) {
            if (strict) error(str(R.string.err_no_places))
            return
        }
        _state.update {
            it.copy(
                places = places,
                status = null,
                error = if (around.foodsUnavailable) str(R.string.err_food_unavailable) else it.error
            )
        }
    }

    private fun sameName(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val x = norm(a)
        val y = norm(b)
        return x.length >= 3 && y.length >= 3 && (x.contains(y) || y.contains(x))
    }

    /** Додає рейтинги Foursquare до закладів OSM (за назвою й відстанню) та нові високооцінені місця. */
    private suspend fun mergeRatings(city: City) {
        val rated = try {
            Api.fetchRated(city, radiusM())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (rated.isEmpty()) return
        _state.update { cur ->
            if (cur.city != city) return@update cur
            val foods = cur.foods.toMutableList()
            for (r in rated) {
                val label = "★ " + "%.1f".format(r.rating) + (r.price?.let { " · " + "$".repeat(it) } ?: "")
                val score = FSQ_SCORE_BASE + (r.rating * 5).toInt()
                val idx = foods.indexOfFirst {
                    Geo.distance(it.pos, LatLon(r.lat, r.lon)) < 70.0 && sameName(it.name, r.name)
                }
                if (idx >= 0) {
                    val old = foods[idx]
                    foods[idx] = old.copy(
                        description = listOfNotNull(label, old.description).joinToString(" · "),
                        score = score,
                        rating = r.rating,
                        price = r.price,
                        website = old.website ?: r.website
                    )
                } else {
                    foods.add(
                        Place(
                            id = "fsq/${r.id}",
                            wikidataId = null,
                            name = r.name,
                            description = label,
                            lat = r.lat,
                            lon = r.lon,
                            category = Category.FOOD,
                            score = score,
                            website = r.website,
                            rating = r.rating,
                            price = r.price
                        )
                    )
                }
            }
            cur.copy(foods = foods.sortedByDescending { it.score }.take(MAX_FOOD))
        }
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
                _state.update { it.copy(error = errorText(e), status = null) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private companion object {
        const val MAX_PLACES = 60
        const val MAX_PLACES_EXTENDED = 150
        const val MAX_FOOD = 600
        val ADMIN_DESCRIPTION = Regex(
            "municipality|county|gmina|commune|district|voivodeship|province|region|\\b(city|town) (in|of|and|with|on)\\b|^(city|town)\\b|" +
                "village|settlement|locality|hamlet|borough|neighbou?rhood|suburb|country|" +
                "national park|nature reserve|landscape park|protected area|river|lake|island|forest",
            RegexOption.IGNORE_CASE
        )
        val INFO_TAG_KEYS = setOf(
            "opening_hours", "phone", "contact:phone", "email", "contact:email", "cuisine",
            "outdoor_seating", "takeaway", "delivery", "diet:vegetarian", "wheelchair", "fee",
            "addr:street", "addr:housenumber", "addr:city", "start_date", "architect", "height"
        )
        const val QUICK_RADIUS_M = 1500
        const val FSQ_SCORE_BASE = 20
        const val MIN_WIKI_ONLY_SITELINKS = 4
        const val ARRIVE_RADIUS_M = 35.0
        const val ANNOUNCE_DISTANCE_M = 150.0
        const val OFF_ROUTE_M = 50.0
        const val REROUTE_COOLDOWN_MS = 15_000L
    }
}
