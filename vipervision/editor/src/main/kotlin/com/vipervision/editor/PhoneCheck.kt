package com.vipervision.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything Viper Vision needs from the phone, each with a ✓ or ✗ and a way to fix it: shown at
 * the top of Diagnostics and in the report, so what's wrong on any phone is plain to see.
 * [ok] null = not known yet (e.g. the camera hasn't been opened since the app was installed).
 */
object PhoneCheck {

    class Item(val title: String, val ok: Boolean?, val detail: String, val fix: (() -> Intent)? = null)

    private val samsung get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun items(context: Context): List<Item> {
        val prefs = context.getSharedPreferences("Prefs", Context.MODE_PRIVATE)
        val pkg = context.packageName
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val list = mutableListOf<Item>()

        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains("$pkg/${CameraThumbnailService::class.java.name}")
        val running = CameraThumbnailService.connectedAt > 0
        list += Item(
            "Accessibility (camera shortcut, screenshot toolbar)",
            enabled && running,
            when {
                !enabled -> "Off: turn on Viper Vision camera & screenshots"
                !running -> "On, but Android isn't running it: turn it off and on again"
                else -> "On and running"
            }
        ) { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val all = Environment.isExternalStorageManager()
            list += Item("All files access", all, if (all) "Allowed" else "Needed to save, move and delete without Android asking") {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
            }
        }

        val battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg) == true
        list += Item(
            "Unrestricted battery",
            battery,
            if (battery) "Android won't stop Viper Vision in the background"
            else "Android may stop the accessibility service in the background"
        ) { batteryIntent(context) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notes = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            list += Item("Notifications", notes, if (notes) "Allowed" else "Download and AI search progress can't show") {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            }
        }

        val camera = runCatching {
            context.packageManager.resolveActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0)?.activityInfo?.packageName
        }.getOrNull()
        val shortcutOn = prefs.getBoolean("vv_camera_shortcut", true)
        val spotKnown = prefs.all.keys.any { it.startsWith("vv_camera_thumbnail_bounds") }
        list += Item(
            "Camera shortcut",
            if (!shortcutOn) true else if (camera == null) false else if (spotKnown) true else null,
            when {
                !shortcutOn -> "Turned off in Settings"
                camera == null -> "No camera app found"
                spotKnown -> "Camera app $camera; its thumbnail has been found"
                else -> "Camera app $camera; open the camera once so the thumbnail is found"
            }
        )

        val toolbarOn = prefs.getBoolean(ScreenshotToolbar.PREF_ENABLED, true)
        val lastShot = prefs.getLong(ScreenshotToolbar.LAST_SEEN, 0L)
        list += Item(
            "Screenshot toolbar",
            if (!toolbarOn) true else if (lastShot > 0) true else null,
            when {
                !toolbarOn -> "Turned off in Settings"
                lastShot > 0 -> "Last screenshot seen ${time.format(Date(lastShot))}"
                else -> "No screenshot seen yet: take one to check"
            }
        )
        if (samsung && toolbarOn) {
            val off = prefs.getBoolean("vv_samsung_toolbar_off", false)
            list += Item("Samsung's own screenshot toolbar off", off, if (off) "Off" else "Still on: the setup asks to turn it off")
        }

        val models = prefs.getBoolean("vv_models_complete", false)
        list += Item("AI models", models, if (models) "All downloaded" else "Not all downloaded yet (Settings → AI models)")
        return list
    }

    /** The phone's "allow unrestricted battery" prompt, or its battery list if that's missing. */
    fun batteryIntent(context: Context): Intent {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        return if (ask.resolveActivity(context.packageManager) != null) ask else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /** As text, for the report. */
    fun summary(context: Context): String = items(context).joinToString("\n") { item ->
        "${mark(item.ok)} ${item.title}: ${item.detail}"
    }

    private fun mark(ok: Boolean?) = when (ok) { true -> "✓"; false -> "✗"; null -> "?" }

    /** The list on the Diagnostics screen, refreshed every second (e.g. back from Settings). */
    @Composable
    fun Section() {
        val context = LocalContext.current
        var items by remember { mutableStateOf(items(context)) }
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                items = items(context)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(Color(0xFF16181B), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val problems = items.count { it.ok == false }
            val unknown = items.count { it.ok == null }
            Text(
                "Phone check: " + when {
                    problems > 0 -> "$problems to fix"
                    unknown > 0 -> "all fine so far ($unknown not checked yet)"
                    else -> "all fine"
                } + if (open) "  ▾" else "  ▸",
                color = if (problems > 0) Color(0xFFFF6E6E) else Color.White,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable { open = !open }
            )
            if (open) for (item in items) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mark(item.ok),
                        color = when (item.ok) { true -> Color(0xFF69F0AE); false -> Color(0xFFFF6E6E); null -> Color(0xFFFFD54F) },
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, fontSize = 13.sp)
                        Text(item.detail, color = Color.Gray, fontSize = 11.sp)
                    }
                    val fix = item.fix
                    if (item.ok == false && fix != null) {
                        TextButton(onClick = { runCatching { context.startActivity(fix().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                            Text("Fix", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
