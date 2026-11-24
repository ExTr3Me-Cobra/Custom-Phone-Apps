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
 * a square around the character, ink = 1.0, contrast stretched, [SIZE]×[SIZE].
 *
 * OCR boxes are often off by half a character or span a whole group, so the box is first
 * [refine]d to the actual glyph nearest its center (gaps between characters in the column
 * profile), and whatever of the neighbors still falls in the square is blanked: one digit per crop.
 */
object CharCrop {
    const val SIZE = 32

    fun square(box: IntRect): IntRect {
        val side = max(box.height * 1.25, box.width * 1.25).roundToInt().coerceAtLeast(4)
        val cx = box.centerX; val cy = box.centerY
        return IntRect((cx - side / 2.0).roundToInt(), (cy - side / 2.0).roundToInt(), (cx + side / 2.0).roundToInt(), (cy + side / 2.0).roundToInt())
    }

    /**
     * The glyph nearest the middle of [box]: in a strip around the box (a bit taller, two and a
     * half heights wide), dark columns form runs separated by the gaps between characters; the run
     * at (or nearest) the box's center is the character. [box] itself if nothing clear is found.
     */
    fun refine(img: GrayImage, box: IntRect): IntRect = glyphAt(img, box) ?: box

    /** The glyph [refine] finds, or null if none is clear. */
    fun glyphAt(img: GrayImage, box: IntRect): IntRect? {
        val gs = glyphs(img, box)
        if (gs.isEmpty()) return null
        val c = box.centerX
        val h = box.height
        // The glyph under the center; if the center falls in a gap, the one whose middle is nearest.
        val g = gs.firstOrNull { c >= it.left && c < it.right } ?: gs.minBy { kotlin.math.abs(c - it.centerX) }
        val dist = if (c >= g.left && c < g.right) 0.0 else minOf(kotlin.math.abs(c - g.left), kotlin.math.abs(c - g.right))
        // A thin "1" is about 0.15 heights wide; wider than 0.95 means neighbors touch.
        if (dist > 0.45 * h || g.width < 0.1 * h || g.width > 0.95 * h) return null
        return g
    }

    /** Every glyph (run of inked columns, with its ink's top and bottom) in the strip around [box]. */
    fun glyphs(img: GrayImage, box: IntRect): List<IntRect> {
        val h = box.height
        if (h < 6) return emptyList()
        val cx = box.centerX
        val x0 = max(0, (cx - 1.25 * h).roundToInt()); val x1 = min(img.width, (cx + 1.25 * h).roundToInt())
        val y0 = max(0, (box.top - 0.1 * h).roundToInt()); val y1 = min(img.height, (box.bottom + 0.1 * h).roundToInt())
        val sw = x1 - x0; val sh = y1 - y0
        if (sw < 4 || sh < 4) return emptyList()
        val v = IntArray(sw * sh) { img[x0 + it % sw, y0 + it / sw] }
        val sorted = v.sortedArray()
        val lo = sorted[(v.size * 0.05).toInt()]; val hi = sorted[(v.size * 0.95).toInt()]
        if (hi - lo < 30) return emptyList()
        // Strokes are dark; the gaps between characters, even blurred, are closer to the paper.
        val t = lo + (hi - lo) * 0.45
        val colInk = IntArray(sw)
        for (y in 0 until sh) for (x in 0 until sw) if (v[y * sw + x] < t) colInk[x]++
        val minInk = max(1, (0.06 * sh).roundToInt())
        // Gaps narrower than a hairline (a broken stroke) don't split a glyph.
        val maxGap = (0.035 * h).toInt()
        val out = ArrayList<IntRect>()
        var x = 0
        while (x < sw) {
            if (colInk[x] < minInk) { x++; continue }
            var end = x; var gap = 0; var k = x
            while (k < sw) {
                if (colInk[k] >= minInk) { end = k; gap = 0 } else if (++gap > maxGap) break
                k++
            }
            // Touching characters (bold print, blur) form one run wider than a digit: split it into
            // digit-wide pieces (digits are about 0.6 heights wide).
            val rw = end + 1 - x
            val pieces = if (rw > 0.95 * h) max(2, (rw / (0.62 * h)).roundToInt()) else 1
            for (p in 0 until pieces) {
                val a0 = x + rw * p / pieces; val a1 = x + rw * (p + 1) / pieces
                var top = sh; var bottom = -1
                for (y in 0 until sh) for (xx in a0 until a1) if (v[y * sw + xx] < t) { if (y < top) top = y; if (y > bottom) bottom = y }
                val gh = bottom - top + 1
                // Too short for a character (an underline, a speck): skip.
                if (bottom >= 0 && gh >= 0.45 * h) {
                    val (gt, gb) = if (gh > 1.3 * h) box.top to box.bottom else (y0 + top) to (y0 + bottom + 1)
                    out += IntRect(x0 + a0, gt, x0 + a1, gb)
                }
            }
            x = end + 1
        }
        return joinSplit(out, h)
    }

    /**
     * A dead print-head line through a digit splits it into two runs that look like two narrow
     * characters. Rejoin neighbours across a thin gap (up to 0.13 heights) when the pieces are
     * narrower than a digit and together no wider than one (0.78 heights); two whole digits side
     * by side stay apart.
     */
    private fun joinSplit(glyphs: List<IntRect>, h: Int): List<IntRect> {
        if (glyphs.size < 2) return glyphs
        val out = ArrayList<IntRect>()
        var cur = glyphs[0]
        for (g in glyphs.drop(1)) {
            val gap = g.left - cur.right
            val joined = IntRect(cur.left, minOf(cur.top, g.top), g.right, maxOf(cur.bottom, g.bottom))
            val narrowPiece = cur.width < 0.42 * h || g.width < 0.42 * h
            if (gap <= 0.13 * h && narrowPiece && joined.width <= 0.78 * h) cur = joined else { out += cur; cur = g }
        }
        out += cur
        return out
    }

    fun extract(img: GrayImage, box: IntRect): FloatArray = extract(img, box, glyphAt(img, box))

    /** As [extract], with the glyph already found ([found] null: none, crop the box as it is). */
    fun extract(img: GrayImage, box: IntRect, found: IntRect?): FloatArray {
        val g = found ?: box
        // The square follows the glyph's height (the box's if the glyph came out short, like a dash).
        val sizing = if (g.height >= box.height * 0.8) g else IntRect(g.left, box.top, g.right, box.bottom)
        val r = square(IntRect(g.left, sizing.top, g.right, sizing.bottom))
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
        // Blank what's left of the neighbors: columns beyond the glyph (plus a little margin).
        if (found != null) {
            val pad = 0.06 * sizing.height
            val keepL = ((g.left - pad - r.left) / sx).toInt(); val keepR = ((g.right + pad - r.left) / sx).roundToInt()
            for (y in 0 until SIZE) for (xx in 0 until SIZE) if (xx < keepL || xx >= keepR) out[y * SIZE + xx] = 0f
        }
        return out
    }
}

/** Damaged-digit classifier: crop → probabilities for 0..9. */
class DigitModel(private val net: NetRunner) {
    fun classify(img: GrayImage, box: IntRect): DoubleArray = classify(img, box, CharCrop.glyphAt(img, box))

    /** As [classify], with the glyph to read already chosen (null: the box as it is). */
    fun classify(img: GrayImage, box: IntRect, glyph: IntRect?): DoubleArray {
        val p = net.run(CharCrop.extract(img, box, glyph), CharCrop.SIZE, CharCrop.SIZE)
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
