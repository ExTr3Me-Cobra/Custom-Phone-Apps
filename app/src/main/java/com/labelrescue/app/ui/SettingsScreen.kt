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
fun SettingsScreen(ui: AppViewModel.SettingsUi, onSave: (Boolean, String, String?) -> Unit) {
    var enabled by remember(ui.loaded) { mutableStateOf(ui.verifyEnabled) }
    var clientId by remember(ui.loaded) { mutableStateOf(ui.clientId) }
    var secret by remember(ui.loaded) { mutableStateOf("") }
    LaunchedEffect(ui) { if (ui.loaded) { enabled = ui.verifyEnabled; clientId = ui.clientId } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "All recovery runs on this phone with bundled models. No analytics and no network use, except the optional USPS check below, which only runs when you tap Verify.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("USPS verification (optional)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Use your own free USPS developer credentials (developers.usps.com, Tracking API) to check whether the top candidates exist. Stored encrypted on this phone.",
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
        Button(onClick = { onSave(enabled, clientId, secret.takeIf { it.isNotBlank() }); secret = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Save")
        }
        Text("Theme follows the system dark mode setting.", style = MaterialTheme.typography.bodySmall)
    }
}
