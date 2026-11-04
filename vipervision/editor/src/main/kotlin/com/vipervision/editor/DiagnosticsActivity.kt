package com.vipervision.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Diagnostic log: the [ViperLog] report, with Copy (to paste into a chat), Share and
 * Clear. The newest lines are at the bottom, and the screen opens scrolled there.
 */
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AccentTheme(this@DiagnosticsActivity) {
                var report by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                fun reload() = scope.launch { report = withContext(Dispatchers.IO) { ViperLog.report() } }
                LaunchedEffect(Unit) { reload() }
                val scroll = rememberScrollState()
                LaunchedEffect(report) { scroll.scrollTo(scroll.maxValue) }

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Text(
                        "Diagnostic log",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp)
                    )
                    Text(
                        "When something goes wrong, come here and tap Copy, then paste it into the chat. " +
                                "It stays on your phone unless you copy or share it.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Pill("Copy", selected = true, filled = true) { report?.let(::copy) }
                        Pill("Share", selected = false) { report?.let(::share) }
                        Pill("Refresh", selected = false) { reload() }
                        Pill("Clear", selected = false) {
                            scope.launch {
                                withContext(Dispatchers.IO) { ViperLog.clear() }
                                reload()
                            }
                        }
                    }
                    // Collapsed to one line (tap it for the list), so the log keeps its room.
                    PhoneCheck.Section()
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            report ?: "Loading…",
                            color = Color(0xFFDDDDDD),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    private fun copy(report: String) {
        // The report is already kept short (the app's own log first, then filtered system lines);
        // this only guards against something unexpected.
        val text = if (report.length > COPY_LIMIT) report.take(COPY_LIMIT) + "\n…(cut)" else report
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Viper Vision log", text))
        Toast.makeText(this, "Copied — paste it into the chat", Toast.LENGTH_SHORT).show()
    }

    private fun share(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Viper Vision diagnostic log")
            putExtra(Intent.EXTRA_TEXT, report.takeLast(SHARE_LIMIT))
        }
        startActivity(Intent.createChooser(intent, "Share log"))
    }

    private companion object {
        const val COPY_LIMIT = 70_000
        const val SHARE_LIMIT = 200_000
    }
}
