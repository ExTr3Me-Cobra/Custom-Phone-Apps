package com.labelrescue.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labelrescue.app.ResultState

@Composable
fun ManualScreen(
    text: String,
    onText: (String) -> Unit,
    error: String?,
    state: ResultState?,
    actions: ResultActions,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Type the digits you can read. Use ? for each digit you can't read. Spaces are fine. " +
                "[38] means \"3 or 8\".",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = text, onValueChange = onText,
            label = { Text("Tracking number") },
            placeholder = { Text("9234 ?902 7507 ?500 2117 09") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp),
            singleLine = false,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            supportingText = {
                val count = text.count { it.isLetterOrDigit() || it == '?' || it == '_' } - Regex("\\[[^]]*]").findAll(text).sumOf { m -> m.value.count { it.isLetterOrDigit() } - 1 }
                Text(error ?: "$count characters · USPS numbers have 20, 22, 26, 30 or 34 digits")
            },
        )
        state?.let { ResultsPanel(it, actions) }
    }
}
