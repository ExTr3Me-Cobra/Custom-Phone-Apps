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
    )

    private val recentSharpness = ArrayDeque<Double>()
    private var readyStreak = 0

    /** Call with each analyzed frame (upright). Keeps a little history for focus and stability. */
    @Synchronized
    fun evaluate(frame: GrayImage): Status {
        val small = frame.limited(640)
        val all = BarcodeLocator.locate(small, 6)
        val st = evaluate(small, all.filter { !it.vertical })
        val sx = small.width.toDouble(); val sy = small.height.toDouble()
        return st.copy(regions = all.map { r -> NormRect((r.rect.left / sx).toFloat(), (r.rect.top / sy).toFloat(), (r.rect.right / sx).toFloat(), (r.rect.bottom / sy).toFloat()) })
    }

    private fun evaluate(small: GrayImage, regions: List<BarcodeLocator.Region>): Status {
        val sx = small.width.toDouble(); val sy = small.height.toDouble()
        val g = guide.toPixels(small.width, small.height)
        fun norm(r: IntRect) = NormRect((r.left / sx).toFloat(), (r.top / sy).toFloat(), (r.right / sx).toFloat(), (r.bottom / sy).toFloat())

        val paper = paperRect(small)
        val paperNorm = paper?.let(::norm)
        // Paper check first: it says which way to move even before a barcode is readable.
        if (paper != null) {
            val wf = paper.width.toDouble() / g.width; val hf = paper.height.toDouble() / g.height
            val dx = ((paper.left + paper.right) - (g.left + g.right)) / 2.0 / g.width
            val dy = ((paper.top + paper.bottom) - (g.top + g.bottom)) / 2.0 / g.height
            when {
                max(wf, hf) < 0.8 -> return notReady(Issue.TOO_SMALL, "Move closer: line the label's corners up with the 4 marks", null, paperNorm)
                abs(dx) > 0.12 || abs(dy) > 0.12 -> return notReady(Issue.OFF_CENTER, "Move the label to the middle: its corners should meet the 4 marks", null, paperNorm)
            }
        }
        val inside = regions.filter { overlap(it.rect, g) >= 0.7 }
        val best = inside.maxByOrNull { it.rect.width }
            ?: return notReady(Issue.NO_BARCODE, "Line the label's 4 corners up with the corner marks", null, paperNorm)
        val r = best.rect
        val crop = small.crop(r.intersect(IntRect(0, 0, small.width, small.height)))
        val tilt = Filters.estimateSkew(crop)
        val mean = crop.mean()
        val sharp = Filters.sharpness(crop)
        recentSharpness.addLast(sharp)
        while (recentSharpness.size > 30) recentSharpness.removeFirst()
        val peak = recentSharpness.max()

        // On a 4×6 label the main barcode spans roughly 45-95% of the label's width.
        val widthFrac = r.width.toDouble() / g.width
        val paperTooBig = paper != null && (paper.width > g.width * 1.12 && paper.height > g.height * 1.12)
        val aligned = widthFrac >= MIN_BARCODE_WIDTH && !paperTooBig && abs(tilt) <= maxTiltDegrees
        val focused = sharp >= minSharpness && sharp >= 0.8 * peak

        val (issue, msg) = when {
            paperTooBig -> Issue.TOO_CLOSE to "Move back: the whole label should fit inside the 4 marks"
            widthFrac < MIN_BARCODE_WIDTH -> Issue.TOO_SMALL to "Move closer: line the label's corners up with the 4 marks"
            abs(tilt) > maxTiltDegrees -> Issue.TILTED to "Straighten the phone (label tilted ${abs(tilt).toInt()}°)"
            mean < 45 -> Issue.TOO_DARK to "More light needed (try the torch)"
            mean > 238 -> Issue.GLARE to "Too much glare: tilt the phone slightly"
            !focused -> Issue.BLURRY to "Hold steady, focusing…"
            else -> null to "Lined up and sharp"
        }
        if (issue != null) return notReady(issue, msg, norm(r), paperNorm, sharp, tilt, aligned, focused)
        readyStreak++
        // Two good checks in a row, so a passing blur doesn't count.
        val ready = readyStreak >= 2
        return Status(ready, null, if (ready) "Lined up and sharp: tap Burst capture" else "Hold still…", aligned, focused, sharp, tilt, norm(r), paperNorm)
    }

    /**
     * The label's paper: the largest bright area, if it is clearly brighter than its surroundings
     * (brown box, dark table). Null when the background is as bright as the label.
     */
    private fun paperRect(img: GrayImage): IntRect? {
        val s = img.limited(160)
        val hist = IntArray(256)
        for (b in s.data) hist[b.toInt() and 0xFF]++
        val t = Filters.otsu(IntArray(s.data.size) { s.data[it].toInt() and 0xFF })
        // Need two clearly different populations: paper and background.
        var lo = 0.0; var nlo = 0; var hi = 0.0; var nhi = 0
        for (v in 0..255) if (v <= t) { lo += v.toDouble() * hist[v]; nlo += hist[v] } else { hi += v.toDouble() * hist[v]; nhi += hist[v] }
        if (nlo == 0 || nhi == 0 || hi / nhi - lo / nlo < 40) return null
        val w = s.width; val h = s.height
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var best: IntArray? = null; var bestArea = 0
        for (start in 0 until w * h) {
            if (seen[start] || (s.data[start].toInt() and 0xFF) <= t) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var l = w; var tp = h; var r = 0; var b = 0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                area++
                if (x < l) l = x; if (x > r) r = x; if (y < tp) tp = y; if (y > b) b = y
                for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < h - 1) p + w else -1)) {
                    if (q < 0 || seen[q] || (s.data[q].toInt() and 0xFF) <= t) continue
                    seen[q] = true; stack[sp++] = q
                }
            }
            if (area > bestArea) { bestArea = area; best = intArrayOf(l, tp, r + 1, b + 1) }
        }
        val bb = best ?: return null
        // Too small to be the label, or it touches every edge (background as bright as paper).
        if (bestArea < w * h * 0.12) return null
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
        const val MIN_BARCODE_WIDTH = 0.38
    }
}
