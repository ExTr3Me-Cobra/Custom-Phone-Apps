package com.labelrescue.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.labelrescue.app.AppViewModel

@Composable
fun SettingsScreen(
    ui: AppViewModel.SettingsUi,
    onSaveCrops: (Boolean) -> Unit = {},
    onExportTraining: () -> Unit = {},
    diagnosticLog: (Boolean) -> String? = { null },
    onClearCrops: () -> Unit = {},
    onQuickScan: (Boolean) -> Unit = {},
    onLearnFromIntact: (Boolean) -> Unit = {},
    onLeaveDevMode: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Developer mode", style = MaterialTheme.typography.titleLarge)
        Text(
            "On: training data, learn mode, logs, exports and every detail. User mode shows only the scan and its result, and keeps nothing of a scan. " +
                "To come back, tap the bottom-left corner of the start screen 20 times.",
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.OutlinedButton(onClick = onLeaveDevMode, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Turn off developer mode")
        }
        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "Everything runs on this phone with bundled models. The app has no network access, no accounts and no analytics: nothing you scan leaves the phone unless you share it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Scanning", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick scan", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(ui.quickScan, onQuickScan)
        }
        Text(
            "Reads the sharpest burst frames first and stops once two checks in a row agree on the same number with at least 80% confidence. " +
                "Hard labels still use every frame. Turn off to always use every frame.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Learn from intact labels", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(ui.learnFromIntact, onLearnFromIntact)
        }
        Text(
            "When a label's barcode reads cleanly, the app also reads the printed number (a few seconds more) and saves each digit " +
                "as a trusted training crop, labeled by the barcode. Nothing to type. Scan good labels from many shippers and printers, " +
                "then export the crops below. Stored only on this phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("On-device models", style = MaterialTheme.typography.titleLarge)
        Text("Bundled, offline. Only models that improved the accuracy benchmark are used. In use: ${ui.models}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep digit crops of confirmed labels for retraining", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(ui.saveCrops, onSaveCrops)
        }
        Text(
            "Stored only on this phone; nothing is uploaded. A digit is kept only if at least one reader (OCR, barcode or digit model) also read it that way, " +
                "so misaligned or wrongly confirmed crops are skipped. Crops from an intact label (learn mode) are marked trusted. Every export is checked crop by crop (re-cut, verified, wrong ones dropped) before training. " +
                "With this or learn mode on, a damaged barcode is also kept with its true number whenever that's known (a 2D code read it, or you confirmed or pasted it): " +
                "examples of real damage to improve the barcode decoder.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Training data", style = MaterialTheme.typography.titleLarge)
        Text(
            "${ui.reportCount} report entr${if (ui.reportCount == 1) "y" else "ies"} · ${ui.cropCount} digit crops · ${ui.barcodeCount} barcode examples. " +
                "Export saves all of it, with the report photos and the last scan's diagnostic log, as one ZIP in Downloads: the file to send. " +
                "The report clears after; crops and barcodes stay until you delete them.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onExportTraining, enabled = ui.reportCount > 0 || ui.cropCount > 0 || ui.barcodeCount > 0 || ui.hasDiagnosticLog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Export training data") }
        androidx.compose.material3.OutlinedButton(onClick = onClearCrops, enabled = ui.cropCount > 0 || ui.barcodeCount > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Delete crops and barcode examples")
        }
        Text("Diagnostic log", style = MaterialTheme.typography.titleLarge)
        var redact by remember { mutableStateOf(true) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text(
            "Everything the app read and decided for the last scan: OCR reads, barcode measurements, per-digit evidence and the ranked candidates. " +
                "It's in the export too, not redacted.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(redact, { redact = it })
            Text("Hide names and addresses", style = MaterialTheme.typography.bodyMedium)
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { diagnosticLog(redact)?.let { share(ctx, it.take(400_000)) } }, enabled = ui.hasDiagnosticLog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(if (ui.hasDiagnosticLog) "Share last scan's log" else "No scan yet") }
        Text("Theme follows the system dark mode setting.", style = MaterialTheme.typography.bodySmall)
    }
}
