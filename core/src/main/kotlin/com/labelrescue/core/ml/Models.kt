package com.labelrescue.core.ml

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Runs one input (HWC floats, h×w×channels) through a network: LiteRT on Android, [RefNet] on the JVM. */
fun interface NetRunner {
    fun run(input: FloatArray, h: Int, w: Int): FloatArray
}

/** The optional on-device models. Null = not used (missing, or dropped by the benchmark). */
data class Models(
    val digits: DigitModel? = null,
    val restore: RestoreModel? = null,
    val regions: RegionModel? = null,
) {
    val names: List<String> get() = listOfNotNull(digits?.let { "digits" }, restore?.let { "restore" }, regions?.let { "regions" })

    companion object {
        val NONE = Models()
        const val DIGITS = "digit_classifier"
        const val RESTORE = "restore"
        const val REGIONS = "region_detector"
    }
}

/**
 * Character crops for the digit classifier, identical in training (mltools) and inference:
 * a square around the OCR box, ink = 1.0, contrast stretched, [SIZE]×[SIZE].
 */
object CharCrop {
    const val SIZE = 32

    fun square(box: IntRect): IntRect {
        val side = max(box.height * 1.25, box.width * 1.9).roundToInt().coerceAtLeast(4)
        val cx = box.centerX; val cy = box.centerY
        return IntRect((cx - side / 2.0).roundToInt(), (cy - side / 2.0).roundToInt(), (cx + side / 2.0).roundToInt(), (cy + side / 2.0).roundToInt())
    }

    fun extract(img: GrayImage, box: IntRect): FloatArray {
        val r = square(box)
        val out = FloatArray(SIZE * SIZE)
        val sx = r.width.toDouble() / SIZE; val sy = r.height.toDouble() / SIZE
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val v = img.sample(r.left + (x + 0.5) * sx - 0.5, r.top + (y + 0.5) * sy - 0.5, 255)
            out[y * SIZE + x] = ((255 - v) / 255.0).toFloat()
        }
        val sorted = out.sortedArray()
        val lo = sorted[(out.size * 0.05).toInt()]; val hi = sorted[(out.size * 0.98).toInt()]
        val span = max(0.15f, hi - lo)
        for (i in out.indices) out[i] = ((out[i] - lo) / span).coerceIn(0f, 1f)
        return out
    }
}

/** Damaged-digit classifier: crop → probabilities for 0..9. */
class DigitModel(private val net: NetRunner) {
    fun classify(img: GrayImage, box: IntRect): DoubleArray {
        val p = net.run(CharCrop.extract(img, box), CharCrop.SIZE, CharCrop.SIZE)
        return DoubleArray(10) { p[it].toDouble() }
    }
}

/**
 * Restoration (denoise/deblur), fully convolutional, run on overlapping fixed-size tiles so the
 * same model works on any image size and on GPU delegates that want fixed shapes.
 */
class RestoreModel(private val net: NetRunner, private val tile: Int = 96, private val overlap: Int = 12) {
    fun restore(img: GrayImage): GrayImage {
        val out = GrayImage(img.width, img.height)
        val step = tile - 2 * overlap
        val inp = FloatArray(tile * tile)
        var ty = -overlap
        while (ty < img.height - overlap) {
            var tx = -overlap
            while (tx < img.width - overlap) {
                for (y in 0 until tile) for (x in 0 until tile) {
                    inp[y * tile + x] = img.getClamped(tx + x, ty + y) / 255f
                }
                val r = net.run(inp, tile, tile)
                for (y in overlap until tile - overlap) {
                    val iy = ty + y; if (iy < 0 || iy >= img.height) continue
                    for (x in overlap until tile - overlap) {
                        val ix = tx + x; if (ix < 0 || ix >= img.width) continue
                        out[ix, iy] = (r[y * tile + x] * 255f).roundToInt()
                    }
                }
                tx += step
            }
            ty += step
        }
        return out
    }
}

/** A labelled area found by the region detector, in image coordinates. */
data class DetectedRegion(val kind: Kind, val box: IntRect, val score: Double) {
    enum class Kind { BARCODE, TRACKING_TEXT, ZIP, SERVICE_BANNER }
}

/**
 * Region detector: a small fully convolutional net predicting per-cell masks (stride 8) for the
 * barcode, the tracking number text, ZIP codes and the service banner on a [INPUT]² letterboxed
 * label image; boxes are connected components of the thresholded masks.
 */
class RegionModel(private val net: NetRunner, private val threshold: Double = 0.5) {
    fun detect(img: GrayImage): List<DetectedRegion> {
        val s = min(INPUT.toDouble() / img.width, INPUT.toDouble() / img.height)
        val small = img.scaled(max(1, (img.width * s).roundToInt()), max(1, (img.height * s).roundToInt()))
        val inp = FloatArray(INPUT * INPUT) { 1f }
        for (y in 0 until small.height) for (x in 0 until small.width) inp[y * INPUT + x] = small[x, y] / 255f
        val out = net.run(inp, INPUT, INPUT)
        val g = INPUT / STRIDE
        val res = ArrayList<DetectedRegion>()
        for ((k, kind) in DetectedRegion.Kind.entries.withIndex()) {
            val mask = BooleanArray(g * g) { out[it * KINDS + k] >= threshold }
            val seen = BooleanArray(g * g)
            for (start in 0 until g * g) {
                if (!mask[start] || seen[start]) continue
                val stack = ArrayDeque<Int>().apply { add(start) }; seen[start] = true
                var l = g; var t = g; var r = -1; var b = -1; var sum = 0.0; var n = 0
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast(); val x = p % g; val y = p / g
                    l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y); sum += out[p * KINDS + k]; n++
                    for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until g && ny in 0 until g && mask[ny * g + nx] && !seen[ny * g + nx]) { seen[ny * g + nx] = true; stack.add(ny * g + nx) }
                    }
                }
                if (n < 2) continue
                val f = STRIDE / s
                val box = IntRect((l * f).roundToInt(), (t * f).roundToInt(), ((r + 1) * f).roundToInt(), ((b + 1) * f).roundToInt())
                    .intersect(IntRect(0, 0, img.width, img.height))
                if (!box.isEmpty) res += DetectedRegion(kind, box, sum / n)
            }
        }
        return res.sortedByDescending { it.score }
    }

    companion object {
        const val INPUT = 256
        const val STRIDE = 8
        val KINDS = DetectedRegion.Kind.entries.size
    }
}
