package com.labelrescue.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labelrescue.app.data.ConfirmedNumber
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.usps.TrackingFormat
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    items: List<ConfirmedNumber>,
    learned: HistoryStats,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Numbers you marked as confirmed. Their Mailer IDs and service codes are used to rank candidates on future labels. Stored only on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (learned.total > 0) {
                    val mids = learned.mailerIds.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} (${it.value})" }
                    val stcs = learned.serviceTypes.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} (${it.value})" }
                    Text("Learned Mailer IDs: $mids", style = MaterialTheme.typography.bodySmall)
                    Text("Learned service codes: $stcs", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onExport, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Export") }
                    FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Import") }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = items.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Clear") }
                }
                if (items.isEmpty()) Text("Nothing confirmed yet.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(items, key = { it.id }) { n ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(TrackingFormat.grouped(n.tracking), fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        Text("${DateFormat.getDateTimeInstance().format(Date(n.confirmedAt))} · ${n.source}", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onDelete(n.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("This deletes all confirmed numbers and what was learned from them. Export first if you want a copy.") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
