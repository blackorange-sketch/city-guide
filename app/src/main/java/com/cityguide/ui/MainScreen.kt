@file:OptIn(ExperimentalMaterial3Api::class)

package com.cityguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cityguide.InfoState
import com.cityguide.MainViewModel
import com.cityguide.R
import com.cityguide.Tts
import com.cityguide.UiState
import com.cityguide.currentLocation
import com.cityguide.data.Place
import com.cityguide.data.Route
import com.cityguide.locationUpdates
import kotlin.math.roundToInt

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

/** Який вміст показує нижня панель (разом з id місця, щоб перехід між місцями теж анімувався). */
private enum class PanelKind { NAV, PICK, PLACE, ROUTE, PLAN, HINT }

private data class PanelKey(val kind: PanelKind, val id: String? = null)

@Composable
fun MainScreen(
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    vm: MainViewModel = viewModel()
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val density = context.resources.displayMetrics.density
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    var rootHeight by remember { mutableIntStateOf(0) }
    var searchBottom by remember { mutableIntStateOf(0) }
    var panelTop by remember { mutableIntStateOf(0) }

    fun startLocate() {
        val appContext = context.applicationContext
        vm.locate { currentLocation(appContext)?.let { it.latitude to it.longitude } }
    }

    var wantNav by remember { mutableStateOf(false) }
    var wantStart by remember { mutableStateOf(false) }

    fun startFromMyLocation() {
        val appContext = context.applicationContext
        vm.useMyLocationAsStart { currentLocation(appContext)?.let { it.latitude to it.longitude } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        when {
            !result.values.any { it } -> {
                wantNav = false
                wantStart = false
                vm.showError(context.getString(R.string.err_no_permission))
            }
            wantNav -> {
                wantNav = false
                vm.startNavigation()
            }
            wantStart -> {
                wantStart = false
                startFromMyLocation()
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

    val onStartHere = {
        if (hasLocationPermission()) {
            startFromMyLocation()
        } else {
            wantStart = true
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
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
                vm.showError(context.getString(R.string.err_no_permission))
            }
        }
    }

    // Голосові підказки мовою інтерфейсу
    val tts = remember { Tts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    LaunchedEffect(nav.announcement?.id) {
        val announcement = nav.announcement
        if (announcement != null && nav.voice) tts.speak(announcement.text)
    }

    val bottomPad = if (panelTop > 0) (rootHeight - panelTop).coerceAtLeast(0) else 0
    val selected = s.selected
    val route = s.route

    // Остання відома картка й маршрут: потрібні, щоб панель плавно зникала, коли стан уже очищено
    val placeKeep = remember { arrayOfNulls<Place>(1) }
    selected?.let { placeKeep[0] = it }
    val routeKeep = remember { arrayOfNulls<Route>(1) }
    route?.let { routeKeep[0] = it }
    val infoKeep = remember { arrayOfNulls<InfoState>(1) }
    s.info?.let { infoKeep[0] = it }

    // «Назад» закриває екран докладної інформації
    BackHandler(enabled = s.info != null) { vm.closeInfo() }

    val panelKey = when {
        nav.active && route != null -> PanelKey(PanelKind.NAV)
        s.pickingStart -> PanelKey(PanelKind.PICK)
        selected != null -> PanelKey(PanelKind.PLACE, selected.id)
        route != null -> PanelKey(PanelKind.ROUTE)
        s.places.isNotEmpty() -> PanelKey(PanelKind.PLAN)
        else -> PanelKey(PanelKind.HINT)
    }

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
            start = s.start,
            pickingStart = s.pickingStart && !nav.active,
            startLabel = stringResource(R.string.start_marker),
            topPaddingPx = searchBottom + (8 * density).toInt(),
            bottomPaddingPx = bottomPad + (8 * density).toInt(),
            onPlaceClick = { id -> if (!nav.active) vm.selectPlace(id) },
            onMapPick = { lat, lon -> vm.setStart(lat, lon) },
            onUserPan = { if (nav.active) vm.setFollow(false) },
            modifier = Modifier.fillMaxSize()
        )

        // --- верх: пошук або банер навігації, підказки міст ---
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Box(Modifier.onGloballyPositioned { searchBottom = it.boundsInRoot().bottom.roundToInt() }) {
                AnimatedContent(
                    targetState = nav.active,
                    transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically(tween(300)) { -it / 2 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(250)) { -it / 2 }) using
                            SizeTransform(clip = false) { _, _ ->
                                spring<IntSize>(stiffness = Spring.StiffnessMediumLow)
                            }
                    },
                    label = "top-bar"
                ) { navActive ->
                    if (navActive) {
                        NavBanner(nav, Modifier.fillMaxWidth())
                    } else {
                        SearchPill(
                            s = s,
                            vm = vm,
                            focus = focus,
                            themeMode = themeMode,
                            onLocate = onLocateClick,
                            onCycleTheme = onCycleTheme
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !nav.active && s.suggestions.isNotEmpty(),
                enter = fadeIn(tween(200)) + expandVertically(tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 12.dp
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
                                        .padding(horizontal = 18.dp, vertical = 11.dp)
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 24.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                            RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.height(12.dp))

                // Помилка з'являється над вмістом і закривається крапкою
                AnimatedVisibility(
                    visible = s.error != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.dismissError() }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    s.error.orEmpty(),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("✕", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                // Індикатор довгого завантаження, коли місця вже є (оновлення радіуса тощо)
                AnimatedVisibility(
                    visible = s.loading && s.status != null && s.places.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        Modifier.padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            s.status.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AnimatedContent(
                    targetState = panelKey,
                    transitionSpec = {
                        (fadeIn(tween(240, delayMillis = 70)) +
                            slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 10 }) togetherWith
                            fadeOut(tween(120)) using
                            SizeTransform(clip = false) { _, _ ->
                                spring<IntSize>(stiffness = Spring.StiffnessMediumLow)
                            }
                    },
                    label = "panel"
                ) { key ->
                    Column(
                        Modifier
                            .heightIn(max = maxPanel)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (key.kind) {
                            PanelKind.NAV -> (route ?: routeKeep[0])?.let { NavContent(nav, it, vm) }
                            PanelKind.PICK -> PickStartContent { vm.pickStartOnMap(false) }
                            PanelKind.PLACE -> (selected ?: placeKeep[0])?.let { place ->
                                val current = route ?: routeKeep[0]
                                PlaceContent(
                                    place = place,
                                    detail = s.detail,
                                    hasRoute = route != null,
                                    inRoute = current?.stops?.any { it.id == place.id } == true,
                                    loading = s.loading,
                                    onAdd = { vm.addToRoute(place) },
                                    onMore = { vm.openInfo(place) },
                                    onClose = vm::clearSelection
                                )
                            }
                            PanelKind.ROUTE -> (route ?: routeKeep[0])?.let {
                                RouteContent(it, s.start, s.foodMaxM, s.loading, vm, onStartNav)
                            }
                            PanelKind.PLAN -> PlanContent(s, vm, onStartHere)
                            PanelKind.HINT -> HintContent(s.status)
                        }
                    }
                }
            }
        }

        // --- екран докладної інформації поверх усього ---
        AnimatedVisibility(
            visible = s.info != null,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(200))
        ) {
            val info = s.info ?: infoKeep[0]
            if (info != null) {
                val current = route ?: routeKeep[0]
                InfoScreen(
                    info = info,
                    canAdd = route != null && current?.stops?.any { it.id == info.place.id } != true,
                    loading = s.loading,
                    onAdd = { vm.addToRoute(info.place) },
                    onClose = vm::closeInfo
                )
            }
        }
    }
}

/** Плаваюча пошукова капсула: поле, геолокація, перемикач теми, індикатор завантаження. */
@Composable
private fun SearchPill(
    s: UiState,
    vm: MainViewModel,
    focus: FocusManager,
    themeMode: ThemeMode,
    onLocate: () -> Unit,
    onCycleTheme: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 14.dp
    ) {
        Column {
            Row(
                Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = s.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = { Text("🔍", fontSize = 18.sp) },
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = s.query.isNotEmpty(),
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(150))
                        ) {
                            Text(
                                "✕",
                                modifier = Modifier
                                    .clickable { vm.onQuery("") }
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                IconCircle(
                    description = stringResource(R.string.my_location_name),
                    onClick = onLocate,
                    enabled = !s.loading
                ) { Text("📍", fontSize = 18.sp) }
                Spacer(Modifier.width(6.dp))
                IconCircle(
                    description = stringResource(
                        when (themeMode) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        }
                    ),
                    onClick = onCycleTheme,
                    container = MaterialTheme.colorScheme.surfaceContainerHigh
                ) { ThemeIcon(themeMode) }
            }
            AnimatedVisibility(
                visible = s.loading,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
