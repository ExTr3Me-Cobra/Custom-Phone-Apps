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
    onExportCrops: () -> Unit = {},
    onClearCrops: () -> Unit = {},
    onReviewCrops: () -> Unit = {},
    onQuickScan: (Boolean) -> Unit = {},
    onLearnFromIntact: (Boolean) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                "so misaligned or wrongly confirmed crops are skipped. Crops from an intact label (learn mode) are marked trusted. Review them before exporting. " +
                "With this or learn mode on, a damaged barcode is also kept with its true number whenever that's known (a 2D code read it, or you confirmed or pasted it): " +
                "examples of real damage to improve the barcode decoder.",
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.FilledTonalButton(onClick = onReviewCrops, enabled = ui.cropCount > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Review ${ui.cropCount} crops")
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            val any = ui.cropCount > 0 || ui.barcodeCount > 0
            androidx.compose.material3.OutlinedButton(onClick = onExportCrops, enabled = any, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text("Export ${ui.cropCount} crops" + if (ui.barcodeCount > 0) " + ${ui.barcodeCount} barcodes" else "")
            }
            androidx.compose.material3.OutlinedButton(onClick = onClearCrops, enabled = any, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Delete all") }
        }
        Text("Theme follows the system dark mode setting.", style = MaterialTheme.typography.bodySmall)
    }
}
