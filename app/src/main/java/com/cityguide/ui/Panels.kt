@file:OptIn(ExperimentalMaterial3Api::class)

package com.cityguide.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cityguide.MainViewModel
import com.cityguide.NavState
import com.cityguide.R
import com.cityguide.UiState
import com.cityguide.data.Api
import com.cityguide.data.Category
import com.cityguide.data.LatLon
import com.cityguide.data.Place
import com.cityguide.data.PlaceDetail
import com.cityguide.data.Route
import kotlin.math.roundToInt

private val RADIUS_OPTIONS_KM = listOf(1.0, 2.5, 5.0, 10.0)
private val FOOD_DISTANCE_OPTIONS_M = listOf(300, 600, 1000, 1500)

// --- форматування ---

@Composable
fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> stringResource(R.string.dur_min, m)
        m == 0 -> stringResource(R.string.dur_h, h)
        else -> stringResource(R.string.dur_h_min, h, m)
    }
}

@Composable
fun formatDistance(meters: Double): String =
    if (meters < 1000) {
        stringResource(R.string.dist_m, ((meters / 10).roundToInt() * 10).coerceAtLeast(0))
    } else {
        stringResource(R.string.dist_km, "%.1f".format(meters / 1000.0))
    }

@Composable
private fun kmLabel(km: Double): String =
    stringResource(R.string.dist_km, if (km % 1.0 == 0.0) km.toInt().toString() else km.toString())

internal fun openUrl(context: Context, url: String) {
    val full = if (url.startsWith("http")) url else "https://$url"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full))) }
}

@Composable
internal fun rememberImageLoader(): ImageLoader {
    val context = LocalContext.current
    // Wikimedia вимагає осмислений User-Agent, тож беремо спільний клієнт
    return remember { ImageLoader.Builder(context).okHttpClient { Api.client }.build() }
}

// --- стартова підказка ---

@Composable
fun HintContent(status: String?) {
    val transition = rememberInfiniteTransition(label = "hint")
    val bob by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "bob"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "🧭",
                fontSize = 32.sp,
                modifier = Modifier.graphicsLayer {
                    translationY = bob
                    rotationZ = bob * 1.5f
                }
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.hint_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            AnimatedContent(targetState = status ?: stringResource(R.string.hint_text), label = "hint-text") { text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PickStartContent(onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("🗺", fontSize = 24.sp) }
        Text(
            stringResource(R.string.pick_start_prompt),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    AppButton(
        text = stringResource(R.string.cancel),
        onClick = onCancel,
        kind = ButtonKind.OUTLINED,
        modifier = Modifier.fillMaxWidth()
    )
}

// --- вибране місце ---

@Composable
fun PlaceContent(
    place: Place,
    detail: PlaceDetail?,
    hasRoute: Boolean,
    inRoute: Boolean,
    loading: Boolean,
    onAdd: () -> Unit,
    onMore: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = rememberImageLoader()
    var expanded by remember(place.id) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                place.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            val categoryName = stringResource(place.category.labelRes)
            val subtitle = if (place.category == Category.FOOD) {
                categoryName
            } else {
                stringResource(R.string.place_sub_wiki, categoryName, place.score)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(categoryColor(place.category), CircleShape)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        CloseButton(onClose)
    }

    AnimatedVisibility(
        visible = detail?.imageUrl != null,
        enter = fadeIn(tween(300)) + expandVertically(tween(350)),
        exit = fadeOut() + shrinkVertically()
    ) {
        val url = detail?.imageUrl
        if (url != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(22.dp))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).crossfade(400).build(),
                    imageLoader = imageLoader,
                    contentDescription = place.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(176.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0x33000000))
                            )
                        )
                )
            }
        }
    }

    val text = detail?.extract ?: place.description
    if (!text.isNullOrBlank()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) 40 else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                .clickable { expanded = !expanded }
        )
    }
    place.hours?.let {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                "🕒 $it",
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    val canAdd = hasRoute && !inRoute
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canAdd) {
            AppButton(
                text = stringResource(R.string.add_to_route),
                onClick = onAdd,
                enabled = !loading,
                modifier = Modifier.weight(1f)
            )
        }
        AppButton(
            text = stringResource(R.string.info_more),
            onClick = onMore,
            kind = if (canAdd) ButtonKind.OUTLINED else ButtonKind.FILLED,
            modifier = Modifier.weight(1f)
        )
    }
}

// --- налаштування маршруту ---

@Composable
fun FoodDistanceChips(value: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    SectionLabel(stringResource(R.string.food_distance_label))
    ChipRow {
        FOOD_DISTANCE_OPTIONS_M.forEach { m ->
            AppChip(
                selected = value == m,
                enabled = enabled,
                onClick = { onSelect(m) },
                label = if (m >= 1000) kmLabel(m / 1000.0) else stringResource(R.string.dist_m, m)
            )
        }
    }
}

@Composable
fun PlanContent(s: UiState, vm: MainViewModel, onStartHere: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.plan_found, s.city?.name.orEmpty(), s.places.size),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }

    SectionLabel(stringResource(R.string.plan_categories))
    ChipRow {
        Category.sights.forEach { category ->
            AppChip(
                selected = category in s.categories,
                onClick = { vm.toggleCategory(category) },
                label = stringResource(category.labelRes),
                dot = categoryColor(category)
            )
        }
    }

    SectionLabel(stringResource(R.string.plan_radius))
    ChipRow {
        RADIUS_OPTIONS_KM.forEach { km ->
            AppChip(
                selected = s.radiusKm == km,
                enabled = !s.loading,
                onClick = { vm.setRadius(km) },
                label = kmLabel(km)
            )
        }
    }

    SettingsCard {
        SwitchRow(stringResource(R.string.plan_extended), s.extended, !s.loading) { vm.setExtended(it) }
        SwitchRow(stringResource(R.string.plan_show_food), s.showFood, !s.loading) { vm.toggleFood() }
    }

    SectionLabel(stringResource(R.string.plan_start))
    ChipRow {
        AppChip(
            selected = s.start == null,
            enabled = !s.loading,
            onClick = vm::setStartAuto,
            label = stringResource(R.string.start_auto)
        )
        AppChip(
            selected = false,
            enabled = !s.loading,
            onClick = onStartHere,
            label = stringResource(R.string.start_here)
        )
        AppChip(
            selected = false,
            enabled = !s.loading,
            onClick = { vm.pickStartOnMap(true) },
            label = stringResource(R.string.start_map)
        )
    }
    AnimatedVisibility(
        visible = s.start != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Text(
            "🟢 " + stringResource(R.string.start_set),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    FoodDistanceChips(s.foodMaxM, !s.loading, vm::setFoodMax)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.plan_duration, formatMinutes(s.durationMin)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
    Slider(
        value = s.durationMin.toFloat(),
        onValueChange = { vm.setDuration(it.roundToInt()) },
        valueRange = 60f..300f,
        steps = 7
    )
    AppButton(
        text = stringResource(R.string.build_route),
        onClick = vm::buildRoute,
        enabled = !s.loading,
        modifier = Modifier.fillMaxWidth()
    )
}

// --- маршрут ---

@Composable
private fun StopRow(
    index: Int,
    place: Place,
    last: Boolean,
    loading: Boolean,
    onOpen: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    val isFood = place.category == Category.FOOD
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NumberBadge(
            number = index + 1,
            color = if (isFood) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (isFood) "🍴 " else "") + place.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            place.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        IconCircle(
            description = "↑", onClick = onUp, enabled = !loading && index > 0, size = 34,
            container = Color.Transparent
        ) { Text("↑", fontSize = 18.sp, color = tint.copy(alpha = if (index > 0) 1f else 0.3f)) }
        IconCircle(
            description = "↓", onClick = onDown, enabled = !loading && !last, size = 34,
            container = Color.Transparent
        ) { Text("↓", fontSize = 18.sp, color = tint.copy(alpha = if (!last) 1f else 0.3f)) }
        IconCircle(
            description = stringResource(R.string.close), onClick = onRemove, enabled = !loading, size = 34,
            container = Color.Transparent
        ) { Text("✕", fontSize = 15.sp, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun RouteContent(
    route: Route,
    start: LatLon?,
    foodMaxM: Int,
    loading: Boolean,
    vm: MainViewModel,
    onStartNav: () -> Unit
) {
    val context = LocalContext.current
    val km = route.distanceMeters / 1000.0
    val walkMin = (route.durationSeconds / 60).roundToInt()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill("📏", stringResource(R.string.dist_km, "%.1f".format(km)))
        StatPill("🚶", formatMinutes(walkMin))
        StatPill("📍", route.stops.size.toString())
    }

    Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        route.stops.forEachIndexed { index, place ->
            key(place.id) {
                AppearItem(index) {
                    StopRow(
                        index = index,
                        place = place,
                        last = index == route.stops.lastIndex,
                        loading = loading,
                        onOpen = { vm.selectPlace(place.id) },
                        onUp = { vm.moveStop(index, -1) },
                        onDown = { vm.moveStop(index, 1) },
                        onRemove = { vm.removeStop(place.id) }
                    )
                }
            }
        }
    }

    FoodDistanceChips(foodMaxM, !loading, vm::setFoodMax)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppButton(
            text = stringResource(R.string.auto_order),
            onClick = vm::optimizeOrder,
            enabled = !loading,
            kind = ButtonKind.TONAL,
            modifier = Modifier.weight(1f)
        )
        AppButton(
            text = stringResource(R.string.lunch_nearby),
            onClick = vm::addLunch,
            enabled = !loading,
            kind = ButtonKind.TONAL,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppButton(
            text = stringResource(R.string.change_params),
            onClick = vm::clearRoute,
            kind = ButtonKind.OUTLINED,
            modifier = Modifier.weight(1f)
        )
        AppButton(
            text = stringResource(R.string.start_nav),
            onClick = onStartNav,
            enabled = !loading,
            modifier = Modifier.weight(1f)
        )
    }
    AppButton(
        text = stringResource(R.string.open_gmaps),
        onClick = { openUrl(context, googleMapsUrl(route, start)) },
        kind = ButtonKind.TEXT,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun googleMapsUrl(route: Route, start: LatLon?): String {
    val first = route.stops.first()
    val last = route.stops.last()
    val origin = if (start != null) "${start.lat},${start.lon}" else "${first.lat},${first.lon}"
    val middle = if (start != null) route.stops.dropLast(1) else route.stops.drop(1).dropLast(1)
    val waypoints = middle.joinToString("|") { "${it.lat},${it.lon}" }
    return Uri.Builder()
        .scheme("https")
        .authority("www.google.com")
        .appendPath("maps")
        .appendPath("dir")
        .appendPath("")
        .appendQueryParameter("api", "1")
        .appendQueryParameter("origin", origin)
        .appendQueryParameter("destination", "${last.lat},${last.lon}")
        .apply { if (waypoints.isNotEmpty()) appendQueryParameter("waypoints", waypoints) }
        .appendQueryParameter("travelmode", "walking")
        .build()
        .toString()
}

// --- навігація ---

@Composable
fun NavBanner(nav: NavState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = colors.primary,
        contentColor = colors.onPrimary,
        shadowElevation = 14.dp
    ) {
        Box(
            Modifier.background(
                Brush.horizontalGradient(listOf(colors.primary, colors.tertiary))
            )
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
                Box(
                    Modifier
                        .size(60.dp)
                        .background(colors.onPrimary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = icon,
                        transitionSpec = {
                            (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(150))) togetherWith
                                (scaleOut(tween(120)) + fadeOut(tween(120)))
                        },
                        label = "nav-arrow"
                    ) { value ->
                        Text(value, fontSize = 34.sp, color = colors.onPrimary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        nav.finished -> Text(
                            stringResource(R.string.nav_done_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        nav.user == null -> {
                            Text(
                                stringResource(R.string.nav_waiting_gps),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.nav_waiting_gps_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onPrimary.copy(alpha = 0.85f)
                            )
                        }
                        else -> {
                            Text(
                                formatDistance(nav.distToManeuver),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            AnimatedContent(
                                targetState = nav.instruction,
                                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                                label = "nav-instruction"
                            ) { text ->
                                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NavContent(nav: NavState, route: Route, vm: MainViewModel) {
    if (nav.finished) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("🎉", fontSize = 36.sp)
            Text(
                stringResource(R.string.nav_done_text),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
        }
        AppButton(
            text = stringResource(R.string.close),
            onClick = vm::stopNavigation,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }

    val stop = route.stops.getOrNull(nav.nextStopIndex)
    if (stop != null) {
        Text(
            stringResource(R.string.nav_next, nav.nextStopIndex + 1, stop.name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (nav.user != null) {
            val progress by animateFloatAsState(
                targetValue = (1.0 - nav.remainingM / route.distanceMeters.coerceAtLeast(1.0))
                    .coerceIn(0.0, 1.0).toFloat(),
                animationSpec = tween(600),
                label = "nav-progress"
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Text(
                stringResource(
                    R.string.nav_to_stop,
                    formatDistance(nav.distToNextStop),
                    formatDistance(nav.remainingM),
                    formatMinutes((nav.remainingSec / 60).roundToInt())
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    AnimatedVisibility(
        visible = nav.rerouting || nav.offRoute,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Text(
            stringResource(R.string.nav_off_route),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    SettingsCard {
        SwitchRow(stringResource(R.string.nav_voice), nav.voice, true) { vm.setVoice(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppButton(
            text = stringResource(R.string.nav_skip),
            onClick = vm::skipStop,
            kind = ButtonKind.OUTLINED,
            modifier = Modifier.weight(1f)
        )
        AnimatedVisibility(
            visible = !nav.follow,
            modifier = if (!nav.follow) Modifier.weight(1f) else Modifier,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            AppButton(
                text = stringResource(R.string.nav_center),
                onClick = { vm.setFollow(true) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    AppButton(
        text = stringResource(R.string.nav_end),
        onClick = vm::stopNavigation,
        kind = ButtonKind.DANGER,
        modifier = Modifier.fillMaxWidth()
    )
}
