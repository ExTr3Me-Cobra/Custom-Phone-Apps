package com.vipervision.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.t8rin.neural_tools.depth.DepthAnything
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Relight's choices. */
data class LightSettings(
    val x: Float, val y: Float,
    /** 0..1 how bright the light is. */
    val strength: Float,
    /** 0..1 how far it reaches. */
    val size: Float,
    /** 0..1 how much darker everything else gets. */
    val darken: Float,
    /** -1 (cool, blue) .. 1 (warm, orange). */
    val warmth: Float
)

/**
 * Relight: a light placed where you tap, which lights the photo by its shape (the surfaces facing
 * it brighten, those turned away less), worked out from Depth Anything's depth map.
 */
class RelightTool(private val preview: Bitmap, private val depth: DepthAnything.Depth) : StudioTool {
    override val stepName = "Relight"

    var light by mutableStateOf(Offset(0.3f, 0.3f))
    var strength by mutableFloatStateOf(0.6f)
    var size by mutableFloatStateOf(0.5f)
    var darken by mutableFloatStateOf(0.3f)
    var warmth by mutableFloatStateOf(0.3f)
    private var shown by mutableStateOf<Bitmap?>(null)

    private fun settings() = LightSettings(light.x, light.y, strength, size, darken, warmth)

    // Surface directions from the depth map (smoothed first so tiny bumps don't count).
    private val w = depth.width
    private val h = depth.height
    private val nx = FloatArray(w * h)
    private val ny = FloatArray(w * h)
    private val nz = FloatArray(w * h)
    private val z = FloatArray(w * h)

    init {
        val smooth = FloatArray(w * h)
        val r = 2
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                s += depth.values[yy * w + xx]
                n++
            }
            smooth[y * w + x] = s / n
        }
        val aspect = w.toFloat() / h
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            z[i] = smooth[i] * 0.5f
            val l = smooth[y * w + max(0, x - 1)]
            val rr = smooth[y * w + min(w - 1, x + 1)]
            val u = smooth[max(0, y - 1) * w + x]
            val d = smooth[min(h - 1, y + 1) * w + x]
            // Slope of closeness against position (in the same units as x across the photo).
            val sx = 0.5f * (rr - l) / (2f / w) / aspect
            val sy = 0.5f * (d - u) / (2f / h)
            val len = sqrt(sx * sx + sy * sy + 1f)
            nx[i] = -sx / len
            ny[i] = -sy / len
            nz[i] = 1f / len
        }
    }

    /** How lit each depth-map pixel is (0..1). */
    private fun shading(s: LightSettings): FloatArray {
        val aspect = w.toFloat() / h
        val out = FloatArray(w * h)
        val lx = s.x * aspect
        val ly = s.y
        val lz = 0.5f + 0.35f
        val reach = 0.15f + 0.85f * s.size
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val px = (x + 0.5f) / w * aspect
            val py = (y + 0.5f) / h
            var vx = lx - px
            var vy = ly - py
            var vz = lz - z[i]
            val flat = sqrt(vx * vx + vy * vy)
            val len = max(1e-5f, sqrt(vx * vx + vy * vy + vz * vz))
            vx /= len; vy /= len; vz /= len
            val facing = max(0f, nx[i] * vx + ny[i] * vy + nz[i] * vz)
            val falloff = exp(-(flat / reach) * (flat / reach))
            // Nearer things catch more of the light, like a light held in front of the scene.
            val near = 0.6f + 0.4f * (z[i] * 2f)
            out[i] = facing * falloff * near
        }
        return out
    }

    private suspend fun render(image: Bitmap, s: LightSettings): Bitmap {
        val shade = shading(s)
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val ambient = 1f - s.darken * 0.6f
        val cr = 1f + 0.2f * s.warmth
        val cg = 1f + 0.04f * s.warmth
        val cb = 1f - 0.2f * s.warmth
        return image.mapPixels { x, y, c ->
            // Bilinear look-up of the shading.
            val fx = ((x + 0.5f) / iw * w - 0.5f).coerceIn(0f, w - 1f)
            val fy = ((y + 0.5f) / ih * h - 0.5f).coerceIn(0f, h - 1f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = min(x0 + 1, w - 1)
            val y1 = min(y0 + 1, h - 1)
            val ax = fx - x0
            val ay = fy - y0
            val sh = (shade[y0 * w + x0] * (1 - ax) + shade[y0 * w + x1] * ax) * (1 - ay) +
                (shade[y1 * w + x0] * (1 - ax) + shade[y1 * w + x1] * ax) * ay
            val lit = s.strength * sh
            val f = ambient + 1.6f * lit
            val glow = 22f * lit
            rgb(c.red8 * f * (1 + (cr - 1) * lit * 2) + glow * cr, c.green8 * f * (1 + (cg - 1) * lit * 2) + glow * cg, c.blue8 * f * (1 + (cb - 1) * lit * 2) + glow * cb)
        }
    }

    override suspend fun render(context: Context, full: Bitmap): Bitmap = render(full, settings())

    @Composable
    override fun Screen(onCancel: () -> Unit, onApply: () -> Unit) {
        val small = remember { preview.scaledToFit(1080) }
        LaunchedEffect(Unit) {
            snapshotFlow { settings() }.conflate().collect { s ->
                shown = withContext(Dispatchers.Default) { render(small, s) }
            }
        }
        StudioFrame(
            title = "Relight",
            shown = shown,
            photoWidth = preview.width,
            photoHeight = preview.height,
            onCancel = onCancel,
            onApply = onApply,
            onTap = { light = it },
            overlay = { rect ->
                val c = Offset(rect.left + light.x * rect.width, rect.top + light.y * rect.height)
                drawCircle(Color.Black.copy(alpha = 0.35f), 16.dp.toPx(), c, style = Stroke(4.dp.toPx()))
                drawCircle(Color(0xFFFFD54F), 16.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                drawCircle(Color(0xFFFFD54F), 6.dp.toPx(), c)
            }
        ) {
            StudioHint("Tap the photo to move the light.")
            LabeledSlider("Light ${(strength * 100).roundToInt()}%", strength, 0f..1f) { strength = it }
            LabeledSlider("Reach ${(size * 100).roundToInt()}%", size, 0f..1f) { size = it }
            LabeledSlider("Darken ${(darken * 100).roundToInt()}%", darken, 0f..1f) { darken = it }
            LabeledSlider(if (warmth >= 0) "Warm ${(warmth * 100).roundToInt()}" else "Cool ${(-warmth * 100).roundToInt()}", warmth, -1f..1f) { warmth = it }
        }
    }
}
