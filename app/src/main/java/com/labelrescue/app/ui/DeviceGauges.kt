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
 * Developer Mode only: live CPU use while a scan runs. The big circle is the app's share of all
 * cores; the small ones are each core. Apps can't read the whole system's CPU use (since Android
 * 8), but they can read their own threads: each thread's CPU time and the core it last ran on
 * (/proc/self/task/<tid>/stat), which gives the app's use of each core. During a scan the app is
 * nearly all the load.
 */
@Composable
fun DeviceGauges() {
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    var cpu by remember { mutableStateOf(0f) }
    var perCore by remember { mutableStateOf(FloatArray(cores)) }
    LaunchedEffect(Unit) {
        var lastCpu = Process.getElapsedCpuTime(); var lastWall = SystemClock.elapsedRealtime()
        var lastTicks = withContext(Dispatchers.IO) { threadTicks() }
        while (true) {
            delay(800)
            val c = Process.getElapsedCpuTime(); val w = SystemClock.elapsedRealtime()
            val ticks = withContext(Dispatchers.IO) { threadTicks() }
            val dt = (w - lastWall).coerceAtLeast(1)
            cpu = ((c - lastCpu).toFloat() / (dt * cores) * 100f).coerceIn(0f, 100f)
            // Per core: CPU ticks (1/100 s) each thread used since last time, on the core it ran on.
            val use = FloatArray(cores)
            for ((tid, t) in ticks) {
                val prev = lastTicks[tid]?.first ?: continue
                val core = t.second
                if (core in 0 until cores) use[core] += (t.first - prev).coerceAtLeast(0L).toFloat()
            }
            perCore = FloatArray(cores) { (use[it] / (dt / 10f) * 100f).coerceIn(0f, 100f) }
            lastCpu = c; lastWall = w; lastTicks = ticks
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Gauge(cpu / 100f, "${cpu.toInt()}%", "CPU")
        Spacer(Modifier.weight(1f))
        // The cores, in rows of four.
        Column(horizontalAlignment = Alignment.End) {
            for (row in 0 until (cores + 3) / 4) Row {
                for (k in row * 4 until minOf(cores, row * 4 + 4)) Gauge(perCore[k] / 100f, "${perCore[k].toInt()}", "core $k", small = true)
            }
        }
    }
}

/** For each of the app's threads: (CPU ticks used so far, core it last ran on). */
private fun threadTicks(): Map<String, Pair<Long, Int>> {
    val out = HashMap<String, Pair<Long, Int>>()
    for (t in File("/proc/self/task").listFiles().orEmpty()) {
        val line = runCatching { File(t, "stat").readText() }.getOrNull() ?: continue
        // Fields after the thread name (which is in parentheses and can contain spaces).
        val f = line.substringAfterLast(')').trim().split(' ')
        // utime and stime are fields 14 and 15, the last core field 39 (here 11, 12 and 36).
        val ticks = (f.getOrNull(11)?.toLongOrNull() ?: continue) + (f.getOrNull(12)?.toLongOrNull() ?: 0)
        val core = f.getOrNull(36)?.toIntOrNull() ?: continue
        out[t.name] = ticks to core
    }
    return out
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

