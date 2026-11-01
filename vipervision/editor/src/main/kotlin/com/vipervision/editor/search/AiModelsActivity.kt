package com.vipervision.editor.search

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipervision.editor.appAccentColor
import com.vipervision.editor.contentOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings → AI models (also offered once when the app first opens): the one-time download of
 * every AI model ([ModelPack]), its progress, and what's already here.
 */
class AiModelsActivity : ComponentActivity() {

    private class State(val here: List<Boolean>, val running: Boolean, val waitingForWifi: Boolean, val progress: Float, val current: String?, val error: String?)

    private val allInOne by lazy { ModelPack.isAllInOne(this) }
    private var switchMessage by mutableStateOf<String?>(null)
    private var switchWhenAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Offered once: opening this screen counts.
        getSharedPreferences("Prefs", MODE_PRIVATE).edit().putBoolean(ModelPack.KEY_PROMPTED, true).apply()
        setContent { Screen() }
        onSwitchResult(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onSwitchResult(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from allowing Viper Vision to install apps.
        if (switchWhenAllowed && canInstall()) {
            switchWhenAllowed = false
            switchToSlim()
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun switchToSlim() {
        if (!canInstall()) {
            // Android asks once whether Viper Vision may install apps (only to replace itself).
            switchWhenAllowed = true
            switchMessage = "Turn on \"Allow from this source\", then come back."
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        switchMessage = "Preparing…"
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { ModelPack.switchToSlim(this@AiModelsActivity) }.exceptionOrNull() }
            switchMessage = error?.let { "Couldn't switch: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** Android's answer to [ModelPack.switchToSlim]: its Update prompt, or why it failed. */
    private fun onSwitchResult(intent: Intent?) {
        if (intent?.action != ModelPack.ACTION_SLIM_INSTALLED) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (prompt != null) runCatching { startActivity(prompt) }
                switchMessage = null
            }
            // On success Android closes the app while replacing it, so this rarely shows.
            PackageInstaller.STATUS_SUCCESS -> switchMessage = "Done."
            PackageInstaller.STATUS_FAILURE_ABORTED -> switchMessage = null
            else -> switchMessage = "Couldn't switch: " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused the install")
        }
    }

    @Composable
    private fun Screen() {
        val accent = remember { appAccentColor(this) }
        val items = ModelPack.items
        val state by produceState<State?>(null) {
            while (true) {
                value = withContext(Dispatchers.IO) {
                    State(
                        here = items.map { runCatching { it.isHere(this@AiModelsActivity) }.getOrDefault(false) },
                        running = ModelPack.isRunning(this@AiModelsActivity),
                        waitingForWifi = ModelPack.isWaiting(this@AiModelsActivity),
                        progress = ModelPack.progress(this@AiModelsActivity),
                        current = ModelPack.current(this@AiModelsActivity),
                        error = ModelPack.lastError(this@AiModelsActivity)
                    )
                }
                delay(1500)
            }
        }
        val s = state
        val hereBytes = s?.let { items.filterIndexed { i, _ -> it.here[i] }.sumOf { it.size } } ?: 0L
        val complete = s != null && s.here.all { it }
        // Opened when the app starts with models missing: it can't be skipped (and the gallery
        // reopens it if it's left anyway). Back works once the
        // download is under way or waiting for Wi-Fi (it carries on in the background), or when
        // all are here; the all-in-one APK's only while unpacking, until it has switched.
        val canLeave = s != null && if (allInOne) s.running else complete || s.running || s.waitingForWifi ||
                ModelPack.isRequested(this)
        androidx.activity.compose.BackHandler(enabled = !canLeave) {}

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Text("AI models", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Every AI tool in Viper Vision and the AI search use these: about ${gb(ModelPack.totalSize)} in all. " +
                        if (allInOne) "This all-in-one version has them inside it; they're unpacked once, with no " +
                                "internet needed, and stay through every update."
                        else "Downloaded once, they stay through every update. They come from Viper Vision's own " +
                                "permanent copy (falling back to the original sites).",
                color = Color.Gray, fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            when {
                s == null -> Unit
                complete && allInOne -> {
                    getSharedPreferences("Prefs", MODE_PRIVATE).edit().putBoolean(ModelPack.KEY_COMPLETE, true).apply()
                    Text("All unpacked (${gb(hereBytes)}).", color = accent, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Last step: switch to the normal version of the app. It's the same app without the " +
                                "packed copy of the models, which frees about ${gb(apkSize())}. Your settings, " +
                                "models and photos all stay. Android will ask you to tap Update.",
                        color = Color.White, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { switchToSlim() },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Switch now", fontSize = 16.sp) }
                    switchMessage?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Color.Gray, fontSize = 13.sp)
                    }
                }
                complete -> {
                    // (Also tells the gallery, in case they came from an earlier build.)
                    getSharedPreferences("Prefs", MODE_PRIVATE).edit().putBoolean(ModelPack.KEY_COMPLETE, true).apply()
                    Text("All downloaded (${gb(hereBytes)}).", color = accent, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Done", fontSize = 16.sp) }
                }
                s.running -> {
                    Text(
                        (if (allInOne) "Unpacking… " else "Downloading… ") + "${(s.progress * 100).toInt()}%" + (s.current?.let { " · $it" } ?: ""),
                        color = Color.White, fontSize = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { s.progress }, color = accent, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("It keeps going in the background; you can leave this screen.", color = Color.Gray, fontSize = 12.sp)
                }
                allInOne -> {
                    // Unpacking starts by itself when the app opens; this restarts it after a stop.
                    Text(
                        "${gb(hereBytes)} of ${gb(ModelPack.totalSize)} unpacked." +
                                (s.error?.let { "\nLast try stopped: $it" } ?: ""),
                        color = Color.White, fontSize = 15.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { ModelPack.start(this@AiModelsActivity, allowMobileData = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Unpack", fontSize = 16.sp) }
                }
                else -> {
                    Text(
                        "${gb(hereBytes)} of ${gb(ModelPack.totalSize)} here." +
                                (s.error?.let { "\nLast try stopped: $it (it will try again)." } ?: ""),
                        color = Color.White, fontSize = 15.sp
                    )
                    if (s.waitingForWifi) {
                        Spacer(Modifier.height(4.dp))
                        Text("Waiting for Wi-Fi: it starts by itself once you're connected.", color = Color.Gray, fontSize = 13.sp)
                        TextButton(onClick = { ModelPack.start(this@AiModelsActivity, allowMobileData = true) }) {
                            Text("Use mobile data now", color = accent)
                        }
                        if (ModelPack.dataSaverBlocks(this@AiModelsActivity)) {
                            Text("Data Saver is on: allow Viper Vision unrestricted data (App info → Mobile data) for it to download on mobile data.", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Only what's missing is downloaded (models from earlier builds are kept).
                    val left = ModelPack.totalSize - hereBytes
                    var askAboutData by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            // On Wi-Fi it just starts; on mobile data it asks first.
                            if (onMobileData()) askAboutData = true
                            else ModelPack.start(this@AiModelsActivity, allowMobileData = false)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accent.contentOn()),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text(if (hereBytes > 0) "Download the rest (${gb(left)})" else "Download (${gb(left)})", fontSize = 16.sp) }
                    if (askAboutData) {
                        AlertDialog(
                            // A choice has to be made (no tapping outside or Back).
                            onDismissRequest = {},
                            properties = androidx.compose.ui.window.DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            ),
                            title = { Text("You're on mobile data") },
                            text = { Text("The AI models are ${gb(left)}. Download them on mobile data now, or wait until you're on Wi-Fi?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    askAboutData = false
                                    ModelPack.start(this@AiModelsActivity, allowMobileData = true)
                                }) { Text("Use mobile data", color = accent) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    askAboutData = false
                                    ModelPack.start(this@AiModelsActivity, allowMobileData = false)
                                }) { Text("Wait for Wi-Fi", color = accent) }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(items.indices.toList()) { i ->
                    val item = items[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(mb(item.size), color = Color.Gray, fontSize = 13.sp)
                        Text(
                            if (s?.here?.getOrNull(i) == true) "  ✓" else "   ",
                            color = accent, fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    /** On a metered connection (mobile data, or a hotspot Android counts as one). */
    private fun onMobileData(): Boolean {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun apkSize() = runCatching { java.io.File(applicationInfo.sourceDir).length() }.getOrDefault(0L)

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)
    private fun mb(bytes: Long) = when {
        bytes >= 1e9 -> gb(bytes)
        bytes < 1_000_000 -> "<1 MB"
        else -> "%.0f MB".format(bytes / 1e6)
    }
}
