package com.cityguide.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cityguide.R
import com.cityguide.data.Category
import com.cityguide.data.Place

/** Чи має сенс показувати кнопку «Квитки» для місця (музеї, розваги, платні об'єкти). */
fun ticketsRelevant(place: Place): Boolean = when (place.category) {
    Category.FOOD, Category.PARK -> false
    Category.RELIGION -> place.tags["fee"] == "yes"
    else -> true
}

private fun encoded(text: String): String = Uri.encode(text)

/**
 * Кнопка «Квитки»: відкриває вибір — офіційний сайт місця, пошук екскурсій і квитків
 * (GetYourGuide) або пошук в інтернеті. Платежі застосунок не приймає, усе відкривається
 * у вбудованому браузері (Custom Tabs).
 */
@Composable
fun TicketsButton(
    place: Place,
    city: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.TONAL
) {
    var open by remember(place.id) { mutableStateOf(false) }
    val context = LocalContext.current
    AppButton(
        text = "🎟 " + stringResource(R.string.tickets),
        onClick = { open = true },
        kind = kind,
        modifier = modifier
    )
    if (open) {
        val query = listOf(place.name, city).filter { it.isNotBlank() }.joinToString(" ")
        val ticketWord = stringResource(R.string.tickets_query)
        val site = place.website
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.tickets_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        place.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (site != null) {
                        TextButton(onClick = { open = false; openUrl(context, site) }) {
                            Text("🌐 " + stringResource(R.string.tickets_official))
                        }
                    }
                    TextButton(onClick = {
                        open = false
                        openUrl(context, "https://www.getyourguide.com/s/?q=" + encoded(query))
                    }) { Text("🎫 " + stringResource(R.string.tickets_gyg)) }
                    TextButton(onClick = {
                        open = false
                        openUrl(context, "https://www.google.com/search?q=" + encoded("$query $ticketWord"))
                    }) { Text("🔎 " + stringResource(R.string.tickets_search)) }
                    Text(
                        stringResource(R.string.tickets_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}
