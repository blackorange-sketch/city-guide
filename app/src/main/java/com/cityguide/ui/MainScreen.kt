package com.cityguide.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import com.cityguide.MainViewModel
import com.cityguide.NavState
import com.cityguide.Tts
import com.cityguide.UiState
import com.cityguide.currentLocation
import com.cityguide.locationUpdates
import com.cityguide.data.Api
import com.cityguide.data.Category
import com.cityguide.data.Place
import com.cityguide.data.PlaceDetail
import com.cityguide.data.Route
import kotlin.math.roundToInt

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

private val RADIUS_OPTIONS_KM = listOf(1.0, 2.5, 5.0, 10.0)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val density = context.resources.displayMetrics.density
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    var rootHeight by remember { mutableIntStateOf(0) }
    var searchBottom by remember { mutableIntStateOf(0) }
    var panelTop by remember { mutableIntStateOf(0) }

    fun startLocate() {
        val appContext = context.applicationContext
        vm.locate { currentLocation(appContext)?.let { it.latitude to it.longitude } }
    }

    var wantNav by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        when {
            !result.values.any { it } -> {
                wantNav = false
                vm.showError("Немає дозволу на геолокацію")
            }
            wantNav -> {
                wantNav = false
                vm.startNavigation()
            }
            else -> startLocate()
        }
    }

    fun hasLocationPermission() = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val onLocateClick = {
        focus.clearFocus()
        if (hasLocationPermission()) startLocate() else permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    val onStartNav = {
        if (hasLocationPermission()) {
            vm.startNavigation()
        } else {
            wantNav = true
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    val nav = s.nav

    // Під час навігації екран не гасне
    val view = LocalView.current
    DisposableEffect(nav.active) {
        view.keepScreenOn = nav.active
        onDispose { view.keepScreenOn = false }
    }

    // Безперервне відстеження положення, поки триває навігація
    LaunchedEffect(nav.active) {
        if (nav.active) {
            try {
                locationUpdates(context.applicationContext).collect { loc ->
                    vm.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing else null)
                }
            } catch (e: SecurityException) {
                vm.stopNavigation()
                vm.showError("Немає дозволу на геолокацію")
            }
        }
    }

    // Голосові підказки
    val tts = remember { Tts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    LaunchedEffect(nav.announcement?.id) {
        val announcement = nav.announcement
        if (announcement != null && nav.voice) tts.speak(announcement.text)
    }

    val bottomPad = if (panelTop > 0) (rootHeight - panelTop).coerceAtLeast(0) else 0
    val selected = s.selected
    val route = s.route

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { rootHeight = it.height }
    ) {
        MapPane(
            city = s.city,
            places = s.places,
            foods = if (s.showFood) s.foods else emptyList(),
            route = route,
            selected = selected,
            nav = nav,
            topPaddingPx = searchBottom + (8 * density).toInt(),
            bottomPaddingPx = bottomPad + (8 * density).toInt(),
            onPlaceClick = { id -> if (!nav.active) vm.selectPlace(id) },
            onUserPan = { if (nav.active) vm.setFollow(false) },
            modifier = Modifier.fillMaxSize()
        )

        // --- верх: пошук і підказки ---
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            if (nav.active) {
                NavBanner(
                    nav = nav,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { searchBottom = it.boundsInRoot().bottom.roundToInt() }
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { searchBottom = it.boundsInRoot().bottom.roundToInt() },
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 8.dp,
                    tonalElevation = 2.dp
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = s.query,
                                onValueChange = vm::onQuery,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Місто або село") },
                                leadingIcon = { Text("🔍", fontSize = 18.sp) },
                                trailingIcon = {
                                    if (s.query.isNotEmpty()) {
                                        Text(
                                            "✕",
                                            modifier = Modifier
                                                .clickable { vm.onQuery("") }
                                                .padding(12.dp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    focus.clearFocus()
                                    vm.search()
                                }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                            Text(
                                "📍",
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .clickable(enabled = !s.loading, onClick = onLocateClick)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        if (s.loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (!nav.active && s.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 8.dp,
                    tonalElevation = 2.dp
                ) {
                    Column {
                        s.suggestions.forEachIndexed { index, suggestion ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focus.clearFocus()
                                        vm.selectSuggestion(suggestion)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    suggestion.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                if (suggestion.subtitle.isNotEmpty()) {
                                    Text(
                                        suggestion.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- низ: панель ---
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { panelTop = it.boundsInRoot().top.roundToInt() },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 16.dp,
            tonalElevation = 2.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            RoundedCornerShape(2.dp)
                        )
                )
                Column(
                    Modifier
                        .heightIn(max = maxPanel)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when {
                        nav.active && route != null -> NavContent(nav, route, vm)
                        selected != null -> PlaceContent(
                            place = selected,
                            detail = s.detail,
                            hasRoute = route != null,
                            inRoute = route?.stops?.any { it.id == selected.id } == true,
                            loading = s.loading,
                            onAdd = { vm.addToRoute(selected) },
                            onClose = vm::clearSelection
                        )
                        route != null -> RouteContent(route, s.loading, vm, onStartNav)
                        s.places.isNotEmpty() -> PlanContent(s, vm)
                        s.error == null -> Text(
                            s.status ?: "Введіть місто або натисніть 📍, щоб знайти цікаві місця поруч",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberImageLoader(): ImageLoader {
    val context = LocalContext.current
    // Wikimedia вимагає осмислений User-Agent, тож беремо спільний клієнт
    return remember { ImageLoader.Builder(context).okHttpClient { Api.client }.build() }
}

private fun openUrl(context: Context, url: String) {
    val full = if (url.startsWith("http")) url else "https://$url"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full))) }
}

@Composable
private fun PlaceContent(
    place: Place,
    detail: PlaceDetail?,
    hasRoute: Boolean,
    inRoute: Boolean,
    loading: Boolean,
    onAdd: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = rememberImageLoader()

    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                place.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val subtitle = if (place.category == Category.FOOD) {
                place.category.label
            } else {
                "${place.category.label} · статей у Вікіпедії: ${place.score}"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            "✕",
            modifier = Modifier
                .clickable(onClick = onClose)
                .padding(start = 16.dp, top = 2.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    detail?.imageUrl?.let { url ->
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = place.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
        )
    }

    val text = detail?.extract ?: place.description
    if (!text.isNullOrBlank()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis
        )
    }
    place.hours?.let { Text("🕒 $it", style = MaterialTheme.typography.bodySmall) }

    val link = detail?.pageUrl ?: place.website
    val canAdd = hasRoute && !inRoute
    if (canAdd || link != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canAdd) {
                Button(
                    onClick = onAdd,
                    enabled = !loading,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Додати в маршрут") }
            }
            if (link != null) {
                OutlinedButton(
                    onClick = { openUrl(context, link) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (place.category == Category.FOOD) "Сайт" else "Вікіпедія") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PlanContent(s: UiState, vm: MainViewModel) {
    Text(
        "${s.city?.name.orEmpty()}: знайдено місць ${s.places.size}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text("Категорії (порожньо = усі)", style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Category.sights.forEach { category ->
            FilterChip(
                selected = category in s.categories,
                onClick = { vm.toggleCategory(category) },
                label = { Text(category.label) }
            )
        }
    }
    Text("Радіус пошуку", style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RADIUS_OPTIONS_KM.forEach { km ->
            FilterChip(
                selected = s.radiusKm == km,
                enabled = !s.loading,
                onClick = { vm.setRadius(km) },
                label = { Text("${if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()} км") }
            )
        }
    }
    SwitchRow(
        "Розширений пошук (і місця без статті у Вікіпедії)",
        s.extended, !s.loading
    ) { vm.setExtended(it) }
    SwitchRow(
        "Показати заклади харчування на карті",
        s.showFood, !s.loading
    ) { vm.toggleFood() }
    Text(
        "Тривалість прогулянки: ${formatMinutes(s.durationMin)}",
        style = MaterialTheme.typography.bodyMedium
    )
    Slider(
        value = s.durationMin.toFloat(),
        onValueChange = { vm.setDuration(it.roundToInt()) },
        valueRange = 60f..300f,
        steps = 7
    )
    Button(
        onClick = vm::buildRoute,
        enabled = !s.loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text("Побудувати маршрут", fontSize = 16.sp) }
}

@Composable
private fun RouteContent(
    route: Route,
    loading: Boolean,
    vm: MainViewModel,
    onStartNav: () -> Unit
) {
    val context = LocalContext.current
    val km = route.distanceMeters / 1000.0
    val walkMin = (route.durationSeconds / 60).roundToInt()
    Text(
        "Маршрут: ${"%.1f".format(km)} км · ~${formatMinutes(walkMin)} пішки · зупинок: ${route.stops.size}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )

    route.stops.forEachIndexed { index, place ->
        val isFood = place.category == Category.FOOD
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.selectPlace(place.id) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(
                        if (isFood) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (isFood) "🍴 " else "") + place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                place.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val arrowColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (loading) 0.3f else 0.8f)
            Text(
                "↑",
                color = arrowColor,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(enabled = !loading && index > 0) { vm.moveStop(index, -1) }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            )
            Text(
                "↓",
                color = arrowColor,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(enabled = !loading && index < route.stops.lastIndex) {
                        vm.moveStop(index, 1)
                    }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            )
            Text(
                "✕",
                color = MaterialTheme.colorScheme.error,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable(enabled = !loading) { vm.removeStop(place.id) }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = vm::optimizeOrder,
            enabled = !loading,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Авто-порядок") }
        OutlinedButton(
            onClick = vm::addLunch,
            enabled = !loading,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("🍴 Обід поруч") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = vm::clearRoute,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Змінити параметри") }
        Button(
            onClick = onStartNav,
            enabled = !loading,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("▶ Навігація") }
    }
    TextButton(
        onClick = { openUrl(context, googleMapsUrl(route)) },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Відкрити в Google Maps") }
}

private fun googleMapsUrl(route: Route): String {
    val first = route.stops.first()
    val last = route.stops.last()
    val waypoints = route.stops.drop(1).dropLast(1).joinToString("|") { "${it.lat},${it.lon}" }
    return Uri.Builder()
        .scheme("https")
        .authority("www.google.com")
        .appendPath("maps")
        .appendPath("dir")
        .appendPath("")
        .appendQueryParameter("api", "1")
        .appendQueryParameter("origin", "${first.lat},${first.lon}")
        .appendQueryParameter("destination", "${last.lat},${last.lon}")
        .apply { if (waypoints.isNotEmpty()) appendQueryParameter("waypoints", waypoints) }
        .appendQueryParameter("travelmode", "walking")
        .build()
        .toString()
}

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m хв"
        m == 0 -> "$h год"
        else -> "$h год $m хв"
    }
}

@Composable
private fun NavBanner(nav: NavState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                nav.finished -> "🎉"
                nav.user == null -> "📡"
                else -> nav.arrow
            }
            Text(icon, fontSize = 40.sp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(16.dp))
            Column {
                when {
                    nav.finished -> Text(
                        "Маршрут завершено",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    nav.user == null -> Column {
                        Text(
                            "Очікую сигнал GPS…",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            "Вийдіть на відкрите місце й перевірте, чи увімкнена геолокація",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                    else -> {
                        Text(
                            formatDistance(nav.distToManeuver),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            nav.instruction,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavContent(nav: NavState, route: Route, vm: MainViewModel) {
    if (nav.finished) {
        Text(
            "Ви пройшли весь маршрут. Дякуємо за прогулянку!",
            style = MaterialTheme.typography.titleMedium
        )
        Button(
            onClick = vm::stopNavigation,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Закрити") }
        return
    }

    val stop = route.stops.getOrNull(nav.nextStopIndex)
    if (stop != null) {
        Text(
            "Далі: ${nav.nextStopIndex + 1}. ${stop.name}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (nav.user != null) {
            Text(
                "До зупинки: ${formatDistance(nav.distToNextStop)} · лишилось всього " +
                    "${formatDistance(nav.remainingM)} (~${formatMinutes((nav.remainingSec / 60).roundToInt())})",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    if (nav.rerouting || nav.offRoute) {
        Text(
            "Ви відхилились від маршруту, перераховую шлях…",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    SwitchRow("Голосові підказки", nav.voice, true) { vm.setVoice(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = vm::skipStop,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Пропустити зупинку") }
        if (!nav.follow) {
            Button(
                onClick = { vm.setFollow(true) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Центрувати") }
        }
    }
    Button(
        onClick = vm::stopNavigation,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp)
    ) { Text("Завершити навігацію") }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} м"
    else "${"%.1f".format(meters / 1000.0)} км"
