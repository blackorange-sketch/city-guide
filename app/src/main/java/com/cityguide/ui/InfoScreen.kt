@file:OptIn(ExperimentalMaterial3Api::class)

package com.cityguide.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cityguide.InfoState
import com.cityguide.R
import com.cityguide.data.Category
import com.cityguide.data.FactKind

/** Екран докладної інформації про місце всередині застосунку: фото, факти, стаття Вікіпедії. */
@Composable
fun InfoScreen(
    info: InfoState,
    canAdd: Boolean,
    loading: Boolean,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    city: String = ""
) {
    val context = LocalContext.current
    val imageLoader = rememberImageLoader()
    val place = info.place
    val colors = MaterialTheme.colorScheme
    val phone = info.facts.firstOrNull { it.kind == FactKind.PHONE }?.value
    val link = info.detail?.pageUrl
    val site = place.website

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // --- шапка: фото (або однотонний фон), назва, кнопка закриття ---
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(colors.primaryContainer)
                ) {
                    val image = info.detail?.imageUrl
                    AnimatedVisibility(
                        visible = image != null,
                        enter = fadeIn(tween(400)),
                        exit = fadeOut()
                    ) {
                        if (image != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(image).crossfade(400).build(),
                                imageLoader = imageLoader,
                                contentDescription = place.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x55000000), Color.Transparent, Color(0xAA000000))
                                )
                            )
                    )
                    Box(
                        Modifier
                            .statusBarsPadding()
                            .padding(12.dp)
                    ) { CloseButton(onClose) }
                    Text(
                        place.name,
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // --- категорія, короткий опис, дії ---
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val categoryName = stringResource(place.category.labelRes)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(categoryColor(place.category), CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (place.category == Category.FOOD || place.category == Category.ENTERTAINMENT) categoryName
                            else stringResource(R.string.place_sub_wiki, categoryName, place.score),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary
                        )
                    }
                    place.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (canAdd) {
                            AppButton(
                                text = stringResource(R.string.add_to_route),
                                onClick = onAdd,
                                enabled = !loading,
                                modifier = Modifier.widthIn(min = 150.dp)
                            )
                        }
                        if (phone != null) {
                            AppButton(
                                text = "📞 " + stringResource(R.string.info_call),
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { c -> c.isDigit() || c == '+' }))
                                        )
                                    }
                                },
                                kind = ButtonKind.TONAL,
                                modifier = Modifier.widthIn(min = 130.dp)
                            )
                        }
                        if (ticketsRelevant(place)) {
                            TicketsButton(place, city, Modifier.widthIn(min = 130.dp), ButtonKind.FILLED)
                        }
                        if (site != null) {
                            AppButton(
                                text = stringResource(R.string.open_website),
                                onClick = { openUrl(context, site) },
                                kind = ButtonKind.TONAL,
                                modifier = Modifier.widthIn(min = 130.dp)
                            )
                        }
                        if (link != null) {
                            AppButton(
                                text = stringResource(R.string.open_wikipedia),
                                onClick = { openUrl(context, link) },
                                kind = ButtonKind.OUTLINED,
                                modifier = Modifier.widthIn(min = 130.dp)
                            )
                        }
                    }
                }
            }

            if (info.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            }

            // --- факти ---
            if (info.facts.isNotEmpty()) {
                item {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SectionLabel(stringResource(R.string.info_facts))
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = colors.surfaceContainerHigh
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                info.facts.forEach { fact ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            stringResource(fact.kind.labelRes),
                                            Modifier.width(124.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                        Text(
                                            fact.value,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- галерея ---
            if (info.gallery.isNotEmpty()) {
                item {
                    Column(
                        Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            SectionLabel(stringResource(R.string.info_gallery))
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(info.gallery, key = { it }) { url ->
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(url).crossfade(300).build(),
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 230.dp, height = 156.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(colors.surfaceContainerHigh)
                                )
                            }
                        }
                    }
                }
            }

            // --- стаття ---
            if (info.article.isNotEmpty()) {
                item {
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                        SectionLabel(stringResource(R.string.info_article))
                    }
                }
                items(info.article) { section ->
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        section.title?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            section.text,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.info_source_wiki),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (!info.loading && info.article.isEmpty() && info.facts.isEmpty() && info.gallery.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.info_no_data),
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}
