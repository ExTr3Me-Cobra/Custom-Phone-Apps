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
import androidx.compose.foundation.layout.padding
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
 * Developer Mode only: live CPU use (top left) while a scan runs, and GPU use (top right) where
 * the phone lets apps read it.
 * CPU is the app's own share of all cores (apps can't read the whole system's since Android 8,
 * and the app is the load during a scan), from the process's total CPU time (1 ms steps), sampled 5
 * times a second and lightly smoothed so it's steady but quick.
 * GPU comes from the GPU driver's busy counter (Adreno's kgsl or Mali's utilization file); many
 * phones hide it from apps, and then the circle isn't shown.
 */
@Composable
fun DeviceGauges() {
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    var cpu by remember { mutableStateOf(0f) }
    var gpu by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        val gpuFile = withContext(Dispatchers.IO) { gpuSource() }
        var lastCpu = cpuNanos(); var lastWall = SystemClock.elapsedRealtimeNanos()
        var smooth = -1f
        while (true) {
            delay(200)
            val c = cpuNanos(); val w = SystemClock.elapsedRealtimeNanos()
            val now = ((c - lastCpu).toDouble() / ((w - lastWall).coerceAtLeast(1) * cores.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
            lastCpu = c; lastWall = w
            // A light blend with the last reading: steady, and still within about half a second.
            smooth = if (smooth < 0) now else smooth * 0.5f + now * 0.5f
            cpu = smooth
            if (gpuFile != null) gpu = withContext(Dispatchers.IO) { readGpu(gpuFile) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Gauge(cpu / 100f, "${cpu.toInt()}%", "CPU")
        Spacer(Modifier.weight(1f))
        gpu?.let { Gauge(it / 100f, "${it.toInt()}%", "GPU") }
    }
}

/** The process's CPU time (all threads), in nanoseconds (counted in 1 ms steps). */
private fun cpuNanos(): Long = Process.getElapsedCpuTime() * 1_000_000L

/** A readable GPU busy counter, if the phone exposes one to apps. */
private fun gpuSource(): File? = listOf(
    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", // Adreno (Snapdragon): "37 %"
    "/sys/class/kgsl/kgsl-3d0/gpubusy", // Adreno: "busy total"
    "/sys/kernel/gpu/gpu_busy", // some Samsung / Qualcomm builds
    "/sys/devices/platform/mali.0/utilization",
    "/sys/class/misc/mali0/device/utilization", // Mali (Exynos, Tensor): 0-256 or %
).map { File(it) }.firstOrNull { f -> runCatching { readGpu(f) != null }.getOrDefault(false) }

/** GPU busy in percent from [f], or null if it can't be read. */
private fun readGpu(f: File): Float? {
    val t = runCatching { f.readText().trim() }.getOrNull() ?: return null
    val nums = Regex("\\d+").findAll(t).map { it.value.toLong() }.toList()
    return when {
        f.name == "gpubusy" && nums.size >= 2 -> if (nums[1] > 0) nums[0] * 100f / nums[1] else 0f
        f.name == "utilization" && nums.isNotEmpty() -> (if (nums[0] > 100) nums[0] * 100f / 256f else nums[0].toFloat())
        nums.isNotEmpty() -> nums[0].toFloat()
        else -> null
    }?.coerceIn(0f, 100f)
}

@Composable
private fun Gauge(fraction: Float, value: String, label: String, small: Boolean = false) {
    val color = when {
        fraction < 0.5f -> Color(0xFF2E9E57)
        fraction < 0.8f -> Color(0xFFE0A100)
        else -> Color(0xFFD93025)
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val d = if (small) 40.dp else 68.dp
    Box(Modifier.size(d), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(d).padding(if (small) 2.dp else 0.dp)) {
            val sw = (if (small) 4 else 7).dp.toPx()
            drawArc(track, 135f, 270f, false, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = if (small) 11.sp else 15.sp)
            Text(label, fontSize = if (small) 7.sp else 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

