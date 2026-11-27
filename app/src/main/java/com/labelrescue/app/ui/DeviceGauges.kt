package com.labelrescue.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Developer Mode only: live CPU use (top left) and temperature (top right) while a scan runs.
 * CPU is the app's own share of all cores (apps can't read the whole system's since Android 8,
 * and the app is the load during a scan). Temperature is the CPU's where the phone lets apps read
 * a thermal sensor, else the battery's (always readable, and it tracks the CPU closely).
 */
@Composable
fun DeviceGauges() {
    val ctx = LocalContext.current
    var cpu by remember { mutableStateOf(0f) }
    var temp by remember { mutableStateOf<Float?>(null) }
    var tempLabel by remember { mutableStateOf("°C") }
    LaunchedEffect(Unit) {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        var lastCpu = Process.getElapsedCpuTime(); var lastWall = SystemClock.elapsedRealtime()
        val zones = withContext(Dispatchers.IO) { cpuZones() }
        while (true) {
            delay(700)
            val c = Process.getElapsedCpuTime(); val w = SystemClock.elapsedRealtime()
            if (w > lastWall) cpu = ((c - lastCpu).toFloat() / ((w - lastWall) * cores) * 100f).coerceIn(0f, 100f)
            lastCpu = c; lastWall = w
            val t = withContext(Dispatchers.IO) { readTemp(ctx, zones) }
            temp = t?.first; tempLabel = t?.second ?: "°C"
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Gauge(cpu / 100f, "${cpu.toInt()}%", "CPU")
        Spacer(Modifier.weight(1f))
        val t = temp
        // 25 °C empty, 50 °C full (phones throttle in the 40s).
        Gauge(if (t == null) 0f else ((t - 25f) / 25f).coerceIn(0f, 1f), if (t == null) "–" else "${t.toInt()}°", tempLabel)
    }
}

@Composable
private fun Gauge(fraction: Float, value: String, label: String) {
    val color = when {
        fraction < 0.5f -> Color(0xFF2E9E57)
        fraction < 0.8f -> Color(0xFFE0A100)
        else -> Color(0xFFD93025)
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(68.dp)) {
            val sw = 7.dp.toPx()
            drawArc(track, 135f, 270f, false, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Readable CPU thermal sensors (often hidden from apps on newer phones). */
private fun cpuZones(): List<File> = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().filter { z ->
    val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: return@filter false
    ("cpu" in type || "tsens" in type || "soc" in type) && runCatching { File(z, "temp").readText().trim().toFloat() }.isSuccess
}

/** (°C, label): the hottest readable CPU sensor, else the battery. */
private fun readTemp(ctx: Context, zones: List<File>): Pair<Float, String>? {
    val cpu = zones.mapNotNull { z -> runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull() }
        .map { if (it > 1000) it / 1000f else it }.filter { it in 10f..120f }.maxOrNull()
    if (cpu != null) return cpu to "CPU °C"
    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: return null
    return if (b == Int.MIN_VALUE) null else b / 10f to "Batt °C"
}
