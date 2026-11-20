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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    diagnosticLog: (Boolean) -> String? = { null },
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == null) {
            Text("No tracking number found.", style = MaterialTheme.typography.titleLarge)
            noResult?.notes?.forEach { Text(it) }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Crop and try again") }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Type the digits I can read") }
            DiagnosticsCard(diagnosticLog)
            return@Column
        }
        state.photo?.output?.let { EvidenceSummary(it) }
        ResultsPanel(state, actions, crops = state.photo?.crops.orEmpty())
        DiagnosticsCard(diagnosticLog)
    }
}

/** Clipboard and share intents have size limits (the Binder buffer is about 1 MB). */
private const val LOG_LIMIT = 400_000

/** Copy or share the detailed log (for development). Names and addresses hidden by default. */
@Composable
private fun DiagnosticsCard(diagnosticLog: (Boolean) -> String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var redact by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostic log", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything the app read and decided for this photo: OCR reads with per-character confidence, barcode measurements and layouts, the per-digit evidence and the ranked candidates. For troubleshooting; nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(redact, { redact = it })
                Text("Hide names and addresses", style = MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    diagnosticLog(redact)?.let {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Label Rescue diagnostic log", it.take(LOG_LIMIT)))
                        android.widget.Toast.makeText(ctx, "Diagnostic log copied (${it.length / 1024} KB)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Copy log") }
                OutlinedButton(onClick = { diagnosticLog(redact)?.let { share(ctx, it.take(LOG_LIMIT)) } }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Share log") }
            }
        }
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
