package com.cityguide.ui

import android.Manifest
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cityguide.MainViewModel
import com.cityguide.UiState
import com.cityguide.currentLocation
import com.cityguide.data.Category
import com.cityguide.data.Place
import com.cityguide.data.Route
import kotlin.math.roundToInt

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val density = context.resources.displayMetrics.density

    var rootHeight by remember { mutableIntStateOf(0) }
    var searchBottom by remember { mutableIntStateOf(0) }
    var panelTop by remember { mutableIntStateOf(0) }

    fun startLocate() {
        val appContext = context.applicationContext
        vm.locate { currentLocation(appContext)?.let { it.latitude to it.longitude } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) startLocate() else vm.showError("Немає дозволу на геолокацію")
    }

    val onLocateClick = {
        focus.clearFocus()
        val granted = LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) startLocate() else permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    val bottomPad = if (panelTop > 0) (rootHeight - panelTop).coerceAtLeast(0) else 0

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { rootHeight = it.height }
    ) {
        MapPane(
            city = s.city,
            places = s.places,
            route = s.route,
            selected = s.selected,
            topPaddingPx = searchBottom + (8 * density).toInt(),
            bottomPaddingPx = bottomPad + (8 * density).toInt(),
            onPlaceClick = vm::selectPlace,
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

            if (s.suggestions.isNotEmpty()) {
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

        // --- низ: вибране місце та панель ---
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { panelTop = it.boundsInRoot().top.roundToInt() }
        ) {
            s.selected?.let { PlaceCard(it, onClose = vm::clearSelection) }

            Surface(
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
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when {
                        s.route != null -> RouteContent(s.route!!, vm)
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
private fun PlaceCard(place: Place, onClose: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${place.category.label} · статей у Вікіпедії: ${place.score}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                place.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "✕",
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun PlanContent(s: UiState, vm: MainViewModel) {
    Text(
        "${s.city?.name.orEmpty()}: знайдено місць ${s.places.size}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Category.entries.forEach { category ->
            FilterChip(
                selected = category in s.categories,
                onClick = { vm.toggleCategory(category) },
                label = { Text(category.label) }
            )
        }
    }
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
private fun RouteContent(route: Route, vm: MainViewModel) {
    val context = LocalContext.current
    val km = route.distanceMeters / 1000.0
    val walkMin = (route.durationSeconds / 60).roundToInt()
    Text(
        "Маршрут: ${"%.1f".format(km)} км · ~${formatMinutes(walkMin)} пішки · зупинок: ${route.stops.size}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    LazyColumn(Modifier.heightIn(max = 190.dp)) {
        itemsIndexed(route.stops) { index, place ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectPlace(place.id) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
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
                Column {
                    Text(place.name, style = MaterialTheme.typography.bodyLarge)
                    place.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = vm::clearRoute,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Змінити") }
        Button(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUrl(route))))
            },
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Навігація") }
    }
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
