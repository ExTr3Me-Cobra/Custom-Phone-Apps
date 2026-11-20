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
fun SettingsScreen(ui: AppViewModel.SettingsUi, onSave: (Boolean, String, String?, Boolean, Int) -> Unit, onClearCache: () -> Unit) {
    var enabled by remember(ui.loaded) { mutableStateOf(ui.verifyEnabled) }
    var clientId by remember(ui.loaded) { mutableStateOf(ui.clientId) }
    var secret by remember(ui.loaded) { mutableStateOf("") }
    var auto by remember(ui.loaded) { mutableStateOf(ui.autoVerify) }
    var perHour by remember(ui.loaded) { mutableStateOf(ui.requestsPerHour.toString()) }
    LaunchedEffect(ui) { if (ui.loaded) { enabled = ui.verifyEnabled; clientId = ui.clientId; auto = ui.autoVerify; perHour = ui.requestsPerHour.toString() } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "All recovery runs on this phone with bundled models. No analytics and no network use, except the optional USPS check below, which only runs when you tap Verify.",
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
        Text("Theme follows the system dark mode setting.", style = MaterialTheme.typography.bodySmall)
    }
}
