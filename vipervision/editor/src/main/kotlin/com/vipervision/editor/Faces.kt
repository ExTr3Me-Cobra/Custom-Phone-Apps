package com.vipervision.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.t8rin.neural_tools.viper.FaceParser
import com.t8rin.neural_tools.viper.YuNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A face found by YuNet; everything in fractions of the photo. */
data class Face(
    val box: RectF,
    val rightEye: Offset,
    val leftEye: Offset,
    val nose: Offset,
    val mouthRight: Offset,
    val mouthLeft: Offset,
    val score: Float
)

object Faces {

    /** The faces in [image] (YuNet must be downloaded). */
    fun detect(image: Bitmap): List<Face> {
        OpenCVLoader.initLocal()
        val img = image.scaledToFit(1600)
        val rgba = Mat()
        val bgr = Mat()
        val found = Mat()
        try {
            Utils.bitmapToMat(img.copy(Bitmap.Config.ARGB_8888, false), rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val detector = FaceDetectorYN.create(YuNet.path, "", Size(img.width.toDouble(), img.height.toDouble()), 0.7f, 0.3f, 5000)
            detector.detect(bgr, found)
            val w = img.width.toFloat()
            val h = img.height.toFloat()
            val faces = ArrayList<Face>()
            val v = FloatArray(15)
            for (r in 0 until found.rows()) {
                found.get(r, 0, v)
                fun p(i: Int) = Offset(v[i] / w, v[i + 1] / h)
                faces += Face(
                    RectF(v[0] / w, v[1] / h, (v[0] + v[2]) / w, (v[1] + v[3]) / h),
                    p(4), p(6), p(8), p(10), p(12), v[14]
                )
            }
            return faces
        } finally {
            rgba.release(); bgr.release(); found.release()
            if (img !== image) img.recycle()
        }
    }

    /** A face's parts: [labels] (512×512, see FaceParser) over [crop] (fractions of the photo). */
    class Parts(val face: Face, val crop: RectF, val labels: ByteArray)

    /** Each face's parts in [image] (FaceParser must be downloaded). */
    fun parse(image: Bitmap, faces: List<Face>): List<Parts> = FaceParser.open().use { parser ->
        faces.map { face ->
            val cx = face.box.centerX() * image.width
            val cy = (face.box.centerY() - face.box.height() * 0.08f) * image.height
            val side = max(face.box.width() * image.width, face.box.height() * image.height) * 1.7f
            val left = (cx - side / 2).coerceIn(0f, image.width - 2f)
            val top = (cy - side / 2).coerceIn(0f, image.height - 2f)
            val right = (cx + side / 2).coerceIn(left + 1f, image.width.toFloat())
            val bottom = (cy + side / 2).coerceIn(top + 1f, image.height.toFloat())
            val piece = Bitmap.createBitmap(image, left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
            val labels = parser.parse(piece)
            if (piece !== image) piece.recycle()
            Parts(face, RectF(left / image.width, top / image.height, right / image.width, bottom / image.height), labels)
        }
    }
}

/** Box blur of a float plane by [r] px (twice, which is close to a smooth bell-curve blur). */
private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    if (r < 1) return src.copyOf()
    var a = src.copyOf()
    val tmp = FloatArray(w * h)
    repeat(2) {
        for (y in 0 until h) {
            var sum = 0f
            val row = y * w
            for (x in -r..r) sum += a[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / (2 * r + 1)
                sum += a[row + min(w - 1, x + r + 1)] - a[row + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / (2 * r + 1)
                sum += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
            }
        }
    }
    return a
}

/** Face retouch's sliders (0..1). */
data class RetouchSettings(val skin: Float, val eyes: Float, val teeth: Float, val lips: Float, val beard: Float = 0f)

/** Face retouch: smooth skin (keeping its texture), brighter eyes, whiter teeth, richer lips. */
class FaceRetouchTool(private val preview: Bitmap, private val parts: List<Faces.Parts>) : StudioTool {
    override val stepName = "Face retouch"

    var skin by mutableFloatStateOf(0f)
    var eyes by mutableFloatStateOf(0f)
    var teeth by mutableFloatStateOf(0f)
    var lips by mutableFloatStateOf(0f)
    /** -1 (lighter, softer) .. 1 (fuller, darker). */
    var beard by mutableFloatStateOf(0f)
    var showBefore by mutableStateOf(false)
    private var shown by mutableStateOf<Bitmap?>(null)

    private fun settings() = RetouchSettings(skin, eyes, teeth, lips, beard)

    override suspend fun render(context: Context, full: Bitmap): Bitmap = render(full, settings())

    private fun render(image: Bitmap, s: RetouchSettings): Bitmap {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        for (p in parts) retouch(out, p, s)
        return out
    }

    private fun retouch(image: Bitmap, p: Faces.Parts, s: RetouchSettings) {
        val left = (p.crop.left * image.width).roundToInt().coerceIn(0, image.width - 1)
        val top = (p.crop.top * image.height).roundToInt().coerceIn(0, image.height - 1)
        val right = (p.crop.right * image.width).roundToInt().coerceIn(left + 1, image.width)
        val bottom = (p.crop.bottom * image.height).roundToInt().coerceIn(top + 1, image.height)
        val w = right - left
        val h = bottom - top
        val px = IntArray(w * h)
        image.getPixels(px, 0, w, left, top, w, h)
        val faceSize = max(p.face.box.width() * image.width, p.face.box.height() * image.height)
        // Each part's mask over the crop.
        fun mask(vararg classes: Int): FloatArray {
            val m = FloatArray(w * h)
            for (y in 0 until h) {
                val ly = ((y + 0.5f) / h * FaceParser.SIDE).toInt().coerceIn(0, FaceParser.SIDE - 1)
                for (x in 0 until w) {
                    val lx = ((x + 0.5f) / w * FaceParser.SIDE).toInt().coerceIn(0, FaceParser.SIDE - 1)
                    if (p.labels[ly * FaceParser.SIDE + lx].toInt() in classes) m[y * w + x] = 1f
                }
            }
            return m
        }
        val feather = max(1, (faceSize * 0.012f).roundToInt())
        val r = FloatArray(w * h) { px[it].red8.toFloat() }
        val g = FloatArray(w * h) { px[it].green8.toFloat() }
        val b = FloatArray(w * h) { px[it].blue8.toFloat() }

        if (s.skin > 0f) {
            val hard = mask(1, 10, 14)
            // Obvious facial hair (enough of the lower face) isn't skin: it's left as it is and
            // its colour isn't mixed into the skin around it.
            val (hair, region) = findHair(p, image, top, w, h, faceSize, r, g, b, ::mask)
            val regionSum = region.sum()
            if (regionSum > 0f && hair.sum() / regionSum > 0.08f) {
                val grown = blur(hair, w, h, max(1, (faceSize * 0.008f).roundToInt()))
                for (i in hard.indices) hard[i] *= (1f - (grown[i] * 2.5f).coerceAtMost(1f))
            }
            // Only the blotchy middle sizes are smoothed: the face's shape and shading (bigger)
            // and its fine texture (smaller) stay, so it doesn't look waxy.
            val m = blur(hard, w, h, feather)
            val mid = max(2, (faceSize * 0.018f).roundToInt())
            val fine = max(1, (faceSize * 0.004f).roundToInt())
            val weight = blur(hard, w, h, mid)
            for (plane in listOf(r, g, b)) {
                // Skin's own colours only (no hair or background mixed in).
                val middle = blur(FloatArray(w * h) { plane[it] * hard[it] }, w, h, mid)
                val small = blur(plane, w, h, fine)
                for (i in plane.indices) {
                    if (m[i] <= 0.001f || weight[i] < 0.05f) continue
                    plane[i] -= (small[i] - middle[i] / weight[i]) * 0.9f * s.skin * m[i]
                }
            }
        }
        if (s.beard != 0f) facialHair(p, image, top, w, h, faceSize, r, g, b, s.beard, ::mask)
        if (s.eyes > 0f) {
            val m = blur(mask(4, 5), w, h, max(1, feather / 2))
            val rad = max(1, (faceSize * 0.01f).roundToInt())
            for (plane in listOf(r, g, b)) {
                val soft = blur(plane, w, h, rad)
                for (i in plane.indices) {
                    if (m[i] <= 0.001f) continue
                    plane[i] += s.eyes * m[i] * ((255f - plane[i]) * 0.18f + (plane[i] - soft[i]) * 0.8f)
                }
            }
        }
        if (s.teeth > 0f) {
            val m = blur(mask(11), w, h, max(1, feather / 2))
            for (i in m.indices) {
                if (m[i] <= 0.001f) continue
                val mx = max(r[i], max(g[i], b[i]))
                val mn = min(r[i], min(g[i], b[i]))
                val sat = (mx - mn) / max(1f, mx)
                // Teeth are bright and only a little coloured (unlike the tongue and gums).
                val k = s.teeth * m[i] * ((1f - sat / 0.55f).coerceIn(0f, 1f)) * ((mx - 80f) / 60f).coerceIn(0f, 1f) * 0.8f
                if (k <= 0f) continue
                val lum = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                val t = min(255f, lum * 1.1f + 12f)
                r[i] += (t - r[i]) * k
                g[i] += (t - g[i]) * k
                b[i] += (min(255f, t + 4f) - b[i]) * k
            }
        }
        if (s.lips > 0f) {
            val m = blur(mask(12, 13), w, h, max(1, feather / 2))
            for (i in m.indices) {
                if (m[i] <= 0.001f) continue
                val grey = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                val k = 1f + 0.9f * s.lips
                val nr = grey + (r[i] - grey) * k + 10f * s.lips
                val ng = grey + (g[i] - grey) * k
                val nb = grey + (b[i] - grey) * k
                r[i] += (nr - r[i]) * m[i]
                g[i] += (ng - g[i]) * m[i]
                b[i] += (nb - b[i]) * m[i]
            }
        }
        for (i in px.indices) px[i] = rgb(r[i], g[i], b[i])
        image.setPixels(px, 0, w, left, top, w, h)
    }

    /**
     * Facial hair: in the lower face (from just under the nose down, the face model's skin and
     * neck), the pixels clearly darker than the skin around them are the hair. Fuller darkens them
     * and fills the gaps between them with their colour; lighter blends them toward the skin.
     */
    /**
     * Where the facial hair is (0..1 per crop pixel): in the lower face (from just under the nose
     * down, the face model's skin and neck), the pixels clearly darker than the skin around them.
     * Also the region it was looked for in.
     */
    private fun findHair(
        p: Faces.Parts, image: Bitmap, top: Int, w: Int, h: Int, faceSize: Float,
        r: FloatArray, g: FloatArray, b: FloatArray, mask: (IntArray) -> FloatArray
    ): Pair<FloatArray, FloatArray> {
        val noseY = p.face.nose.y * image.height - top
        val mouthY = (p.face.mouthLeft.y + p.face.mouthRight.y) / 2f * image.height - top
        val startY = noseY + (mouthY - noseY) * 0.3f
        val ramp = max(2f, faceSize * 0.08f)
        fun px(f: Float) = max(1, (faceSize * f).roundToInt())
        // The face's own skin (not the neck, where shadows look like hair), kept back from its
        // edge (the jaw's outline isn't hair either), from just under the nose down.
        val face = mask(intArrayOf(1))
        val inside = blur(face, w, h, px(0.0125f))
        val region = FloatArray(w * h) { i ->
            if (inside[i] < 0.98f) 0f else (((i / w) - startY) / ramp).coerceIn(0f, 1f)
        }
        val lum = FloatArray(w * h) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
        val wide = px(0.06f)
        // Darker than the bare skin around it (worked out again without the hair found so far).
        var hair = FloatArray(w * h)
        repeat(3) {
            val bare = FloatArray(w * h) { face[it] * (1f - (hair[it] * 2f).coerceAtMost(1f)) }
            val bw = blur(bare, w, h, wide)
            val bl = blur(FloatArray(w * h) { lum[it] * bare[it] }, w, h, wide)
            hair = FloatArray(w * h) { i ->
                if (region[i] <= 0f || bw[i] < 0.05f) 0f
                else (((bl[i] / bw[i]) - lum[i] - 3f) / 18f).coerceIn(0f, 1f) * region[i]
            }
        }
        // And hair-like: fine texture around it (smooth shadows aren't hair) ...
        val fine = px(0.006f)
        val mean = blur(lum, w, h, fine)
        val square = blur(FloatArray(w * h) { lum[it] * lum[it] }, w, h, fine)
        val texture = blur(FloatArray(w * h) { ((kotlin.math.sqrt(max(0f, square[it] - mean[it] * mean[it])) - 2f) / 6f).coerceIn(0f, 1f) }, w, h, px(0.02f))
        for (i in hair.indices) hair[i] *= (texture[i] * 1.5f).coerceAtMost(1f)
        // ... and in a patch, not a thin line.
        val density = blur(hair, w, h, px(0.015f))
        for (i in hair.indices) hair[i] *= (density[i] * 3f).coerceAtMost(1f)
        return blur(hair, w, h, px(0.006f)) to region
    }

    /** Facial hair: fuller darkens it and fills its gaps with its colour; lighter blends it toward the skin. */
    private fun facialHair(
        p: Faces.Parts, image: Bitmap, top: Int, w: Int, h: Int, faceSize: Float,
        r: FloatArray, g: FloatArray, b: FloatArray, amount: Float, mask: (IntArray) -> FloatArray
    ) {
        val (soft, region) = findHair(p, image, top, w, h, faceSize, r, g, b, mask)
        val skin = mask(intArrayOf(1, 14))
        val wide = max(3, (faceSize * 0.06f).roundToInt())
        evenColour(r, g, b, soft, amount)
        if (amount > 0f) {
            // The hair's colour nearby, to fill the gaps with.
            val near = max(2, (faceSize * 0.025f).roundToInt())
            val hw = blur(soft, w, h, near)
            for (plane in listOf(r, g, b)) {
                val hc = blur(FloatArray(w * h) { plane[it] * soft[it] }, w, h, near)
                for (i in plane.indices) {
                    if (region[i] <= 0f) continue
                    var v = plane[i] * (1f - 0.15f * amount * soft[i])
                    if (hw[i] > 0.05f) {
                        val fill = 0.45f * amount * region[i] * (1f - soft[i]) * (hw[i] * 2f).coerceAtMost(1f)
                        v += (hc[i] / hw[i] - v) * fill
                    }
                    plane[i] = v
                }
            }
        } else {
            // Toward the skin's own colour (from the skin that isn't hair).
            val bare = FloatArray(w * h) { skin[it] * (1f - soft[it]) }
            val bw = blur(bare, w, h, wide)
            for (plane in listOf(r, g, b)) {
                val bc = blur(FloatArray(w * h) { plane[it] * bare[it] }, w, h, wide)
                for (i in plane.indices) {
                    if (soft[i] <= 0.001f || bw[i] < 0.05f) continue
                    plane[i] += (bc[i] / bw[i] - plane[i]) * (-amount) * 0.85f * soft[i]
                }
            }
        }
    }

    /**
     * One colour for the facial hair: its main colour comes from its more colourful hairs (greys
     * don't count), and each hair is moved toward it, keeping its own brightness. Grey hairs move
     * most, and with a fuller beard they're also brought to the beard's own darkness.
     */
    private fun evenColour(r: FloatArray, g: FloatArray, b: FloatArray, hair: FloatArray, amount: Float) {
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var sw = 0.0
        val sat = FloatArray(hair.size)
        for (i in hair.indices) {
            if (hair[i] <= 0.05f) continue
            val mx = max(r[i], max(g[i], b[i]))
            val mn = min(r[i], min(g[i], b[i]))
            sat[i] = (mx - mn) / max(1f, mx)
            val k = hair[i] * ((sat[i] - 0.08f) / 0.2f).coerceIn(0f, 1f)
            sr += r[i] * k; sg += g[i] * k; sb += b[i] * k; sw += k
        }
        if (sw < 20.0) return
        val tr = (sr / sw).toFloat()
        val tg = (sg / sw).toFloat()
        val tb = (sb / sw).toFloat()
        val tl = max(1f, 0.299f * tr + 0.587f * tg + 0.114f * tb)
        val tmx = max(tr, max(tg, tb))
        val tSat = ((tmx - min(tr, min(tg, tb))) / max(1f, tmx)).coerceAtLeast(0.05f)
        val strength = kotlin.math.abs(amount)
        for (i in hair.indices) {
            if (hair[i] <= 0.01f) continue
            val grey = 1f - (sat[i] / tSat).coerceIn(0f, 1f)
            val k = (strength * hair[i] * (0.55f + 0.45f * grey)).coerceAtMost(1f)
            var l = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
            if (amount > 0f) l += (tl - l) * 0.3f * grey
            val f = l / tl
            r[i] += (tr * f - r[i]) * k
            g[i] += (tg * f - g[i]) * k
            b[i] += (tb * f - b[i]) * k
        }
    }

    @Composable
    override fun Screen(onCancel: () -> Unit, onApply: () -> Unit) {
        val small = remember { preview.scaledToFit(1080) }
        LaunchedEffect(Unit) {
            snapshotFlow { settings() }.conflate().collect { s ->
                shown = withContext(Dispatchers.Default) { render(small, s) }
            }
        }
        StudioFrame(
            title = "Face retouch",
            shown = if (showBefore) small else shown,
            photoWidth = preview.width,
            photoHeight = preview.height,
            onCancel = onCancel,
            onApply = onApply
        ) {
            StudioHint("${parts.size} face${if (parts.size == 1) "" else "s"} found.")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Before", selected = showBefore) { showBefore = !showBefore }
            }
            LabeledSlider("Skin ${(skin * 100).roundToInt()}%", skin, 0f..1f) { skin = it }
            LabeledSlider("Eyes ${(eyes * 100).roundToInt()}%", eyes, 0f..1f) { eyes = it }
            LabeledSlider("Teeth ${(teeth * 100).roundToInt()}%", teeth, 0f..1f) { teeth = it }
            LabeledSlider("Lips ${(lips * 100).roundToInt()}%", lips, 0f..1f) { lips = it }
            LabeledSlider(
                when {
                    beard > 0f -> "Beard fuller ${(beard * 100).roundToInt()}"
                    beard < 0f -> "Beard lighter ${(-beard * 100).roundToInt()}"
                    else -> "Facial hair"
                },
                beard, -1f..1f
            ) { beard = it }
        }
    }
}

/** Red eye: in each eye YuNet found, the red of a flash in the pupil turned back to dark. */
object RedEye {

    /** [image] with red eyes fixed, and how many eyes were. */
    fun fix(image: Bitmap, faces: List<Face>): Pair<Bitmap, Int> {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        var fixed = 0
        for (face in faces) {
            val re = Offset(face.rightEye.x * image.width, face.rightEye.y * image.height)
            val le = Offset(face.leftEye.x * image.width, face.leftEye.y * image.height)
            val apart = hypot(re.x - le.x, re.y - le.y)
            if (apart < 6f) continue
            for (eye in listOf(re, le)) if (fixEye(out, eye, apart * 0.2f)) fixed++
        }
        return out to fixed
    }

    private fun fixEye(image: Bitmap, centre: Offset, radius: Float): Boolean {
        val r = max(3, radius.roundToInt())
        val left = (centre.x - r).roundToInt().coerceIn(0, image.width - 1)
        val top = (centre.y - r).roundToInt().coerceIn(0, image.height - 1)
        val right = (centre.x + r).roundToInt().coerceIn(left + 1, image.width)
        val bottom = (centre.y + r).roundToInt().coerceIn(top + 1, image.height)
        val w = right - left
        val h = bottom - top
        val px = IntArray(w * h)
        image.getPixels(px, 0, w, left, top, w, h)
        val red = FloatArray(w * h)
        var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val d = hypot(left + x + 0.5f - centre.x, top + y + 0.5f - centre.y) / r
            if (d > 1f) continue
            val other = max(c.green8, c.blue8).toFloat()
            if (c.red8 > 55 && c.red8 > other * 1.45f) {
                red[y * w + x] = 1f - d * 0.3f
                count++
            }
        }
        if (count < 4) return false
        val m = blur(red, w, h, max(1, r / 12))
        for (i in px.indices) {
            if (m[i] <= 0.01f) continue
            val c = px[i]
            val k = min(1f, m[i] * 1.3f)
            val dark = (c.green8 + c.blue8) / 2f * 0.85f
            px[i] = rgb(c.red8 + (dark - c.red8) * k, c.green8 * (1 - 0.08f * k), c.blue8 * (1 - 0.08f * k))
        }
        image.setPixels(px, 0, w, left, top, w, h)
        return true
    }
}
