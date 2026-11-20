package com.labelrescue.core.capture

import com.labelrescue.core.barcode.BarcodeLocator
import com.labelrescue.core.fusion.NormRect
import com.labelrescue.core.image.Filters
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.abs
import kotlin.math.max

/** Live capture check: is the label lined up in the guide box and in focus? */
class CaptureGuide(
    /** The guide box, as fractions of the (upright) camera frame. */
    val guide: NormRect = DEFAULT_GUIDE,
    private val minWidthFraction: Double = 0.55,
    private val maxTiltDegrees: Double = 6.0,
    /** Absolute sharpness floor (gradient RMS on the downscaled barcode area). */
    private val minSharpness: Double = 14.0,
) {
    enum class Issue { NO_BARCODE, TOO_SMALL, OFF_CENTER, TILTED, TOO_DARK, GLARE, BLURRY }

    data class Status(
        val ready: Boolean,
        val issue: Issue?,
        val message: String,
        val aligned: Boolean,
        val focused: Boolean,
        val sharpness: Double,
        val tiltDegrees: Double,
        /** Where the barcode was found (fractions of the frame), for drawing. */
        val barcode: NormRect?,
    )

    private val recentSharpness = ArrayDeque<Double>()
    private var readyStreak = 0

    /** Call with each analyzed frame (upright). Keeps a little history for focus and stability. */
    @Synchronized
    fun evaluate(frame: GrayImage): Status {
        val small = frame.limited(640)
        val sx = small.width.toDouble(); val sy = small.height.toDouble()
        val g = guide.toPixels(small.width, small.height)
        val regions = BarcodeLocator.locate(small, 4).filter { !it.vertical }
        // The barcode most inside the guide.
        val best = regions.maxByOrNull { overlap(it.rect, g) * it.rect.width }
        if (best == null || overlap(best.rect, g) < 0.3) return notReady(Issue.NO_BARCODE, "Fit the barcode and the number below it inside the box", null, 0.0, 0.0)
        val r = best.rect
        val norm = NormRect((r.left / sx).toFloat(), (r.top / sy).toFloat(), (r.right / sx).toFloat(), (r.bottom / sy).toFloat())
        val crop = small.crop(r.intersect(IntRect(0, 0, small.width, small.height)))
        val tilt = Filters.estimateSkew(crop)
        val mean = crop.mean()
        val sharp = Filters.sharpness(crop)
        recentSharpness.addLast(sharp)
        while (recentSharpness.size > 30) recentSharpness.removeFirst()
        val peak = recentSharpness.max()

        val widthFrac = r.width.toDouble() / g.width
        val cx = (r.left + r.right) / 2.0; val gcx = (g.left + g.right) / 2.0
        val inside = r.left >= g.left - g.width * 0.06 && r.right <= g.right + g.width * 0.06 &&
            r.top >= g.top - g.height * 0.15 && r.bottom <= g.bottom + g.height * 0.15
        val centered = abs(cx - gcx) <= g.width * 0.12
        val aligned = widthFrac >= minWidthFraction && inside && centered && abs(tilt) <= maxTiltDegrees
        val focused = sharp >= minSharpness && sharp >= 0.8 * peak

        val (issue, msg) = when {
            widthFrac < minWidthFraction -> Issue.TOO_SMALL to "Move closer: the barcode should fill most of the box"
            !inside || !centered -> Issue.OFF_CENTER to "Center the barcode in the box"
            abs(tilt) > maxTiltDegrees -> Issue.TILTED to "Straighten the phone (barcode tilted ${abs(tilt).toInt()}°)"
            mean < 45 -> Issue.TOO_DARK to "More light needed (try the torch)"
            mean > 238 -> Issue.GLARE to "Too much glare: tilt the phone slightly"
            !focused -> Issue.BLURRY to "Hold steady, focusing…"
            else -> null to "Lined up and sharp"
        }
        if (issue != null) return notReady(issue, msg, norm, sharp, tilt, aligned, focused)
        readyStreak++
        // Two good checks in a row, so a passing blur doesn't count.
        val ready = readyStreak >= 2
        return Status(ready, null, if (ready) "Lined up and sharp: tap Burst capture" else "Hold still…", aligned, focused, sharp, tilt, norm)
    }

    private fun notReady(issue: Issue, msg: String, norm: NormRect?, sharp: Double, tilt: Double, aligned: Boolean = false, focused: Boolean = false): Status {
        readyStreak = 0
        return Status(false, issue, msg, aligned, focused, sharp, tilt, norm)
    }

    private fun overlap(a: IntRect, b: IntRect): Double {
        val i = a.intersect(b)
        if (i.isEmpty) return 0.0
        return i.width.toDouble() * i.height / max(1, a.width * a.height)
    }

    companion object {
        /** Wide band across the middle of a portrait frame: barcode plus the number under it. */
        val DEFAULT_GUIDE = NormRect(0.05f, 0.34f, 0.95f, 0.62f)
    }
}
