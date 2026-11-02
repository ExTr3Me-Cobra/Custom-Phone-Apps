package com.motiondots.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private val Accent = Color(0xFF5EC8FF)
private val Grey = Color(0xFF9AA0A6)
private val Card = Color(0xFF1A1D21)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) { Screen() }
        }
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    // Re-read permissions every time the app comes back to the front.
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    @Suppress("UNUSED_EXPRESSION") resumed
    val canOverlay = Settings.canDrawOverlays(context)
    val notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val a11yOn = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    var on by remember { mutableStateOf(prefs.on) }
    LaunchedEffect(resumed) { on = prefs.on }
    var useA11y by remember { mutableStateOf(prefs.useAccessibility) }
    val ready = canOverlay || (useA11y && a11yOn)

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Motion Dots", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Dots at the edges of the screen move with the vehicle, like the scenery outside, so your eyes " +
                "see the motion your inner ear feels. For passengers facing forward, never the driver.",
            color = Grey, fontSize = 14.sp
        )
        var crash by remember { mutableStateOf(prefs.sp.getString("crash", null)) }
        crash?.let { report ->
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(Color(0xFF3A1414), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Motion Dots crashed last time", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(report.lines().take(8).joinToString("\n"), color = Grey, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Motion Dots crash", report))
                    }) { Text("Copy", color = Color.White) }
                    OutlinedButton(onClick = { prefs.sp.edit().remove("crash").apply(); crash = null }) { Text("Clear", color = Color.White) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (!on && !notifications) {
                    (context as ComponentActivity).requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                on = !on
                DotsService.set(context, on)
            },
            enabled = ready || on,
            colors = ButtonDefaults.buttonColors(containerColor = if (on) Color(0xFF3A3F45) else Accent, contentColor = if (on) Color.White else Color.Black),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) { Text(if (on) "Turn dots off" else "Turn dots on", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        if (on) DotsStatus()
        Spacer(Modifier.height(6.dp))
        Text("Tip: tap the Motion Dots app icon to switch them on or off, or add the \"Motion dots\" tile in Quick Settings.", color = Grey, fontSize = 13.sp)

        Section("Setup")
        Check("Display over other apps", canOverlay, "Needed for the dots.") {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
        Check("Notifications", notifications, "Shows a quiet \"dots are on\" notification with Turn off.") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        Toggle(
            "Also over the status bar, notifications and Settings",
            "Uses an accessibility service: a normal overlay can't cover those screens and is limited to 80% " +
                "opacity. Turn on Motion Dots in Accessibility (on Samsung: Installed apps). If it's greyed out, open " +
                "App info → ⋮ → Allow restricted settings first.",
            useA11y
        ) { useA11y = it; prefs.useAccessibility = it }
        if (useA11y) {
            Check("Accessibility service", a11yOn, if (a11yOn) "Drawing over everything." else "Not on yet.") {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        Section("Look")
        ColorPicker(prefs)
        SliderRow("Dot size", prefs.size, 4f..20f, { "${it.toInt()} dp" }) { prefs.size = it }
        SliderRow("Gap between dots (fewer ← → more)", prefs.spacing, 30f..140f, { "${it.toInt()} dp" }, reverse = true) { prefs.spacing = it }
        SliderRow("Distance from edge", prefs.inset, 2f..60f, { "${it.toInt()} dp" }) { prefs.inset = it }
        SliderRow("Opacity", prefs.opacity, 0.2f..1f, { "${(it * 100).toInt()}%" + if (!useA11y && it > 0.8f) " (80% max as a normal overlay)" else "" }) { prefs.opacity = it }
        Choice("Where", listOf("Left and right edges", "All four edges"), prefs.layout) { prefs.layout = it }

        Section("Motion")
        SliderRow("Sensitivity", prefs.sensitivity, 0.3f..3f, { "×${"%.1f".format(it)}" }) { prefs.sensitivity = it }
        SliderRow("Travel before new dots appear", prefs.travel, 15f..150f, { "${it.toInt()} dp" }) { prefs.travel = it }
        Text("A set of dots fades out after moving this far and a fresh set fades in at the edge, so they never slide into the middle.", color = Grey, fontSize = 12.sp)
        SliderRow("Smoothing", prefs.smoothing, 0f..1f, { if (it < 0.2f) "snappy" else if (it > 0.7f) "very smooth" else "medium" }) { prefs.smoothing = it }
        var hide by remember { mutableStateOf(prefs.hideWhenStill) }
        Toggle("Hide when not moving", "Fades the dots out after a while without vehicle motion and back in when it starts.", hide) {
            hide = it; prefs.hideWhenStill = it
        }
        if (hide) SliderRow("Fade out after", prefs.hideAfter, 3f..120f, { "${it.toInt()} s" }) { prefs.hideAfter = it.toInt().toFloat() }
        var rev by remember { mutableStateOf(prefs.reversed) }
        Toggle("Facing backwards", "Flips the direction, for a rear-facing seat (e.g. on a train).", rev) {
            rev = it; prefs.reversed = it
        }

        Section("Live motion")
        LiveMotion()
        Spacer(Modifier.height(30.dp))
    }
}

/** Whether the dots on screen are getting motion readings. */
@Composable
private fun DotsStatus() {
    var text by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        var last = -1L
        while (true) {
            val m = Motion.active
            if (m == null) { text = "Dots aren't showing (check Setup)"; ok = false }
            else {
                val perSec = if (last < 0) 0 else ((m.readings - last) * 2)
                last = m.readings
                val fresh = android.os.SystemClock.elapsedRealtime() - m.lastReadingAt < 1500
                ok = fresh
                text = if (fresh) "Dots are getting motion readings ($perSec/s)" else "Dots are NOT getting motion readings" +
                    if (m.restarts > 0) " (restarted the sensors ${m.restarts}×)" else ""
            }
            delay(500)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text((if (ok) "✓ " else "✗ ") + text, color = if (ok) Color(0xFF69F0AE) else Color(0xFFFF6E6E), fontSize = 13.sp)
}

@Composable
private fun LiveMotion() {
    val context = LocalContext.current
    val motion = remember { Motion(context) }
    DisposableEffect(Unit) { motion.start(); onDispose { motion.stop() } }
    var f by remember { mutableFloatStateOf(0f) }
    var r by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            f = motion.forward.toFloat(); r = motion.right.toFloat()
            delay(100)
        }
    }
    Text(
        "Forward ${"%+.2f".format(f)} m/s² (${if (f > 0.3) "speeding up" else if (f < -0.3) "braking" else "steady"})\n" +
            "Sideways ${"%+.2f".format(r)} m/s² (${if (r > 0.3) "turning right" else if (r < -0.3) "turning left" else "straight"})",
        color = Color.White, fontSize = 14.sp
    )
    Text(
        "Dots move opposite: down when speeding up, up when braking, right in a left turn, left in a right turn.",
        color = Grey, fontSize = 13.sp
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Check(title: String, ok: Boolean, detail: String, fix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Card, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ok) "✓" else "✗", color = if (ok) Color(0xFF69F0AE) else Color(0xFFFF6E6E), fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(detail, color = Grey, fontSize = 12.sp)
        }
        if (!ok) OutlinedButton(onClick = fix) { Text("Fix", color = Color.White) }
    }
}

@Composable
private fun Toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(detail, color = Grey, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black))
    }
}

@Composable
private fun SliderRow(
    title: String, initial: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String,
    reverse: Boolean = false, save: (Float) -> Unit
) {
    var v by remember { mutableFloatStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(label(v), color = Grey, fontSize = 13.sp)
    }
    // "reverse": the slider runs the other way (right = smaller value = more dots).
    val shown = if (reverse) range.start + range.endInclusive - v else v
    Slider(
        value = shown,
        onValueChange = { s -> v = if (reverse) range.start + range.endInclusive - s else s; save(v) },
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
    )
}

@Composable
private fun Choice(title: String, options: List<String>, initial: Int, save: (Int) -> Unit) {
    var sel by remember { mutableIntStateOf(initial) }
    Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val picked = i == sel
            Box(
                Modifier.weight(1f).background(if (picked) Accent else Card, RoundedCornerShape(10.dp))
                    .clickable { sel = i; save(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text(o, color = if (picked) Color.Black else Color.White, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun ColorPicker(prefs: Prefs) {
    var sel by remember { mutableIntStateOf(prefs.color) }
    Text("Colour (with an outline so they show on light and dark screens)", color = Color.White, fontSize = 15.sp)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Prefs.COLORS.forEachIndexed { i, (name, fill, outline) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { sel = i; prefs.color = i }) {
                Box(
                    Modifier.size(34.dp)
                        .border(if (i == sel) 3.dp else 0.dp, if (i == sel) Accent else Color.Transparent, CircleShape)
                        .padding(5.dp).background(Color(outline), CircleShape).padding(2.dp).background(Color(fill), CircleShape)
                )
                Text(name, color = Grey, fontSize = 11.sp)
            }
        }
    }
}
