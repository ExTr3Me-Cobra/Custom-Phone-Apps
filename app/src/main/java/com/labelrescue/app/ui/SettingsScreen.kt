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
    onSave: (Boolean, String, String?, Boolean, Int) -> Unit,
    onClearCache: () -> Unit,
    onSaveCrops: (Boolean) -> Unit = {},
    onExportCrops: () -> Unit = {},
    onClearCrops: () -> Unit = {},
    onReviewCrops: () -> Unit = {},
    onQuickScan: (Boolean) -> Unit = {},
    onSaveUps: (Boolean, String, String?) -> Unit = { _, _, _ -> },
    onLearnFromIntact: (Boolean) -> Unit = {},
) {
    var upsOn by remember(ui.loaded) { mutableStateOf(ui.upsEnabled) }
    var upsId by remember(ui.loaded) { mutableStateOf(ui.upsClientId) }
    var upsSecret by remember(ui.loaded) { mutableStateOf("") }
    var enabled by remember(ui.loaded) { mutableStateOf(ui.verifyEnabled) }
    var clientId by remember(ui.loaded) { mutableStateOf(ui.clientId) }
    var secret by remember(ui.loaded) { mutableStateOf("") }
    var auto by remember(ui.loaded) { mutableStateOf(ui.autoVerify) }
    var perHour by remember(ui.loaded) { mutableStateOf(ui.requestsPerHour.toString()) }
    LaunchedEffect(ui) { if (ui.loaded) { enabled = ui.verifyEnabled; clientId = ui.clientId; auto = ui.autoVerify; perHour = ui.requestsPerHour.toString() } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "All recovery runs on this phone with bundled models. No analytics and no network use, except the optional USPS check and UPS lookup below, which you turn on with your own keys.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("USPS verification (optional)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Use your own free USPS developer credentials (developers.usps.com: add an app with the Tracking API, then copy its Consumer Key and Secret). " +
                "After ranking, candidates are checked in order and each USPS record is compared with the label (ZIPs, mail class, date). " +
                "Stored encrypted on this phone. Recovery itself never needs this.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable USPS verification", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(enabled, { enabled = it })
        }
        OutlinedTextField(clientId, { clientId = it }, label = { Text("Consumer key (client ID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            secret, { secret = it },
            label = { Text(if (ui.hasSecret) "Consumer secret (saved; type to replace)" else "Consumer secret") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Verify automatically after ranking", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(auto, { auto = it })
        }
        OutlinedTextField(
            perHour, { perHour = it.filter(Char::isDigit).take(5) }, singleLine = true,
            label = { Text("Max USPS requests per hour") },
            supportingText = { Text("USPS doesn't publish its default quota. Keep this at or below your app's quota; 429 responses back off automatically.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            onSave(enabled, clientId, secret.takeIf { it.isNotBlank() }, auto, perHour.toIntOrNull()?.coerceAtLeast(1) ?: 60); secret = ""
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Save")
        }
        androidx.compose.material3.OutlinedButton(onClick = onClearCache, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Clear saved USPS results (${ui.cached})")
        }
        Text("Each number is looked up once and the answer is saved, so it is never queried twice. Clear this to re-check (e.g. a label that wasn't scanned yet).", style = MaterialTheme.typography.bodySmall)
        Text("UPS → USPS lookup (optional)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Some UPS packages (SurePost, Ground Saver) are delivered by USPS. When the label's UPS 1Z number is readable, " +
                "the app asks UPS's official Tracking API which USPS number the package has, and ranks it first if it fits the label. " +
                "Use your own free keys: developer.ups.com, create an app with the Tracking product, copy its Client ID and Secret. " +
                "Only the 1Z number is sent, to onlinetools.ups.com. Stored encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Look up USPS numbers from UPS", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(upsOn, { upsOn = it })
        }
        OutlinedTextField(upsId, { upsId = it }, label = { Text("UPS Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            upsSecret, { upsSecret = it },
            label = { Text(if (ui.upsHasSecret) "UPS Client Secret (saved; type to replace)" else "UPS Client Secret") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSaveUps(upsOn, upsId, upsSecret.takeIf { it.isNotBlank() }); upsSecret = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Save UPS settings")
        }
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
                "so misaligned or wrongly confirmed crops are skipped. Numbers USPS confirmed are marked trusted. Review them before exporting.",
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.FilledTonalButton(onClick = onReviewCrops, enabled = ui.cropCount > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Review ${ui.cropCount} crops")
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = onExportCrops, enabled = ui.cropCount > 0, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Export ${ui.cropCount} crops") }
            androidx.compose.material3.OutlinedButton(onClick = onClearCrops, enabled = ui.cropCount > 0, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Delete crops") }
        }
        Text("Theme follows the system dark mode setting.", style = MaterialTheme.typography.bodySmall)
    }
}
