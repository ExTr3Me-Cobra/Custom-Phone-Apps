package com.vipervision.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Done
import com.t8rin.neural_tools.depth.DepthAnything
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the out-of-focus parts look. */
enum class BlurType(val label: String) {
    /** A camera lens: bright spots become round discs (bokeh). */
    Lens("Lens"),

    /** A soft, even blur. */
    Smooth("Smooth"),

    /** Streaks in one direction, like a panning shot. */
    Motion("Motion"),

    /** Streaks out from the focus point, like zooming during the shot. */
    Zoom("Zoom")
}

/** One set of Portrait blur choices (plain values, safe to hand to another thread). */
data class BlurSettings(
    val type: BlurType,
    /** 0..1: how blurry the farthest-from-focus parts get. */
    val strength: Float,
    /** 0 (far) .. 1 (near): the distance in focus. */
    val focusDepth: Float,
    /** 0..1: how much in front of and behind the focus also stays sharp. */
    val depthOfField: Float,
    /** Keeps the found subject crisp to its edges (the depth map alone is soft at edges). */
    val keepSubject: Boolean,
    /** 0..1: Lens only, how much bright spots glow into discs. */
    val highlights: Float,
    /** Degrees: Motion only, the streaks' direction. */
    val angle: Float,
    /** Fractions of the photo: Zoom's centre. */
    val focusX: Float,
    val focusY: Float
)

/** The subject's cut-out as opacity (0..255) at its own size, sampled by fractions of the photo. */
class Matte(val width: Int, val height: Int, private val alpha: ByteArray) {
    fun at(fx: Float, fy: Float): Float {
        val x = (fx * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (fy * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = x - x0
        val ay = y - y0
        fun a(i: Int) = (alpha[i].toInt() and 255) / 255f
        val top = a(y0 * width + x0) * (1 - ax) + a(y0 * width + x1) * ax
        val bottom = a(y1 * width + x0) * (1 - ax) + a(y1 * width + x1) * ax
        return top * (1 - ay) + bottom * ay
    }

    /** How much of the photo is subject (0..1). */
    val coverage: Float = alpha.count { (it.toInt() and 255) >= 128 }.toFloat() / max(1, alpha.size)

    companion object {
        fun of(subject: Bitmap): Matte {
            val px = IntArray(subject.width * subject.height)
            subject.getPixels(px, 0, subject.width, 0, 0, subject.width, subject.height)
            return Matte(subject.width, subject.height, ByteArray(px.size) { (px[it] ushr 24).toByte() })
        }
    }
}

/**
 * Portrait blur: each part of the photo blurred by how far it is from the focus distance, from
 * Depth Anything's depth map (like a real lens), with the subject's cut-out keeping its edges
 * crisp. Blurs are made at a few sizes on shrunk copies and mixed per pixel; each only gathers
 * pixels that are themselves blurry, so sharp things don't smear into the blur around them.
 */
object PortraitBlur {

    /** [image] blurred by [settings], with [depth] (and [matte] for the subject) laid over it. */
    suspend fun apply(image: Bitmap, depth: DepthAnything.Depth, matte: Matte?, settings: BlurSettings): Bitmap {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        // About 0.4% to 4% of the photo's long side at most.
        val maxRadius = max(w, h) * (0.004f + 0.036f * settings.strength.coerceIn(0f, 1f))
        val subject = if (settings.keepSubject) matte else null
        // Around a kept subject the depth map is soft (the background right next to it reads
        // nearly as near as the subject), so the background's depth is carried in up to and
        // under the subject's edge.
        val depthUsed = subject?.let { backgroundDepth(depth, it) } ?: depth
        val field = field(w, h, depthUsed, subject, settings)
        // Blur sizes, halving down to about a pixel and a half.
        val radii = ArrayList<Float>()
        var r = maxRadius
        while (r >= 1.5f && radii.size < 8) {
            radii.add(0, r)
            r /= 2f
        }
        if (radii.isNotEmpty()) {
            val gamma = if (settings.type == BlurType.Lens) 2.2f + 3f * settings.highlights.coerceIn(0f, 1f) else 2.2f
            val toLight = FloatArray(256) { (it / 255f).pow(gamma) }
            val levels = ArrayList<Level>(radii.size)
            for (radius in radii) levels += level(pixels, w, h, field, maxRadius, radius, settings, toLight, gamma, subject)
            compose(pixels, w, h, field, maxRadius, radii, levels)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    /**
     * [depth] with the subject (and a band around it) filled in from the background next to it,
     * spreading inwards ring by ring.
     */
    private fun backgroundDepth(depth: DepthAnything.Depth, matte: Matte): DepthAnything.Depth {
        val w = depth.width
        val h = depth.height
        val band = max(2, (max(w, h) * 0.025f).roundToInt())
        // Unknown: the subject, grown by the band.
        val subject = BooleanArray(w * h) { matte.at(((it % w) + 0.5f) / w, ((it / w) + 0.5f) / h) >= 0.2f }
        val unknown = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!subject[y * w + x]) continue
            for (dy in -band..band) for (dx in -band..band) {
                if (dx * dx + dy * dy > band * band) continue
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) unknown[yy * w + xx] = true
            }
        }
        val values = depth.values.copyOf()
        var left = unknown.count { it }
        if (left == 0 || left == w * h) return depth
        val next = BooleanArray(w * h)
        while (left > 0) {
            var changed = 0
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!unknown[i]) continue
                var sum = 0f
                var n = 0
                if (x > 0 && !unknown[i - 1]) { sum += values[i - 1]; n++ }
                if (x < w - 1 && !unknown[i + 1]) { sum += values[i + 1]; n++ }
                if (y > 0 && !unknown[i - w]) { sum += values[i - w]; n++ }
                if (y < h - 1 && !unknown[i + w]) { sum += values[i + w]; n++ }
                if (n > 0) {
                    values[i] = sum / n
                    next[i] = true
                    changed++
                }
            }
            if (changed == 0) break
            for (i in next.indices) if (next[i]) { unknown[i] = false; next[i] = false }
            left -= changed
        }
        return DepthAnything.Depth(w, h, values)
    }

    /** A grey picture of [depth] (near is light), for seeing what the AI thinks is near. */
    fun depthPicture(depth: DepthAnything.Depth): Bitmap {
        val px = IntArray(depth.values.size) {
            val v = (depth.values[it] * 255).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, depth.width, depth.height, Bitmap.Config.ARGB_8888)
    }

    /** The middle closeness of the subject (or null when there isn't one). */
    fun subjectDepth(depth: DepthAnything.Depth, matte: Matte): Float? {
        val values = ArrayList<Float>()
        for (y in 0 until depth.height) for (x in 0 until depth.width) {
            val fx = (x + 0.5f) / depth.width
            val fy = (y + 0.5f) / depth.height
            if (matte.at(fx, fy) >= 0.5f) values += depth.values[y * depth.width + x]
        }
        if (values.size < 8) return null
        values.sort()
        return values[values.size / 2]
    }

    /** The middle of the subject, in fractions of the photo. */
    fun subjectCentre(matte: Matte): Offset? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        val step = max(1, max(matte.width, matte.height) / 256)
        for (y in 0 until matte.height step step) for (x in 0 until matte.width step step) {
            val fx = (x + 0.5f) / matte.width
            val fy = (y + 0.5f) / matte.height
            if (matte.at(fx, fy) >= 0.5f) {
                sx += fx
                sy += fy
                n++
            }
        }
        return if (n == 0) null else Offset((sx / n).toFloat(), (sy / n).toFloat())
    }

    // ---- How blurry each pixel is ------------------------------------------------------------

    /** Per pixel, the blur as a fraction (0..255) of the biggest. */
    private suspend fun field(w: Int, h: Int, depth: DepthAnything.Depth, matte: Matte?, s: BlurSettings): ByteArray {
        val out = ByteArray(w * h)
        val band = 0.3f * s.depthOfField.coerceIn(0f, 1f)
        rows(h) { y ->
            val fy = (y + 0.5f) / h
            for (x in 0 until w) {
                val fx = (x + 0.5f) / w
                val distance = abs(depth.at(fx, fy) - s.focusDepth)
                // Blur grows with the distance (in depth-map units) past the sharp band, as with a lens.
                var t = ((distance - band) / FALLOFF).coerceIn(0f, 1f)
                if (matte != null) t *= 1f - matte.at(fx, fy)
                out[y * w + x] = (t * 255f).roundToInt().toByte()
            }
        }
        return out
    }

    // ---- One blur size --------------------------------------------------------------------------

    private class Level(val width: Int, val height: Int, val step: Int, val colors: IntArray)

    /**
     * The photo blurred by [radius] px: averaged down by a whole number of pixels so the blur is
     * only a few pixels there, blurred with the chosen kind of blur, kept small (it's enlarged
     * smoothly as it's mixed in).
     */
    private suspend fun level(
        pixels: IntArray, w: Int, h: Int, field: ByteArray, maxRadius: Float,
        radius: Float, s: BlurSettings, toLight: FloatArray, gamma: Float, subject: Matte?
    ): Level {
        val step = max(2, (radius / KERNEL).roundToInt())
        val sw = ceil(w / step.toFloat()).toInt()
        val sh = ceil(h / step.toFloat()).toInt()
        // Weighted light (premultiplied by weight), 4 values per small pixel: r, g, b, weight.
        val small = FloatArray(sw * sh * 4)
        val needed = 0.5f * radius
        rows(sh) { sy ->
            val y0 = sy * step
            val y1 = min(h, y0 + step)
            for (sx in 0 until sw) {
                val x0 = sx * step
                val x1 = min(w, x0 + step)
                var r = 0f
                var g = 0f
                var b = 0f
                var wt = 0f
                for (y in y0 until y1) {
                    var i = y * w + x0
                    for (x in x0 until x1) {
                        // With a kept subject: everything but the subject counts fully, the subject
                        // not at all (so it can't smear into the blur around it). Otherwise sharp
                        // pixels hardly count, for the same reason.
                        val k = if (subject != null) (1f - subject.at((x + 0.5f) / w, (y + 0.5f) / h)).let { it * it }.coerceAtLeast(1e-4f)
                        else ((field[i].toInt() and 255) / 255f * maxRadius / needed).coerceIn(MIN_WEIGHT, 1f)
                        val p = pixels[i]
                        r += k * toLight[p shr 16 and 255]
                        g += k * toLight[p shr 8 and 255]
                        b += k * toLight[p and 255]
                        wt += k
                        i++
                    }
                }
                val n = ((y1 - y0) * (x1 - x0)).toFloat()
                val o = (sy * sw + sx) * 4
                small[o] = r / n
                small[o + 1] = g / n
                small[o + 2] = b / n
                small[o + 3] = wt / n
            }
        }
        val kr = radius / step
        val colors = IntArray(sw * sh)
        val inverse = 1f / gamma
        when (s.type) {
            BlurType.Lens, BlurType.Smooth -> {
                // Taps on a grid within the radius: an even disc with a soft rim (Lens), or a bell curve.
                val reach = ceil(kr).toInt()
                val dxs = ArrayList<Int>()
                val dys = ArrayList<Int>()
                val ks = ArrayList<Float>()
                val sigma = max(0.5f, kr / 2f)
                for (dy in -reach..reach) for (dx in -reach..reach) {
                    val d = sqrt((dx * dx + dy * dy).toFloat())
                    val k = if (s.type == BlurType.Lens) (kr + 0.5f - d).coerceIn(0f, 1f)
                    else if (d <= kr + 0.5f) exp(-(d * d) / (2 * sigma * sigma)) else 0f
                    if (k > 0f) {
                        dxs += dx
                        dys += dy
                        ks += k
                    }
                }
                val tx = dxs.toIntArray()
                val ty = dys.toIntArray()
                val tk = ks.toFloatArray()
                rows(sh) { y ->
                    for (x in 0 until sw) {
                        var r = 0f
                        var g = 0f
                        var b = 0f
                        var wt = 0f
                        for (t in tk.indices) {
                            val xx = (x + tx[t]).coerceIn(0, sw - 1)
                            val yy = (y + ty[t]).coerceIn(0, sh - 1)
                            val o = (yy * sw + xx) * 4
                            val k = tk[t]
                            r += k * small[o]
                            g += k * small[o + 1]
                            b += k * small[o + 2]
                            wt += k * small[o + 3]
                        }
                        colors[y * sw + x] = colour(r, g, b, wt, inverse)
                    }
                }
            }

            BlurType.Motion, BlurType.Zoom -> {
                val angle = Math.toRadians(s.angle.toDouble())
                val ux = cos(angle).toFloat()
                val uy = sin(angle).toFloat()
                val cx = s.focusX * sw
                val cy = s.focusY * sh
                val far = max(sw, sh) * 0.35f
                rows(sh) { y ->
                    for (x in 0 until sw) {
                        var dx = ux
                        var dy = uy
                        var length = kr * 1.5f
                        if (s.type == BlurType.Zoom) {
                            // Along the line out from the centre, longer further out.
                            val vx = x + 0.5f - cx
                            val vy = y + 0.5f - cy
                            val d = hypot(vx, vy)
                            if (d < 1e-3f) {
                                dx = 0f; dy = 0f
                            } else {
                                dx = vx / d; dy = vy / d
                            }
                            length = kr * 1.5f * min(1f, d / far)
                        }
                        val taps = max(1, (length * 2).roundToInt())
                        var r = 0f
                        var g = 0f
                        var b = 0f
                        var wt = 0f
                        for (t in 0..taps) {
                            val along = if (taps == 0) 0f else -length + 2f * length * t / taps
                            val px = x + dx * along
                            val py = y + dy * along
                            // Bilinear tap.
                            val fx = px.coerceIn(0f, sw - 1f)
                            val fy = py.coerceIn(0f, sh - 1f)
                            val x0 = fx.toInt()
                            val y0 = fy.toInt()
                            val x1 = min(x0 + 1, sw - 1)
                            val y1 = min(y0 + 1, sh - 1)
                            val ax = fx - x0
                            val ay = fy - y0
                            val k00 = (1 - ax) * (1 - ay)
                            val k10 = ax * (1 - ay)
                            val k01 = (1 - ax) * ay
                            val k11 = ax * ay
                            val o00 = (y0 * sw + x0) * 4
                            val o10 = (y0 * sw + x1) * 4
                            val o01 = (y1 * sw + x0) * 4
                            val o11 = (y1 * sw + x1) * 4
                            r += k00 * small[o00] + k10 * small[o10] + k01 * small[o01] + k11 * small[o11]
                            g += k00 * small[o00 + 1] + k10 * small[o10 + 1] + k01 * small[o01 + 1] + k11 * small[o11 + 1]
                            b += k00 * small[o00 + 2] + k10 * small[o10 + 2] + k01 * small[o01 + 2] + k11 * small[o11 + 2]
                            wt += k00 * small[o00 + 3] + k10 * small[o10 + 3] + k01 * small[o01 + 3] + k11 * small[o11 + 3]
                        }
                        colors[y * sw + x] = colour(r, g, b, wt, inverse)
                    }
                }
            }
        }
        return Level(sw, sh, step, colors)
    }

    /** Averaged light back to a colour. */
    private fun colour(r: Float, g: Float, b: Float, wt: Float, inverse: Float): Int {
        if (wt <= 1e-9f) return 0xFF000000.toInt()
        fun c(v: Float) = ((v / wt).coerceIn(0f, 1f).pow(inverse) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    // ---- Mixing ---------------------------------------------------------------------------------

    /** Each pixel of [pixels] replaced by the mix of the two blur sizes nearest its own blur. */
    private suspend fun compose(
        pixels: IntArray, w: Int, h: Int, field: ByteArray, maxRadius: Float,
        radii: List<Float>, levels: List<Level>
    ) {
        rows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val blur = (field[i].toInt() and 255) / 255f * maxRadius
                if (blur < 0.35f) continue
                val out = if (blur <= radii[0]) {
                    mix(pixels[i], sample(levels[0], x, y), blur / radii[0])
                } else {
                    var k = 0
                    while (k < radii.size - 1 && blur > radii[k + 1]) k++
                    if (k == radii.size - 1) sample(levels[k], x, y)
                    else mix(sample(levels[k], x, y), sample(levels[k + 1], x, y), (blur - radii[k]) / (radii[k + 1] - radii[k]))
                }
                pixels[i] = out
            }
        }
    }

    /** [level] at full-size pixel [x], [y] (bilinear). */
    private fun sample(level: Level, x: Int, y: Int): Int {
        val fx = ((x + 0.5f) / level.step - 0.5f).coerceIn(0f, level.width - 1f)
        val fy = ((y + 0.5f) / level.step - 0.5f).coerceIn(0f, level.height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, level.width - 1)
        val y1 = min(y0 + 1, level.height - 1)
        val ax = fx - x0
        val ay = fy - y0
        val c = level.colors
        val top = mix(c[y0 * level.width + x0], c[y0 * level.width + x1], ax)
        val bottom = mix(c[y1 * level.width + x0], c[y1 * level.width + x1], ax)
        return mix(top, bottom, ay)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = a shr shift and 255
            val cb = b shr shift and 255
            return (ca + (cb - ca) * u).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Runs [body] for every row in 0 until [count], spread over the phone's cores. */
    private suspend fun rows(count: Int, body: (Int) -> Unit) = coroutineScope {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val chunk = max(1, (count + cores * 4 - 1) / (cores * 4))
        (0 until count step chunk).map { start ->
            async(Dispatchers.Default) {
                for (y in start until min(count, start + chunk)) {
                    if (y % 8 == 0) ensureActive()
                    body(y)
                }
            }
        }.awaitAll()
        Unit
    }

    /** How far past the sharp band (in depth-map units, 0..1) the blur reaches its most. */
    private const val FALLOFF = 0.35f

    /** Blur radius, in shrunk pixels, each size is worked out at. */
    private const val KERNEL = 6f

    /** How much a sharp pixel still counts in the blur (never quite nothing). */
    private const val MIN_WEIGHT = 0.03f
}

/** One use of Portrait blur: the photo as edited so far (screen size), its subject and depth, the choices. */
class PortraitBlurSession(val preview: Bitmap, subject: Bitmap?, val depth: DepthAnything.Depth) {
    /** The subject's cut-out (null when nothing stood out as a subject). */
    val matte: Matte? = subject?.let(Matte::of)?.takeIf { it.coverage >= 0.005f }
    private val subjectDepth: Float? = matte?.let { PortraitBlur.subjectDepth(depth, it) }

    var type by mutableStateOf(BlurType.Lens)
    var strength by mutableFloatStateOf(0.5f)
    var focusDepth by mutableFloatStateOf(subjectDepth ?: depth.at(0.5f, 0.5f))
    var depthOfField by mutableFloatStateOf(0.15f)
    var keepSubject by mutableStateOf(subjectDepth != null)
    var highlights by mutableFloatStateOf(0.5f)
    var angle by mutableFloatStateOf(0f)
    var focus by mutableStateOf(matte?.let(PortraitBlur::subjectCentre) ?: Offset(0.5f, 0.5f))
    var showDepth by mutableStateOf(false)
    var shown by mutableStateOf<Bitmap?>(null)
    val depthPicture: Bitmap by lazy { PortraitBlur.depthPicture(depth) }

    val hasSubject: Boolean get() = subjectDepth != null

    fun settings() = BlurSettings(type, strength, focusDepth, depthOfField, keepSubject && matte != null, highlights, angle, focus.x, focus.y)

    /** A tap at [point] (fractions of the photo): focuses at that distance (on the subject, keeps it crisp). */
    fun focusAt(point: Offset) {
        focus = point
        // A small average, so one odd depth pixel doesn't decide.
        var sum = 0f
        val d = 0.01f
        for (oy in -1..1) for (ox in -1..1) sum += depth.at((point.x + ox * d).coerceIn(0f, 1f), (point.y + oy * d).coerceIn(0f, 1f))
        val onSubject = (matte?.at(point.x, point.y) ?: 0f) >= 0.5f
        keepSubject = onSubject
        focusDepth = if (onSubject) subjectDepth ?: (sum / 9f) else sum / 9f
    }

    /** Switches the subject lock; turning it on focuses back on the subject. */
    fun toggleSubject() {
        if (!hasSubject) return
        keepSubject = !keepSubject
        if (keepSubject) {
            focusDepth = subjectDepth!!
            matte?.let(PortraitBlur::subjectCentre)?.let { focus = it }
        }
    }
}

@Composable
internal fun PortraitBlurScreen(session: PortraitBlurSession, onCancel: () -> Unit, onApply: () -> Unit) {
    val context = LocalContext.current
    val accent = remember { appAccentColor(context) }
    BackHandler(onBack = onCancel)
    var holding by remember { mutableStateOf(false) }
    // The preview follows the choices (a smaller copy, redone after each change).
    LaunchedEffect(session) {
        val small = withContext(Dispatchers.Default) { session.preview.scaledToFit(1080) }
        snapshotFlow { session.settings() }.conflate().collect { settings ->
            session.shown = withContext(Dispatchers.Default) { PortraitBlur.apply(small, session.depth, session.matte, settings) }
        }
    }
    CompositionLocalProvider(LocalAccent provides accent) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
                Text(
                    "Portrait blur", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
                IconButton(onClick = onApply) { Icon(Icons.Rounded.Done, "Apply", tint = accent) }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Hold to see the photo before Portrait blur.
                    .holdToCompare { holding = it }
                    .pointerInput(session) {
                        detectTapGestures { tap ->
                            val rect = fittedImageRect(size.width.toFloat(), size.height.toFloat(), session.preview.width, session.preview.height)
                            if (rect.contains(tap)) {
                                session.focusAt(Offset((tap.x - rect.left) / rect.width, (tap.y - rect.top) / rect.height))
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val shown = when {
                    holding -> session.preview
                    session.showDepth -> session.depthPicture
                    else -> session.shown
                }
                if (shown != null) {
                    Image(
                        bitmap = remember(shown) { shown.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Where it's focused.
                    if (!holding) Canvas(Modifier.fillMaxSize()) {
                        val rect = fittedImageRect(size.width, size.height, session.preview.width, session.preview.height)
                        val c = Offset(rect.left + session.focus.x * rect.width, rect.top + session.focus.y * rect.height)
                        val r = 22.dp.toPx()
                        drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(4.dp.toPx()))
                        drawCircle(accent, r, c, style = Stroke(2.dp.toPx()))
                        drawCircle(accent, 2.5.dp.toPx(), c)
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(20.dp)
                    ) { CircularProgressIndicator(color = accent) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    "Tap the photo to choose what's in focus.",
                    color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BlurType.entries.forEach { type ->
                        Pill(type.label, selected = session.type == type) { session.type = type }
                    }
                    if (session.hasSubject) Pill("Subject sharp", selected = session.keepSubject) { session.toggleSubject() }
                    Pill("Depth map", selected = session.showDepth) { session.showDepth = !session.showDepth }
                }
                Spacer(Modifier.height(6.dp))
                DialPanel(buildList {
                    add(Dial("Blur", Adjustment.Vignette.icon, (session.strength * 100).roundToInt()) { session.strength = it / 100f })
                    // Picking a distance by hand: the subject no longer decides. 0 far, 100 near.
                    add(Dial("Focus", Adjustment.Contrast.icon, (session.focusDepth * 100).roundToInt()) {
                        session.keepSubject = false
                        session.focusDepth = it / 100f
                    })
                    add(Dial("Depth", Adjustment.Shadows.icon, (session.depthOfField * 100).roundToInt()) { session.depthOfField = it / 100f })
                    when (session.type) {
                        BlurType.Lens -> add(Dial("Bokeh", Adjustment.Highlights.icon, (session.highlights * 100).roundToInt()) { session.highlights = it / 100f })
                        BlurType.Motion -> add(Dial("Angle", Adjustment.Brightness.icon, (session.angle / 1.8f).roundToInt()) { session.angle = it * 1.8f })
                        else -> Unit
                    }
                })
            }
        }
    }
}
