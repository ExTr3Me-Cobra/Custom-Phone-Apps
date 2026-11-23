package com.labelrescue.core.capture

import com.labelrescue.core.barcode.BarcodeLocator
import com.labelrescue.core.fusion.NormRect
import com.labelrescue.core.image.Filters
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Live capture check: is the whole label lined up with the four corner marks, and in focus?
 *
 * The marks frame a 4×6 in. (2:3) label filling most of the 3:4 portrait frame, so the whole
 * label is in the photo whatever its design. Lined up means:
 * - the label's paper fills the marks (each edge near its mark), when the paper stands out from
 *   the background; on a white box, where it doesn't, this check is skipped;
 * - a barcode is inside the marks, wide enough for the label to be at the right distance, and straight.
 *
 * Landscape labels (wider than tall, like small Priority Mail labels) get landscape marks: when the
 * paper is found landscape on a few frames in a row the marks turn to [LANDSCAPE_GUIDE], and back
 * once it's found portrait again.
 */
class CaptureGuide(
    /** The label frame, as fractions of the (upright) camera frame. */
    val guide: NormRect = DEFAULT_GUIDE,
    private val maxTiltDegrees: Double = 6.0,
    /** Absolute sharpness floor (gradient RMS on the downscaled barcode area). */
    private val minSharpness: Double = 14.0,
) {
    enum class Issue { NO_BARCODE, TOO_SMALL, TOO_CLOSE, OFF_CENTER, TILTED, TOO_DARK, GLARE, BLURRY }

    data class Status(
        val ready: Boolean,
        val issue: Issue?,
        val message: String,
        val aligned: Boolean,
        val focused: Boolean,
        val sharpness: Double,
        val tiltDegrees: Double,
        /** Where the barcode was found (fractions of the frame), for drawing and focusing. */
        val barcode: NormRect?,
        /** Where the label's paper was found (fractions of the frame), if it stands out. */
        val label: NormRect? = null,
        /** Everything that looks like a 1D barcode (fractions of the frame), for the live overlay. */
        val regions: List<NormRect> = emptyList(),
        /** The corner marks in use (portrait, or landscape for a landscape label). */
        val guide: NormRect = DEFAULT_GUIDE,
        val landscape: Boolean = false,
    )

    /** The marks in use now: [guide], or [LANDSCAPE_GUIDE] while a landscape label is in view. */
    @Volatile var current: NormRect = guide
        private set
    @Volatile var landscape: Boolean = false
        private set
    private var orientationVotes = 0

    private val recentSharpness = ArrayDeque<Double>()
    private var readyStreak = 0

    /** Call with each analyzed frame (upright). Keeps a little history for focus and stability. */
    @Synchronized
    fun evaluate(frame: GrayImage): Status {
        val small = frame.limited(640)
        val area = small.width.toDouble() * small.height
        // Barcode-shaped areas only: the locator can also return a merged area as big as the label
        // (several codes plus the gaps between them), which is no use for aiming or focus.
        val all = BarcodeLocator.locate(small, 8).filter { r ->
            val w = r.rect.width.toDouble(); val h = r.rect.height.toDouble()
            val along = if (r.vertical) h else w; val across = if (r.vertical) w else h
            w * h <= 0.25 * area && along >= 1.4 * across
        }
        val st = evaluate(small, all.filter { !it.vertical })
        val sx = small.width.toDouble(); val sy = small.height.toDouble()
        return st.copy(guide = current, landscape = landscape, regions = all.map { r -> NormRect((r.rect.left / sx).toFloat(), (r.rect.top / sy).toFloat(), (r.rect.right / sx).toFloat(), (r.rect.bottom / sy).toFloat()) })
    }

    private fun evaluate(small: GrayImage, regions: List<BarcodeLocator.Region>): Status {
        val sx = small.width.toDouble(); val sy = small.height.toDouble()
        fun norm(r: IntRect) = NormRect((r.left / sx).toFloat(), (r.top / sy).toFloat(), (r.right / sx).toFloat(), (r.bottom / sy).toFloat())

        // The paper's edges are advice, never a blocker: on busy backgrounds they can't always
        // be found, and labels come in other sizes than 4x6. The barcode decides.
        val paper = paperRect(small)
        // Portrait or landscape marks: switch after a few frames that agree, so they don't flicker.
        if (paper != null) {
            val wide = paper.width > paper.height * 1.05
            if (wide != landscape) {
                orientationVotes++
                if (orientationVotes >= ORIENTATION_FRAMES) {
                    landscape = wide; current = if (wide) LANDSCAPE_GUIDE else guide; orientationVotes = 0
                }
            } else orientationVotes = 0
        }
        val g = current.toPixels(small.width, small.height)
        val paperNorm = paper?.let(::norm)
        val paperSmall = paper != null && max(paper.width.toDouble() / g.width, paper.height.toDouble() / g.height) < 0.75
        val paperOff = paper != null && run {
            val dx = ((paper.left + paper.right) - (g.left + g.right)) / 2.0 / g.width
            val dy = ((paper.top + paper.bottom) - (g.top + g.bottom)) / 2.0 / g.height
            abs(dx) > 0.2 || abs(dy) > 0.2
        }
        val paperTooBig = paper != null && paper.width > g.width * 1.12 && paper.height > g.height * 1.12

        val inside = regions.filter { overlap(it.rect, g) >= 0.7 }
        val best = inside.maxByOrNull { it.rect.width }
        if (best == null) {
            val (issue, msg) = when {
                paperSmall -> Issue.TOO_SMALL to "Move closer: line the label's corners up with the 4 marks"
                paperOff -> Issue.OFF_CENTER to "Move the label to the middle: its corners should meet the 4 marks"
                else -> Issue.NO_BARCODE to "Line the label's 4 corners up with the corner marks"
            }
            return notReady(issue, msg, null, paperNorm)
        }
        val r = best.rect
        val crop = small.crop(r.intersect(IntRect(0, 0, small.width, small.height)))
        val tilt = Filters.estimateSkew(crop)
        val mean = crop.mean()
        val sharp = Filters.sharpness(crop)
        recentSharpness.addLast(sharp)
        while (recentSharpness.size > 30) recentSharpness.removeFirst()
        // A typical sharp frame, not the single sharpest (one lucky frame made every other one
        // count as out of focus, so the check flickered with hand shake).
        val peak = recentSharpness.sorted().let { it[((it.size - 1) * 0.8).toInt()] }

        // On a 4×6 label the main barcode spans roughly 45-95% of the label's width.
        val widthFrac = r.width.toDouble() / g.width
        // A label found clearly smaller than the marks: closer gives a sharper read.
        val tooFar = widthFrac < (if (landscape) MIN_BARCODE_WIDTH_LANDSCAPE else MIN_BARCODE_WIDTH) || paperSmall
        val aligned = !tooFar && !paperTooBig && !paperOff && abs(tilt) <= maxTiltDegrees
        val focused = sharp >= minSharpness && sharp >= 0.75 * peak

        val (issue, msg) = when {
            tooFar -> Issue.TOO_SMALL to "Move closer: line the label's corners up with the 4 marks"
            paperOff -> Issue.OFF_CENTER to "Move the label to the middle: its corners should meet the 4 marks"
            paperTooBig -> Issue.TOO_CLOSE to "Move back: the whole label should fit inside the 4 marks"
            abs(tilt) > maxTiltDegrees -> Issue.TILTED to "Straighten the phone (label tilted ${abs(tilt).toInt()}°)"
            mean < 45 -> Issue.TOO_DARK to "More light needed (try the torch)"
            mean > 238 -> Issue.GLARE to "Too much glare: tilt the phone slightly"
            !focused -> Issue.BLURRY to "Hold steady, focusing…"
            else -> null to "Lined up and sharp"
        }
        if (issue != null) return notReady(issue, msg, norm(r), paperNorm, sharp, tilt, aligned, focused)
        val lined = if (landscape) "Landscape label lined up and sharp" else "Lined up and sharp"
        readyStreak++
        // Two good checks in a row, so a passing blur doesn't count.
        val ready = readyStreak >= 2
        return Status(ready, null, if (ready) "$lined: tap Burst capture" else "Hold still…", aligned, focused, sharp, tilt, norm(r), paperNorm)
    }

    /**
     * The label's paper: the largest bright area once thin dark features (ruled lines, text,
     * barcode bars) are closed over, if it's clearly brighter than its surroundings and shaped like
     * a label (portrait or landscape, mostly filling its box). Null when it can't be told apart: a white or
     * striped background, or paper cut into pieces.
     */
    private fun paperRect(img: GrayImage): IntRect? {
        val s = img.limited(160)
        val w = s.width; val h = s.height
        val v = IntArray(w * h) { s.data[it].toInt() and 0xFF }
        val t = Filters.otsu(v)
        // Two clearly different populations: paper and background.
        var lo = 0.0; var nlo = 0; var hi = 0.0; var nhi = 0
        for (x in v) if (x <= t) { lo += x; nlo++ } else { hi += x; nhi++ }
        if (nlo == 0 || nhi == 0 || hi / nhi - lo / nlo < 40) return null
        // Closing of the bright mask (dilate, then erode, radius 2): ruled lines and text on the
        // label no longer split it into pieces.
        var mask = BooleanArray(w * h) { v[it] > t }
        fun morph(m: BooleanArray, dilate: Boolean, r: Int): BooleanArray = BooleanArray(w * h) { p ->
            val x = p % w; val y = p / w
            var hit = !dilate
            loop@ for (dy in -r..r) for (dx in -r..r) {
                val xx = x + dx; val yy = y + dy
                val b = if (xx in 0 until w && yy in 0 until h) m[yy * w + xx] else !dilate
                if (dilate && b) { hit = true; break@loop }
                if (!dilate && !b) { hit = false; break@loop }
            }
            hit
        }
        mask = morph(morph(mask, true, 2), false, 2)
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var best: IntArray? = null; var bestArea = 0
        for (start in 0 until w * h) {
            if (seen[start] || !mask[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var l = w; var tp = h; var r = 0; var b = 0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                area++
                if (x < l) l = x; if (x > r) r = x; if (y < tp) tp = y; if (y > b) b = y
                for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < h - 1) p + w else -1)) {
                    if (q < 0 || seen[q] || !mask[q]) continue
                    seen[q] = true; stack[sp++] = q
                }
            }
            if (area > bestArea) { bestArea = area; best = intArrayOf(l, tp, r + 1, b + 1) }
        }
        val bb = best ?: return null
        val bw = bb[2] - bb[0]; val bh = bb[3] - bb[1]
        // Shaped like a label: big enough, fills most of its box, portrait (4x6 is 1.5) or
        // landscape (6x4, the small Priority Mail labels).
        if (bestArea < w * h * 0.15 * (if (bw > bh) 0.6 else 1.0)) return null
        if (bestArea < 0.6 * bw * bh) return null
        val aspect = max(bw, bh).toDouble() / max(1, min(bw, bh))
        if (aspect < 1.05 || aspect > 2.3) return null
        if (bb[0] <= 1 && bb[1] <= 1 && bb[2] >= w - 1 && bb[3] >= h - 1) return null
        val f = img.width.toDouble() / w
        return IntRect((bb[0] * f).toInt(), (bb[1] * f).toInt(), min(img.width, (bb[2] * f).toInt()), min(img.height, (bb[3] * f).toInt()))
    }

    private fun notReady(
        issue: Issue, msg: String, barcode: NormRect?, label: NormRect?,
        sharp: Double = 0.0, tilt: Double = 0.0, aligned: Boolean = false, focused: Boolean = false,
    ): Status {
        readyStreak = 0
        return Status(false, issue, msg, aligned, focused, sharp, tilt, barcode, label)
    }

    private fun overlap(a: IntRect, b: IntRect): Double {
        val i = a.intersect(b)
        if (i.isEmpty) return 0.0
        return i.width.toDouble() * i.height / max(1, a.width * a.height)
    }

    companion object {
        /** A 2:3 portrait label (4×6 in.) filling 86% of the width of a 3:4 portrait frame. */
        val DEFAULT_GUIDE: NormRect = run {
            val w = 0.86f
            val h = w * 3f * 1.5f / 4f // label height as a fraction of the frame height
            NormRect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
        }
        /** A 3:2 landscape label (6×4 in.) filling 92% of the width of the 3:4 portrait frame. */
        val LANDSCAPE_GUIDE: NormRect = run {
            val w = 0.92f
            val h = w * 3f / 1.5f / 4f
            NormRect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
        }
        const val MIN_BARCODE_WIDTH = 0.38
        /** On a landscape label the barcode spans less of the label's width. */
        const val MIN_BARCODE_WIDTH_LANDSCAPE = 0.28
        /** Frames in a row the paper must agree on before the marks turn. */
        const val ORIENTATION_FRAMES = 3
    }
}
