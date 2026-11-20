package com.labelrescue.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labelrescue.app.ResultState
import com.labelrescue.core.fusion.Progress
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.fusion.Source

@Composable
fun ProcessingScreen(progress: Progress?, status: String?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Running every decoder on every frame and image variant. Accuracy first, so this can take a while.", style = MaterialTheme.typography.bodyLarge)
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator({ (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(progress?.phase ?: status ?: "Working…", style = MaterialTheme.typography.titleMedium)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cancel") }
    }
}

@Composable
fun PhotoResultsScreen(
    state: ResultState?,
    noResult: RecoveryOutput?,
    actions: ResultActions,
    onManual: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == null) {
            Text("No tracking number found.", style = MaterialTheme.typography.titleLarge)
            noResult?.notes?.forEach { Text(it) }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Crop and try again") }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Type the digits I can read") }
            return@Column
        }
        state.photo?.output?.let { EvidenceSummary(it) }
        ResultsPanel(state, actions, crops = state.photo?.crops.orEmpty())
    }
}

@Composable
private fun EvidenceSummary(out: RecoveryOutput) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val full = out.fullDecode
            if (full != null) {
                Text("Read directly from the ${if (out.fullDecodeBy?.contains("data matrix") == true) "2D code" else "barcode"}", style = MaterialTheme.typography.titleMedium)
                Text("Check digit valid · ${out.fullDecodeBy}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Recovered from: " + out.perSource.keys.joinToString(" + ") { it.label }, style = MaterialTheme.typography.titleMedium)
                val rec = out.recovered.filter { it.rawConfidence < 0.9 }
                if (rec.isNotEmpty()) {
                    Text(
                        "Barcode checksum filled in digit " + rec.joinToString(", ") { "${it.position + 1} = ${it.digit} (${formatPct(it.confidence * 100)})" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (Source.BARCODE_PARTIAL !in out.perSource) Text("No usable barcode signal; using the printed digits.", style = MaterialTheme.typography.bodySmall)
            }
            out.zip?.let { Text("Destination ZIP from barcode: $it", style = MaterialTheme.typography.bodySmall) }
            val hints = out.serviceHints
            if (hints.isNotEmpty()) Text("Label says: " + hints.joinToString { it.service }, style = MaterialTheme.typography.bodySmall)
            val s = out.stats
            Text(
                "${s.frames} frames · ${s.variants} image variants · ${s.ocrReads} OCR reads · ${s.barcodeMeasurements} barcode scans · ${s.millis / 1000.0}s",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
