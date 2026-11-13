package com.cityguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cityguide.PhotoState
import com.cityguide.R

/** Вибір джерела фото: камера або галерея. */
@Composable
fun PhotoSourceDialog(onCamera: () -> Unit, onGallery: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_search)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.photo_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onCamera) { Text("📷 " + stringResource(R.string.photo_camera)) }
                TextButton(onClick = onGallery) { Text("🖼 " + stringResource(R.string.photo_gallery)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

/** Результат пошуку за фото: ім'я об'єкта, опис і перехід до докладної інформації. */
@Composable
fun PhotoResultDialog(photo: PhotoState, onDetails: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val result = photo.result
    AlertDialog(
        onDismissRequest = { if (!photo.loading) onClose() },
        title = {
            Text(
                stringResource(if (photo.loading) R.string.photo_identifying else R.string.photo_result_title)
            )
        },
        text = {
            when {
                photo.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.photo_identifying))
                }
                photo.error != null -> Text(photo.error)
                result != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        photo.hit?.title ?: result.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    result.place?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    (result.description ?: photo.hit?.description)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (result.confidence != "high") {
                        Text(
                            stringResource(R.string.photo_maybe),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {}
            }
        },
        confirmButton = {
            when {
                photo.loading -> {}
                photo.hit != null -> TextButton(onClick = onDetails) { Text(stringResource(R.string.photo_more)) }
                result?.name != null -> TextButton(onClick = {
                    openUrl(
                        context,
                        "https://www.google.com/search?q=" + android.net.Uri.encode(
                            listOfNotNull(result.name, result.place).joinToString(" ")
                        )
                    )
                }) { Text("🔎 " + stringResource(R.string.tickets_search)) }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
    )
}
