package com.glowalerts.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GlowListener.ensureChannels(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Screen() {
    val context = LocalContext.current
    Prefs.get(context)
    val s by Prefs.flow.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        Tilt.start(context)
        KeepAlive.start(context)
    }
    fun set(change: (Settings) -> Settings) = Prefs.update(context, change)

    /** Plays the current effect on screen, if live previews are on. */
    fun preview() {
        val now = Prefs.get(context)
        if (now.autoPreview) playFullScreen(context, now)
    }

    /** A change that should be shown straight away (switches, chips). */
    fun setAndShow(change: (Settings) -> Settings) {
        set(change)
        preview()
    }

    // Which colour the picker is open for: 1, 2, or 0 = closed.
    var picking by remember { mutableIntStateOf(0) }
    // The live camera ring only shows while a camera-ring slider is being moved (and briefly after).
    var camTouched by remember { mutableLongStateOf(0L) }
    var showRing by remember { mutableStateOf(false) }
    LaunchedEffect(camTouched) {
        if (camTouched > 0) {
            kotlinx.coroutines.delay(1500)
            showRing = false
        }
    }
    fun camChange(change: (Settings) -> Settings) {
        set(change)
        showRing = true
        camTouched = System.currentTimeMillis()
    }

    val setupDone = remember(tick) {
        GlowListener.isEnabled(context) && LightService.isEnabled(context)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Text("Glow Alerts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp))
                .background(if (s.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .clickable { set { it.copy(enabled = !it.enabled) } }
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (s.enabled) "Glow Alerts is on" else "Glow Alerts is off", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (s.enabled) "Every new notification lights up the screen." else "No lighting.", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = s.enabled, onCheckedChange = { v -> set { it.copy(enabled = v) } })
        }

        Fold("Setup", open = !setupDone, summary = if (setupDone) "✅ All set" else "⚠️ Needs attention – tap to open") {
            Steps(tick, s.wakeScreen)
            PhoneCheck(tick)
        }

        // Preview: always visible.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniPreview(s)
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.effect.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = { playFullScreen(context, s) }) { Text("Play full screen") }
                OutlinedButton(onClick = { sendTest(context, 0) }) { Text("Test notification") }
                OutlinedButton(onClick = { sendTest(context, 6000) }) { Text("Test in 6 s") }
            }
        }
        SwitchRow(
            "Play on screen as I change things",
            "Picking an effect plays it straight away; other changes play when you let go of a slider or flip a switch.",
            s.autoPreview,
        ) { v -> set { it.copy(autoPreview = v) } }

        Fold("Effect", open = true, summary = s.effect.label) {
            LightGroup.entries.forEach { group ->
                Label(group.label)
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LightEffect.entries.filter { it.group == group }.forEach { e ->
                        FilterChip(selected = s.effect == e, onClick = { setAndShow { it.copy(effect = e) } }, label = { Text(e.label) })
                    }
                }
            }
        }

        if (s.effect.isCrack) {
            val lightning = s.effect == LightEffect.CRACK_LIGHTNING
            Fold("${s.effect.label} settings", open = true) {
                Label("How it starts")
                SwitchRow("All at once", "The whole crack appears in one hit instead of spreading", s.crackAllAtOnce) { v -> setAndShow { it.copy(crackAllAtOnce = v) } }
                Note(if (s.crackAllAtOnce) "Centred on:" else "Spreads from:")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CrackOrigin.entries.forEach { o ->
                        FilterChip(selected = s.crackOrigin == o, onClick = { setAndShow { it.copy(crackOrigin = o) } }, label = { Text(o.label) })
                    }
                }
                SwitchRow("Flash on impact", "A burst of light where the crack hits", s.crackFlash) { v -> setAndShow { it.copy(crackFlash = v) } }
                if (s.crackFlash) {
                    SwitchRow("Flash fills the whole screen", "Off: the flash is a burst where the crack starts", s.crackFlashFull) { v -> setAndShow { it.copy(crackFlashFull = v) } }
                }

                if (lightning) {
                    Label("Where it lands")
                    SwitchRow(
                        "Land at the bottom as I'm holding it",
                        "Turned sideways, it lands along whichever side is down. Upright, upside down or lying flat, it lands along the bottom.",
                        s.lightningTilt,
                    ) { v -> setAndShow { it.copy(lightningTilt = v) } }
                    if (s.lightningTilt) {
                        val mode by produceState(Tilt.landing) {
                            while (true) {
                                value = Tilt.landing
                                kotlinx.coroutines.delay(250)
                            }
                        }
                        Note("Right now: ${Tilt.label(mode)}.")
                    }
                }

                if (lightning && s.crackOrigin == CrackOrigin.CAMERA) {
                    Label("Camera hole ring")
                    SwitchRow("Glow around the camera hole", "Where the lightning comes from", s.camRing) { v -> setAndShow { it.copy(camRing = v) } }
                    if (s.camRing) {
                        Note("The ring shows on your real camera hole while you move these sliders.")
                        SliderRow("Move left / right", "%+.1f dp".format(s.camOffsetX), s.camOffsetX, -40f..40f) { v -> camChange { it.copy(camOffsetX = (v * 2).roundToInt() / 2f) } }
                        SliderRow("Move up / down", "%+.1f dp".format(s.camOffsetY), s.camOffsetY, -40f..40f) { v -> camChange { it.copy(camOffsetY = (v * 2).roundToInt() / 2f) } }
                        SliderRow("Ring size", "%+.1f dp".format(s.camSizeAdjust), s.camSizeAdjust, -20f..40f) { v -> camChange { it.copy(camSizeAdjust = (v * 2).roundToInt() / 2f) } }
                        SliderRow("Ring thickness", "%.1f dp".format(s.camRingDp), s.camRingDp, 0.5f..16f) { v -> camChange { it.copy(camRingDp = (v * 2).roundToInt() / 2f) } }
                        SliderRow("Ring glow", "${(s.camRingGlow * 100).roundToInt()} %", s.camRingGlow, 0f..5f) { v -> camChange { it.copy(camRingGlow = (v * 20).roundToInt() / 20f) } }
                        if (showRing) LiveCamRing(s)
                    }
                }

                Label("Shape")
                SliderRow("Line thickness", "${s.crackLineDp.roundToInt()} dp", s.crackLineDp, 1f..16f, ::preview) { v -> set { it.copy(crackLineDp = v.roundToInt().toFloat()) } }
                SliderRow("Detail", "${s.crackDetail} / 10", s.crackDetail.toFloat(), 1f..10f, ::preview) { v -> set { it.copy(crackDetail = v.roundToInt()) } }
                SliderRow("Number of cracks", "${s.crackCount}", s.crackCount.toFloat(), 1f..6f, ::preview) { v -> set { it.copy(crackCount = v.roundToInt()) } }
                SliderRow("Jaggedness", "${(s.crackJagged * 100).roundToInt()} %", s.crackJagged, 0.2f..2f, ::preview) { v -> set { it.copy(crackJagged = (v * 20).roundToInt() / 20f) } }
                SliderRow("Branch length", "${(s.crackBranchLength * 100).roundToInt()} %", s.crackBranchLength, 0.3f..2.5f, ::preview) { v -> set { it.copy(crackBranchLength = (v * 20).roundToInt() / 20f) } }
                SwitchRow("White-hot core", "A bright white line down the middle of each crack", s.crackCore) { v -> setAndShow { it.copy(crackCore = v) } }

                Label("Motion")
                SliderRow("Screen shake", if (s.crackShake == 0f) "Off" else "${(s.crackShake * 100).roundToInt()} %", s.crackShake, 0f..3f, ::preview) { v -> set { it.copy(crackShake = (v * 20).roundToInt() / 20f) } }
                SliderRow("Cracks per alert", "${s.crackRepeats}×", s.crackRepeats.toFloat(), 1f..6f, ::preview) { v -> set { it.copy(crackRepeats = v.roundToInt()) } }
                SwitchRow("Flicker", "Flickers like lightning while it forms", s.crackFlicker) { v -> setAndShow { it.copy(crackFlicker = v) } }
                SwitchRow("Shimmer", "Gently pulses once it's formed", s.crackShimmer) { v -> setAndShow { it.copy(crackShimmer = v) } }
                SwitchRow("Pull back at the end", "The crack retreats into where it started instead of just fading", s.crackRetract) { v -> setAndShow { it.copy(crackRetract = v) } }
                SwitchRow("Also light the screen edge", "A glow around the edge while it's cracked", s.crackEdgeGlow) { v -> setAndShow { it.copy(crackEdgeGlow = v) } }
                SwitchRow("Same crack every time", "Off: every notification gets a new random crack", s.crackSamePattern) { v -> setAndShow { it.copy(crackSamePattern = v) } }
                if (s.crackSamePattern) {
                    OutlinedButton(
                        onClick = { setAndShow { it.copy(crackSeed = kotlin.random.Random.nextLong()) } },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("Try a different crack") }
                }
            }
        }

        Fold("Colour", open = true, summary = s.colorMode.label) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorMode.entries.forEach { m ->
                    FilterChip(selected = s.colorMode == m, onClick = { setAndShow { it.copy(colorMode = m) } }, label = { Text(m.label) })
                }
            }
            when (s.colorMode) {
                ColorMode.APP -> {
                    Note("Each notification uses its app's colour (or its icon's main colour). This one is used when an app has none:")
                    ColorRow("Fallback colour", s.color1) { picking = 1 }
                }
                ColorMode.ONE -> ColorRow("Colour", s.color1) { picking = 1 }
                ColorMode.TWO -> {
                    ColorRow("First colour", s.color1) { picking = 1 }
                    ColorRow("Second colour", s.color2) { picking = 2 }
                }
                ColorMode.RAINBOW -> Note("Every effect turns through all the colours of the rainbow.")
            }
        }

        Fold("Look", summary = "Speed ${speedLabel(s.speed)} · ${secondsLabel(s.seconds)} · brightness ${s.brightness} %") {
            // Speed slider runs from 0.25x to 20x; spread out so slow speeds are still easy to set.
            SliderRow("Speed", speedLabel(s.speed), speedToSlider(s.speed), 0f..1f, ::preview) { p -> set { it.copy(speed = sliderToSpeed(p)) } }
            SliderRow("Plays for", secondsLabel(s.seconds), secondsToSlider(s.seconds), 0f..1f, ::preview) { p -> set { it.copy(seconds = sliderToSeconds(p)) } }
            SliderRow("Brightness", "${s.brightness} %", s.brightness.toFloat(), 20f..300f, ::preview) { v -> set { it.copy(brightness = (v / 5).roundToInt() * 5) } }
            Note("Over 100 % the light is stacked for an extra-bright, blown-out look.")
            SliderRow("Glow", "${(s.glow * 100).roundToInt()} %", s.glow, 0f..5f, ::preview) { v -> set { it.copy(glow = (v * 20).roundToInt() / 20f) } }
            // Edge line thickness and corners only matter for edge effects (or a crack's edge glow).
            if (!s.effect.isCrack || s.crackEdgeGlow) {
                SliderRow("Edge line thickness", "${s.thicknessDp.roundToInt()} dp", s.thicknessDp, 1f..30f, ::preview) { v -> set { it.copy(thicknessDp = v.roundToInt().toFloat()) } }
                SwitchRow("Match my screen's corners", "Follows the exact curve of your screen's corners", s.matchCorners) { v -> setAndShow { it.copy(matchCorners = v) } }
                if (!s.matchCorners) {
                    SliderRow("Corner curve", "${s.cornerDp.roundToInt()} dp", s.cornerDp, 0f..120f, ::preview) { v -> set { it.copy(cornerDp = v.roundToInt().toFloat()) } }
                }
            }
        }

        Fold("When", summary = listOfNotNull(
            "in use".takeIf { s.onUnlocked }, "lock screen".takeIf { s.onLocked }, "AOD".takeIf { s.onAod },
            "wakes screen".takeIf { s.wakeScreen },
        ).joinToString(" · ").ifEmpty { "never" }) {
            SwitchRow("While using the phone", "Lights up over whatever's on screen", s.onUnlocked) { v -> set { it.copy(onUnlocked = v) } }
            SwitchRow("On the lock screen", "Lights up while locked", s.onLocked) { v -> set { it.copy(onLocked = v) } }
            SwitchRow(
                "On the Always On Display",
                "Lights up on the Always On Display without waking the phone. Smoothness depends on the phone (the AOD refreshes slowly).",
                s.onAod,
            ) { v -> set { it.copy(onAod = v) } }
            SwitchRow(
                "Wake the screen",
                "When the screen is off, turn it on for each notification so you see the lighting. Needs \"Full screen notifications\" in Setup.",
                s.wakeScreen,
            ) { v -> set { it.copy(wakeScreen = v) } }
            if (s.wakeScreen) {
                SwitchRow(
                    "Wake from the Always On Display too",
                    "Samsung may not show other apps' lighting on the AOD. On: the AOD wakes to the lock screen and the lighting plays there. Off: it tries to play on the AOD itself.",
                    s.wakeFromAod,
                ) { v -> set { it.copy(wakeFromAod = v) } }
            }
            SwitchRow("Include silent notifications", "Also light up for notifications apps send quietly", s.includeSilent) { v -> set { it.copy(includeSilent = v) } }
        }

        Fold("Reliability", summary = if (s.alwaysReady) "Always ready" else "Off") {
            SwitchRow(
                "Always ready",
                "Keeps Glow Alerts running so Android never puts it to sleep or delays it, and reconnects it if it's ever cut off. Uses more battery.",
                s.alwaysReady,
            ) { v ->
                set { it.copy(alwaysReady = v) }
                KeepAlive.start(context)
            }
            if (s.alwaysReady) {
                SwitchRow(
                    "Show the \"ready\" notification",
                    "Off: no notification at all. Glow Alerts still stays running through its lighting service (which Android keeps on), with the same keep-awake and reconnect checks.",
                    s.readyNotification,
                ) { v ->
                    set { it.copy(readyNotification = v) }
                    KeepAlive.start(context)
                }
                SwitchRow(
                    "Keep the processor awake",
                    "Never lets the phone's processor fully sleep, for the fastest possible reaction. Uses noticeably more battery.",
                    s.keepAwake,
                ) { v ->
                    set { it.copy(keepAwake = v) }
                    KeepAlive.start(context)
                }
            }
            Note("Tip: also set Settings → Battery → Background usage limits so Glow Alerts is under \"Never auto sleeping apps\".")
        }

        Fold("Apps", summary = if (s.excluded.isEmpty()) "All apps light up" else "${s.excluded.size} switched off") {
            AppList(s.excluded) { pkg, on -> set { it.copy(excluded = if (on) it.excluded - pkg else it.excluded + pkg) } }
        }

        Fold("Save & restore settings", summary = "Keep your settings for a reinstall or a new phone") {
            SettingsBackup()
        }
        Fold("Recent activity", summary = Log.lines.firstOrNull() ?: "Nothing yet") {
            if (Log.lines.isEmpty()) Note("Nothing yet. New notifications will be listed here with what Glow Alerts did.")
            Log.lines.take(20).forEach {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
            }
        }
        Spacer(Modifier.size(40.dp))
    }

    if (picking != 0) {
        ColorPickerDialog(
            title = if (picking == 1) "First colour" else "Second colour",
            initial = if (picking == 1) s.color1 else s.color2,
            recent = s.recentColors,
            onDismiss = { picking = 0 },
            onPick = { c ->
                val which = picking
                setAndShow { if (which == 1) it.copy(color1 = c) else it.copy(color2 = c) }
                Prefs.addRecentColor(context, c)
                picking = 0
            },
        )
    }
}

/** Save settings to a file (Documents by default) and restore them, e.g. after reinstalling. */
@Composable
private fun SettingsBackup() {
    val context = LocalContext.current
    val docs = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
    val save = rememberLauncherForActivityResult(object : androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json") {
        override fun createIntent(c: Context, input: String) = super.createIntent(c, input).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, docs)
    }) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Prefs.exportJson(context).toByteArray()) } }.isSuccess
        android.widget.Toast.makeText(context, if (ok) "Settings saved" else "Couldn't save the settings", android.widget.Toast.LENGTH_SHORT).show()
    }
    val restore = rememberLauncherForActivityResult(object : androidx.activity.result.contract.ActivityResultContracts.OpenDocument() {
        override fun createIntent(c: Context, input: Array<String>) = super.createIntent(c, input).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, docs)
    }) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            Prefs.importJson(context, context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrDefault(false)
        android.widget.Toast.makeText(context, if (ok) "Settings restored" else "That isn't a Glow Alerts settings file", android.widget.Toast.LENGTH_LONG).show()
    }
    Note("Saves every setting (effect, colours, look, when to light up, switched-off apps) to a small file, "
        + "in Documents unless you pick somewhere else. After reinstalling, tap Restore and pick that file. "
        + "Permissions (notification access, accessibility) have to be given again after a reinstall.")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { save.launch("glow-alerts-settings.json") }) { Text("Save settings") }
        OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore settings") }
    }
}

/** A section that folds open and shut; [summary] shows while it's shut. */
@Composable
private fun Fold(title: String, open: Boolean = false, summary: String? = null, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(open) }
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            if (!expanded && summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
    }
    if (expanded) content()
}

@Composable
private fun Steps(tick: Int, wake: Boolean) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val pkgUri = Uri.parse("package:${context.packageName}")
    val nm = context.getSystemService(NotificationManager::class.java)
    val listener = remember(tick) { GlowListener.isEnabled(context) }
    val lighting = remember(tick) { LightService.isEnabled(context) }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val notify = remember(tick) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && nm.areNotificationsEnabled()
    }
    val fsi = remember(tick) { Waker.fullScreenAllowed(context) }

    Step("1. Notification access", "Lets Glow Alerts see new notifications. Turn on \"Glow Alerts\".", listener) {
        open(context, Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    Step(
        "2. Switch on \"Glow Alerts Lighting\"",
        "Settings → Accessibility → Installed apps → Glow Alerts Lighting → On. Needed to draw over the lock screen and Always On Display; it only draws the light and reads nothing on your screen.",
        lighting,
    ) { open(context, Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
    if (!listener || !lighting) {
        Note("Switch greyed out (\"Restricted setting\")? Open App info → ⋮ (top right) → Allow restricted settings, then try again.")
        OutlinedButton(
            onClick = { open(context, Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text("Open App info") }
    }
    Step("3. Unrestricted battery", "So Samsung never puts Glow Alerts to sleep.", battery) {
        open(context, Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
    }
    Step("4. Allow notifications", "Turn on \"Allow notifications\" for Glow Alerts. Needed for the test button and to wake the screen.", notify) {
        // The settings page always works, even if the permission pop-up was turned down before.
        open(context, Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
    }
    if (wake) {
        Step("5. Full screen notifications", "Needed for \"Wake the screen\" (how alarm apps turn the screen on).", fsi) {
            open(context, Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
        }
    }
}

/**
 * Phone settings outside this app that stop notifications waking or showing on the lock screen /
 * AOD (Samsung's, or changed by the Shade app made earlier).
 */
@Composable
private fun PhoneCheck(tick: Int) {
    val context = LocalContext.current
    val cr = context.contentResolver
    val problems = remember(tick) {
        buildList {
            val lockNotes = runCatching { AndroidSettings.Secure.getInt(cr, "lock_screen_show_notifications", 1) }.getOrDefault(1)
            if (lockNotes == 0) add("Notifications are hidden on the lock screen (Settings → Notifications → Lock screen notifications). With them hidden, the AOD and lock screen don't react to new notifications.")
            val headsUp = runCatching { AndroidSettings.Global.getInt(cr, "heads_up_notifications_enabled", 1) }.getOrDefault(1)
            if (headsUp == 0) add("Notification pop-ups are switched off for the whole phone. The Shade app's \"Turn off Samsung's duplicates\" does this: open Shade and switch it back, or uninstall Shade.")
            val shade = runCatching { context.packageManager.getPackageInfo("com.shadeui.app", 0); true }.getOrDefault(false)
            if (shade) add("The Shade app is installed. If it's switched on, it can hide or replace Samsung's notifications; turn it off while testing.")
            if (!LightService.aodEnabled(context)) add("Always On Display is off, so the screen stays black until something wakes it. Keep \"Wake the screen\" on (below), or turn on AOD in Settings → Lock screen and AOD.")
        }
    }
    if (problems.isEmpty()) return
    Label("Phone settings to check")
    problems.forEach { Note("⚠️ $it") }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open(context, Intent("android.settings.NOTIFICATION_SETTINGS")) }) { Text("Notification settings") }
        OutlinedButton(onClick = { open(context, Intent(AndroidSettings.ACTION_SECURITY_SETTINGS)) }) { Text("Lock screen") }
    }
}

@Composable
private fun Step(title: String, summary: String, done: Boolean, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { action() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (done) "✅" else "⬜", Modifier.padding(end = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Keeps the camera ring drawn on the real screen while these settings are showing. */
@Composable
private fun LiveCamRing(s: Settings) {
    val context = LocalContext.current
    val ring = remember { CamRingView(context) }
    DisposableEffect(Unit) {
        val root = (context as? Activity)?.window?.decorView as? ViewGroup
        root?.addView(ring, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onDispose { root?.removeView(ring) }
    }
    SideEffect { ring.settings = s }
}

/** A small phone-shaped preview that plays the effect in a loop. */
@Composable
private fun MiniPreview(s: Settings) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthDp = 130.dp
    val screenW = context.resources.displayMetrics.widthPixels.toFloat()
    val screenH = context.resources.displayMetrics.heightPixels.toFloat()
    val boxW = with(density) { widthDp.toPx() }
    val scale = boxW / screenW
    val heightDp = with(density) { (screenH * scale).toDp() }
    val full = LightService.spec(context, s, null, forever = true)
    val spec = full.copy(
        thicknessPx = full.thicknessPx * scale * 2f,
        cornerPx = full.cornerPx * scale,
        crack = full.crack.copy(lineWidthPx = full.crack.lineWidthPx * scale * 2f, camRingPx = full.crack.camRingPx * scale * 2f),
    )
    val cornerDp = with(density) { spec.cornerPx.toDp() }
    Box(
        Modifier.size(widthDp, heightDp).clip(RoundedCornerShape(cornerDp)).background(Color(0xFF0B0B0F))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(cornerDp)),
    ) {
        AndroidView(
            factory = { EdgeLightView(it, spec) },
            update = { view ->
                if (view.spec.effect != spec.effect || view.spec.crack != spec.crack) {
                    view.spec = spec
                    view.restart()
                } else {
                    view.spec = spec
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AppList(excluded: Set<String>, onChange: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }
                .map { it to GlowListener.appName(context, it) }.sortedBy { it.second.lowercase() }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    Note("Untick apps that should never light up.${if (excluded.isNotEmpty()) " (${excluded.size} switched off)" else ""}")
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(if (expanded) "Hide apps" else "Show apps")
    }
    if (!expanded) return
    val list = apps
    if (list == null) {
        Note("Loading apps…")
        return
    }
    list.forEach { (pkg, name) ->
        val on = pkg !in excluded
        Row(
            Modifier.fillMaxWidth().clickable { onChange(pkg, !on) }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = { onChange(pkg, it) })
            Text(name)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onFinished: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}

/** Plays the effect over the whole screen: through the service if it's on, else inside the app. */
private fun playFullScreen(context: Context, s: Settings) {
    val spec = LightService.spec(context, s, null)
    LightService.instance?.let {
        it.play(spec)
        return
    }
    val activity = context as? Activity ?: return
    val root = activity.window.decorView as? ViewGroup ?: return
    lateinit var view: EdgeLightView
    view = EdgeLightView(activity, spec) { root.removeView(view) }
    root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

/**
 * Posts an ordinary notification, which Glow Alerts then lights up for like any other. A wake
 * lock keeps the phone awake during the wait so the test arrives on time after locking.
 */
private fun sendTest(context: Context, delayMs: Long) {
    val app = context.applicationContext
    GlowListener.ensureChannels(app)
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        return
    }
    if (delayMs > 0) {
        app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glowalerts:test")
            .acquire(delayMs + 10_000)
        Log.add("Test: sending in ${delayMs / 1000} s…")
    }
    Handler(Looper.getMainLooper()).postDelayed({
        val n = Notification.Builder(app, GlowListener.TEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Test notification")
            .setContentText("If the screen lit up, Glow Alerts is working.")
            .setAutoCancel(true)
            .build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt(), n) }
    }, delayMs)
}

/** The speed slider runs 0..1 and maps to 0.25x..20x on a curve. */
private const val SPEED_MIN = 0.25f
private const val SPEED_MAX = 20f

private fun sliderToSpeed(p: Float): Float {
    val v = SPEED_MIN * (SPEED_MAX / SPEED_MIN).toDouble().pow(p.toDouble()).toFloat()
    return if (v < 3f) (v * 20).roundToInt() / 20f else (v * 4).roundToInt() / 4f
}

private fun speedToSlider(speed: Float): Float =
    (ln(speed.coerceIn(SPEED_MIN, SPEED_MAX) / SPEED_MIN) / ln(SPEED_MAX / SPEED_MIN)).coerceIn(0f, 1f)

private fun speedLabel(speed: Float) = if (speed < 3f) "%.2fx".format(speed) else "%.1fx".format(speed)

/** "Plays for" slider: 0..1 mapped to 0.1 s..20 s on a curve, so short times are easy to set. */
private const val SECONDS_MIN = 0.1f
private const val SECONDS_MAX = 20f

private fun sliderToSeconds(p: Float): Float {
    val v = SECONDS_MIN * (SECONDS_MAX / SECONDS_MIN).toDouble().pow(p.toDouble()).toFloat()
    return when {
        v < 1f -> (v * 20).roundToInt() / 20f
        v < 5f -> (v * 4).roundToInt() / 4f
        else -> v.roundToInt().toFloat()
    }.coerceIn(SECONDS_MIN, SECONDS_MAX)
}

private fun secondsToSlider(seconds: Float): Float =
    (ln(seconds.coerceIn(SECONDS_MIN, SECONDS_MAX) / SECONDS_MIN) / ln(SECONDS_MAX / SECONDS_MIN)).coerceIn(0f, 1f)

private fun secondsLabel(seconds: Float) = when {
    seconds < 1f -> "%.2f s".format(seconds)
    seconds < 5f -> "%.2f s".format(seconds).replace(Regex("\\.?0+ s$"), " s")
    else -> "${seconds.roundToInt()} s"
}
