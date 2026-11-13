@file:OptIn(ExperimentalMaterial3Api::class)

package com.cityguide.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cityguide.R
import com.cityguide.data.Category

/** Вигляд кнопки застосунку. */
enum class ButtonKind { FILLED, TONAL, OUTLINED, DANGER, TEXT }

/** Кнопка з пружною анімацією натискання. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.FILLED
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "button-press"
    )
    val m = modifier
        .height(52.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    val shape = RoundedCornerShape(18.dp)
    val label: @Composable () -> Unit = {
        Text(
            text,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
    when (kind) {
        ButtonKind.FILLED -> Button(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            interactionSource = source
        ) { label() }
        ButtonKind.TONAL -> FilledTonalButton(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            interactionSource = source
        ) { label() }
        ButtonKind.OUTLINED -> OutlinedButton(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            interactionSource = source
        ) { label() }
        ButtonKind.DANGER -> Button(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            interactionSource = source
        ) { label() }
        ButtonKind.TEXT -> TextButton(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            interactionSource = source
        ) { label() }
    }
}

/** Кругла кнопка-іконка (емодзі або символ) із пружним натисканням. */
@Composable
fun IconCircle(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Int = 40,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "icon-press"
    )
    Box(
        modifier
            .size(size.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Емодзі поточної теми: за системою, світла, темна. */
@Composable
fun ThemeIcon(mode: ThemeMode) {
    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            (scaleIn(tween(220)) + fadeIn(tween(220))) togetherWith (scaleOut(tween(150)) + fadeOut(tween(150)))
        },
        label = "theme-icon"
    ) { m ->
        Text(
            when (m) {
                ThemeMode.SYSTEM -> "🌓"
                ThemeMode.LIGHT -> "☀️"
                ThemeMode.DARK -> "🌙"
            },
            fontSize = 18.sp
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Група налаштувань на заокругленій плашці. */
@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) { content() }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Рядок прокручуваних фільтр-чипів. */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
fun AppChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    dot: Color? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        label = { Text(label, maxLines = 1) },
        leadingIcon = dot?.let { color ->
            {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

fun categoryColor(category: Category): Color =
    Color(android.graphics.Color.parseColor(category.color))

/** Невелика плашка зі статистикою (відстань, час, кількість зупинок). */
@Composable
fun StatPill(emoji: String, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(emoji, fontSize = 15.sp)
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
        }
    }
}

/** Поява елемента списку: плавне проявлення з розкриттям і затримкою за номером. */
@Composable
fun AppearItem(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(260, delayMillis = (index * 45).coerceAtMost(320))) +
            expandVertically(tween(260, delayMillis = (index * 45).coerceAtMost(320)))
    ) { content() }
}

/** Кольоровий кружечок із номером зупинки; колір анімується. */
@Composable
fun NumberBadge(number: Int, color: Color, size: Int = 28) {
    val animated by animateColorAsState(color, tween(300), label = "badge")
    Box(
        Modifier
            .size(size.dp)
            .background(animated, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            number.toString(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CloseButton(onClick: () -> Unit) {
    IconCircle(
        description = stringResource(R.string.close),
        onClick = onClick,
        size = 34,
        container = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text("✕", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))
