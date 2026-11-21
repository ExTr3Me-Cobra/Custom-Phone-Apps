package com.labelrescue.core.usps

import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrLine
import kotlin.math.max
import kotlin.math.min

/**
 * Where the barcodes sit on a label, from its printed text (frame coordinates).
 *
 * The layout is the same on every USPS label: the IMpb barcode sits between "USPS TRACKING #"
 * and the printed number. UPS Ground Saver / SurePost labels add a UPS block higher up: "TRACKING
 * #: 1Z…" with the UPS Code 128 right under it, and the USPS service banner below that. The UPS
 * barcode encodes letters (1Z…), so the USPS partial decoder only finds noise there. [ups] is
 * that block, to leave out; [usps] is where the USPS barcode should be, to read first.
 */
data class LabelZones(val ups: IntRect?, val usps: IntRect?) {

    /** True if [r] lies mostly inside the UPS barcode block. */
    fun inUps(r: IntRect): Boolean {
        val u = ups ?: return false
        val i = u.intersect(r)
        if (i.isEmpty) return false
        return i.width.toLong() * i.height >= 0.6 * r.width.toLong() * r.height
    }

    /** True if the vertical middle of [r] lies inside the UPS block (for thin barcode scan bands). */
    fun centerInUps(r: IntRect): Boolean {
        val u = ups ?: return false
        val cy = (r.top + r.bottom) / 2; val cx = (r.left + r.right) / 2
        return cy in u.top..u.bottom && cx in u.left..u.right
    }

    companion object {
        val NONE = LabelZones(null, null)

        private val UPS_LINE = Regex("TRACK\\w*\\s*#?\\s*:?\\s*[1IL]\\s?[Z2]\\s?[0-9A-Z]")
        private val USPS_TRACKING = Regex("USPS\\s*TRACK|TRACKING\\s*#?\\s*$|USPS\\s*TRAC")

        /**
         * Finds the zones from OCR lines with boxes in frame coordinates. [numberLines] are boxes of
         * lines that look like the USPS number (see OcrInterpreter.numberLineBoxes).
         */
        fun find(lines: List<Pair<OcrLine, IntRect?>>, numberLines: List<IntRect>, width: Int, height: Int): LabelZones {
            val bounds = IntRect(0, 0, width, height)
            val boxed = lines.mapNotNull { (l, b) -> b?.let { l.text.uppercase() to it } }
            val upsLine = boxed.filter { (t, _) -> "USPS" !in t && UPS_LINE.containsMatchIn(t) }.minByOrNull { it.second.top }?.second
            val uspsLine = boxed.filter { (t, _) -> USPS_TRACKING.containsMatchIn(t) && (upsLine == null || !UPS_LINE.containsMatchIn(t)) }
                .maxByOrNull { it.second.top }?.second
            val number = numberLines.filter { upsLine == null || it.top > upsLine.bottom }.minByOrNull { it.top }

            val ups = upsLine?.let { u ->
                val h = max(12, u.height)
                // Everything printed below the UPS line that says USPS (the service banner, USPS
                // TRACKING #) or the USPS number ends the UPS block.
                val stop = boxed.filter { (t, b) -> b.top > u.bottom && ("USPS" in t || b == uspsLine) }.minOfOrNull { it.second.top }
                    ?: number?.top
                val bottom = min(stop ?: (u.bottom + 9 * h), u.bottom + 9 * h)
                val w = u.width
                IntRect(u.left - w / 4, u.top, u.right + w / 3, bottom).intersect(bounds)
            }?.takeIf { !it.isEmpty }

            val usps = when {
                uspsLine != null && number != null && number.top > uspsLine.bottom ->
                    IntRect(min(uspsLine.left, number.left) - number.width / 8, uspsLine.top, max(uspsLine.right, number.right) + number.width / 8, number.top + number.height / 3)
                number != null -> {
                    val lw = number.width
                    IntRect(number.left - lw / 6, number.top - (lw * 0.45).toInt(), number.right + lw / 6, number.top + number.height / 3)
                }
                uspsLine != null -> {
                    val h = max(12, uspsLine.height)
                    IntRect(uspsLine.left - uspsLine.width / 4, uspsLine.top, uspsLine.right + uspsLine.width * 2, uspsLine.bottom + 9 * h)
                }
                else -> null
            }?.intersect(bounds)?.takeIf { !it.isEmpty && (ups == null || !it.isEmpty && ups.intersect(it).height < it.height / 2) }
            return LabelZones(ups, usps)
        }
    }
}
