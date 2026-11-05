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
    val warmth: Float,
    /** 0..1 how much of the photo's own lighting is taken out first. */
    val remove: Float = 0f
)

/**
 * Relight: a light placed where you tap, which lights the photo by its shape (the surfaces facing
 * it brighten, those turned away less), worked out from Depth Anything's depth map.
 */
class RelightTool(private val preview: Bitmap, private val depth: DepthAnything.Depth) : StudioTool {
    override val stepName = "Relight"

    var light by mutableStateOf(Offset(0.3f, 0.3f))
    var strength by mutableFloatStateOf(0f)
    var size by mutableFloatStateOf(0.5f)
    var darken by mutableFloatStateOf(0f)
    var warmth by mutableFloatStateOf(0f)
    /** Remove existing light: ready at 70%, acting once there's a light (or it's moved by hand). */
    var removeLight by mutableFloatStateOf(0.7f)
    private var removeTouched by mutableStateOf(false)
    private var shown by mutableStateOf<Bitmap?>(null)

    private fun settings() = LightSettings(
        light.x, light.y, strength, size, darken, warmth,
        remove = if (strength > 0f || removeTouched) removeLight else 0f
    )

    // Surface directions from the depth map (smoothed first so tiny bumps don't count).
    private val w = depth.width
    private val h = depth.height
    private val nx = FloatArray(w * h)
    private val ny = FloatArray(w * h)
    private val nz = FloatArray(w * h)
    private val z = FloatArray(w * h)

    private val slope = FloatArray(w * h)

    /** The photo's own lighting at each depth-map pixel (fitted to its surfaces), for removing it. */
    private val existing: FloatArray

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
            var sx = 0.5f * (rr - l) / (2f / w) / aspect
            var sy = 0.5f * (d - u) / (2f / h)
            slope[i] = sqrt(sx * sx + sy * sy)
            val len = sqrt(sx * sx + sy * sy + 1f)
            nx[i] = -sx / len
            ny[i] = -sy / len
            nz[i] = 1f / len
        }
        // Steep jumps in depth are outlines (one thing in front of another), not surfaces: they're
        // made flat, so the light doesn't glow round the edges of things.
        val edge = FloatArray(w * h) { ((slope[it] - 1.5f) / 1.5f).coerceIn(0f, 1f) }
        val grown = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) m = max(m, edge[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)])
            grown[y * w + x] = m
        }
        for (i in 0 until w * h) {
            if (grown[i] <= 0f) continue
            val k = 1f - grown[i]
            var ax = nx[i] * k
            var ay = ny[i] * k
            val az = nz[i]
            val len = sqrt(ax * ax + ay * ay + az * az)
            nx[i] = ax / len; ny[i] = ay / len; nz[i] = az / len
        }
        existing = fitLighting()
    }

    /**
     * The photo's own lighting: its brightness fitted as a + b·(surface direction) over the whole
     * photo (one soft light and fill, the usual case), which follows shape but not colours.
     */
    private fun fitLighting(): FloatArray {
        val small = Bitmap.createScaledBitmap(preview, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== preview) small.recycle()
        val m = Array(4) { DoubleArray(4) }
        val v = DoubleArray(4)
        for (i in 0 until w * h) {
            val c = px[i]
            val l = (0.299 * c.red8 + 0.587 * c.green8 + 0.114 * c.blue8) / 255.0
            if (l < 0.03 || l > 0.97) continue
            val f = doubleArrayOf(1.0, nx[i].toDouble(), ny[i].toDouble(), nz[i].toDouble())
            for (a in 0..3) {
                v[a] += f[a] * l
                for (b in 0..3) m[a][b] += f[a] * f[b]
            }
        }
        val c = solve4(m, v) ?: return FloatArray(w * h) { 1f }
        val s = FloatArray(w * h) { i -> (c[0] + c[1] * nx[i] + c[2] * ny[i] + c[3] * nz[i]).toFloat() }
        return boxBlur(boxBlur(s, w, h, 2), w, h, 2)
    }

    /** Solves the 4×4 system [m]·x = [v] (null if it can't be). */
    private fun solve4(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val a = Array(4) { r -> DoubleArray(5) { c -> if (c < 4) m[r][c] else v[r] } }
        for (col in 0..3) {
            val p = (col..3).maxBy { kotlin.math.abs(a[it][col]) }
            if (kotlin.math.abs(a[p][col]) < 1e-9) return null
            val t = a[p]; a[p] = a[col]; a[col] = t
            for (r in 0..3) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (c in col..4) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(4) { a[it][4] / a[it][it] }
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
        if (s.strength <= 0f && s.remove <= 0f && s.darken <= 0f) return image
        // The light, and how much the photo's own lighting is evened out, worked out at the
        // depth map's size, then enlarged so they follow the photo's own edges (a guided filter
        // on a copy of at most 1600 px), so nothing spills past the outline of things.
        val shade = shading(s)
        val mean = existing.average().toFloat()
        val ratio = FloatArray(w * h) { i -> (mean / max(1e-3f, existing[i])).coerceIn(0.6f, 2.2f).let { r -> if (s.remove <= 0f) 1f else kotlin.math.exp(kotlin.math.ln(r) * s.remove) } }
        val guide = image.scaledToFit(1600)
        val gw = guide.width
        val gh = guide.height
        val gpx = IntArray(gw * gh)
        guide.getPixels(gpx, 0, gw, 0, 0, gw, gh)
        if (guide !== image) guide.recycle()
        val grey = FloatArray(gw * gh) { (0.299f * gpx[it].red8 + 0.587f * gpx[it].green8 + 0.114f * gpx[it].blue8) / 255f }
        val gShade = guided(grey, enlarge(shade, gw, gh), gw, gh)
        val gRatio = guided(grey, enlarge(ratio, gw, gh), gw, gh)
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val ambient = 1f - s.darken * 0.6f
        val cr = 1f + 0.2f * s.warmth
        val cg = 1f + 0.04f * s.warmth
        val cb = 1f - 0.2f * s.warmth
        return image.mapPixels { x, y, c ->
            val fx = ((x + 0.5f) / iw * gw - 0.5f).coerceIn(0f, gw - 1f)
            val fy = ((y + 0.5f) / ih * gh - 0.5f).coerceIn(0f, gh - 1f)
            val sh = max(0f, sample(gShade, gw, gh, fx, fy))
            val keep = max(0.3f, sample(gRatio, gw, gh, fx, fy))
            val lit = s.strength * sh
            val f = (ambient + 1.6f * lit) * keep
            val glow = 22f * lit
            rgb(c.red8 * f * (1 + (cr - 1) * lit * 2) + glow * cr, c.green8 * f * (1 + (cg - 1) * lit * 2) + glow * cg, c.blue8 * f * (1 + (cb - 1) * lit * 2) + glow * cb)
        }
    }

    /** [a] (depth-map size) enlarged to [tw]×[th] (bilinear). */
    private fun enlarge(a: FloatArray, tw: Int, th: Int): FloatArray = FloatArray(tw * th) { i ->
        val fx = (((i % tw) + 0.5f) / tw * w - 0.5f).coerceIn(0f, w - 1f)
        val fy = (((i / tw) + 0.5f) / th * h - 0.5f).coerceIn(0f, h - 1f)
        sample(a, w, h, fx, fy)
    }

    private fun sample(a: FloatArray, aw: Int, ah: Int, fx: Float, fy: Float): Float {
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, aw - 1)
        val y1 = min(y0 + 1, ah - 1)
        val ax = fx - x0
        val ay = fy - y0
        return (a[y0 * aw + x0] * (1 - ax) + a[y0 * aw + x1] * ax) * (1 - ay) + (a[y1 * aw + x0] * (1 - ax) + a[y1 * aw + x1] * ax) * ay
    }

    /** [p] smoothed so its changes line up with the edges of [guide] (He et al.'s guided filter). */
    private fun guided(guide: FloatArray, p: FloatArray, gw: Int, gh: Int): FloatArray {
        val r = max(4, (max(gw, gh) * 0.012f).roundToInt())
        val eps = 2e-3f
        val n = gw * gh
        val mI = boxBlur(guide, gw, gh, r)
        val mp = boxBlur(p, gw, gh, r)
        val ip = boxBlur(FloatArray(n) { guide[it] * p[it] }, gw, gh, r)
        val ii = boxBlur(FloatArray(n) { guide[it] * guide[it] }, gw, gh, r)
        val a = FloatArray(n) { (ip[it] - mI[it] * mp[it]) / (ii[it] - mI[it] * mI[it] + eps) }
        val b = FloatArray(n) { mp[it] - a[it] * mI[it] }
        val ma = boxBlur(a, gw, gh, r)
        val mb = boxBlur(b, gw, gh, r)
        return FloatArray(n) { ma[it] * guide[it] + mb[it] }
    }

    /** An even average over (2r+1)² around each pixel. */
    private fun boxBlur(src: FloatArray, bw: Int, bh: Int, r: Int): FloatArray {
        val tmp = FloatArray(bw * bh)
        val out = FloatArray(bw * bh)
        val k = 2 * r + 1
        for (y in 0 until bh) {
            val row = y * bw
            var sum = 0f
            for (x in -r..r) sum += src[row + x.coerceIn(0, bw - 1)]
            for (x in 0 until bw) {
                tmp[row + x] = sum / k
                sum += src[row + min(bw - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
        for (x in 0 until bw) {
            var sum = 0f
            for (y in -r..r) sum += tmp[y.coerceIn(0, bh - 1) * bw + x]
            for (y in 0 until bh) {
                out[y * bw + x] = sum / k
                sum += tmp[min(bh - 1, y + r + 1) * bw + x] - tmp[max(0, y - r) * bw + x]
            }
        }
        return out
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
            original = small,
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
            StudioHint("Tap the photo to move the light. Remove light evens out the photo's own lighting first.")
            LabeledSlider("Light ${(strength * 100).roundToInt()}%", strength, 0f..1f) { strength = it }
            LabeledSlider("Remove light ${(removeLight * 100).roundToInt()}%", removeLight, 0f..1f) {
                removeLight = it
                removeTouched = true
            }
            LabeledSlider("Reach ${(size * 100).roundToInt()}%", size, 0f..1f) { size = it }
            LabeledSlider("Darken ${(darken * 100).roundToInt()}%", darken, 0f..1f) { darken = it }
            LabeledSlider(if (warmth >= 0) "Warm ${(warmth * 100).roundToInt()}" else "Cool ${(-warmth * 100).roundToInt()}", warmth, -1f..1f) { warmth = it }
        }
    }
}
