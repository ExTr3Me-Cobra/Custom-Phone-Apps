package com.labelrescue.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onScan: (() -> Unit)?,
    onImport: (() -> Unit)?,
    onManual: () -> Unit,
    onHistory: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Label Rescue", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Recover USPS tracking numbers from damaged, smudged or torn labels. Everything runs on this phone, offline.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.size(4.dp))
        onScan?.let { BigButton("Scan label (burst)", Icons.Default.CameraAlt, primary = true, onClick = it) }
        onImport?.let { BigButton("Import photos", Icons.Default.PhotoLibrary, onClick = it) }
        BigButton("Type digits", Icons.Default.Keyboard, onClick = onManual)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onHistory?.let { BigButton("History", Icons.Default.History, Modifier.weight(1f), onClick = it) }
            onSettings?.let { BigButton("Settings", Icons.Default.Settings, Modifier.weight(1f), onClick = it) }
        }
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, modifier: Modifier = Modifier.fillMaxWidth(), primary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (primary) Button(onClick = onClick, modifier = modifier.heightIn(min = 72.dp)) { content() }
    else FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 64.dp)) { content() }
}
