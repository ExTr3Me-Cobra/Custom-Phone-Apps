package com.labelrescue.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labelrescue.app.data.TrainingCrops

/**
 * Every saved training crop with the digit it will teach. Tap one that's wrong (shows a
 * different digit, or isn't a digit) to delete it before exporting.
 */
@Composable
fun CropReviewScreen(
    items: List<TrainingCrops.Item>,
    thumbnail: (TrainingCrops.Item) -> Bitmap?,
    onDelete: (TrainingCrops.Item) -> Unit,
    onDeleteUntrusted: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(88.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Each picture is saved with the digit under it. Tap any picture that doesn't show that digit to delete it. " +
                        "Green = from an intact label whose barcode proved the number (trusted).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${items.size} crops · ${items.count { it.trusted }} trusted · digits: " +
                        ('0'..'9').joinToString(" ") { d -> "$d×${items.count { it.digit == d }}" },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (items.any { !it.trusted }) {
                    OutlinedButton(onClick = onDeleteUntrusted, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Delete all untrusted crops")
                    }
                }
                if (items.isEmpty()) Text("No crops saved.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(items, key = { it.file.name }) { item ->
            val bmp = remember(item.file.name) { thumbnail(item) }
            Card(
                onClick = { onDelete(item) },
                colors = CardDefaults.cardColors(
                    containerColor = if (item.trusted) Color(0xFF1B5E20).copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), "Digit crop", Modifier.size(64.dp), filterQuality = FilterQuality.None)
                    }
                    Text(item.digit.toString(), fontFamily = FontFamily.Monospace, fontSize = 24.sp)
                    Text("by ${item.agreedBy.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
