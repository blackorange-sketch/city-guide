package com.cityguide.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cityguide.MainViewModel
import com.cityguide.data.Category
import com.cityguide.data.Route
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Місто, наприклад Львів") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() })
                )
                Button(onClick = vm::search, enabled = !s.loading) { Text("Знайти") }
            }

            if (s.places.isNotEmpty()) {
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
                Text("Тривалість: ${formatMinutes(s.durationMin)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.durationMin.toFloat(),
                    onValueChange = { vm.setDuration(it.roundToInt()) },
                    valueRange = 60f..300f,
                    steps = 7
                )
                Button(
                    onClick = vm::buildRoute,
                    enabled = !s.loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Побудувати маршрут") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapPane(
                city = s.city,
                places = s.places,
                route = s.route,
                modifier = Modifier.fillMaxSize()
            )
            if (s.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        BottomPanel(
            status = s.status,
            error = s.error,
            route = s.route
        )
    }
}

@Composable
private fun BottomPanel(status: String?, error: String?, route: Route?) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
        } else if (route == null) {
            Text(status ?: "Введіть місто, щоб побачити визначні місця")
        }

        if (route != null) {
            val km = route.distanceMeters / 1000.0
            val walkMin = (route.durationSeconds / 60).roundToInt()
            Text(
                "Маршрут: ${"%.1f".format(km)} км, ~$walkMin хв пішки, зупинок: ${route.stops.size}",
                style = MaterialTheme.typography.titleSmall
            )
            LazyColumn(Modifier.heightIn(max = 160.dp)) {
                itemsIndexed(route.stops) { index, place ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text("${index + 1}. ${place.name}", style = MaterialTheme.typography.bodyLarge)
                        place.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUrl(route)))) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Відкрити в навігації") }
        }
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
