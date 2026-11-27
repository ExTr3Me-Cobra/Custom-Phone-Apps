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
 * With a [LearnedLayout] (positions measured on intact labels) those take precedence over the rules.
 */
data class LabelZones(val ups: IntRect?, val usps: IntRect?, val learned: Boolean = false, val fedex: IntRect? = null) {

    /** Printed text that locates the barcodes. */
    data class Anchors(val upsLine: IntRect?, val uspsLine: IntRect?, val number: IntRect?)

    /**
     * True if [r] lies mostly inside the UPS barcode block or the FedEx one (another carrier's
     * number, not the USPS one). FedEx's site doesn't show the USPS number, so FedEx numbers are
     * simply left out.
     */
    fun inUps(r: IntRect): Boolean = listOfNotNull(ups, fedex).any { u ->
        val i = u.intersect(r)
        !i.isEmpty && i.width.toLong() * i.height >= 0.6 * r.width.toLong() * r.height
    }

    /** True if the middle of [r] lies inside the UPS or FedEx block (for thin barcode scan bands). */
    fun centerInUps(r: IntRect): Boolean = listOfNotNull(ups, fedex).any { u ->
        val cy = (r.top + r.bottom) / 2; val cx = (r.left + r.right) / 2
        cy in u.top..u.bottom && cx in u.left..u.right
    }

    companion object {
        val NONE = LabelZones(null, null)

        /** "FedEx Tracking ID# …" (FedEx Ground Economy / SmartPost labels hand off to USPS). */
        private val FEDEX_LINE = Regex("FED\\s*EX\\s*TRACK|FEDEX\\s*TRK|TRACKING\\s*ID\\s*#?\\s*:?\\s*\\d{4}\\s?\\d{4}\\s?\\d{4}\\b")
        /** FedEx Ground's own barcode line: "96" + 20 digits, printed with "(000 000 0000)". */
        private val FEDEX_96 = Regex("^\\s*96\\d{6,}|\\(\\s*0{3}\\s*0{3}\\s*0{4}\\s*\\)")
        private val UPS_LINE = Regex("TRACK\\w*\\s*#?\\s*:?\\s*[1IL]\\s?[Z2]\\s?[0-9A-Z]")
        // Banner text (Publication 199, 4.5): "USPS TRACKING #" or "USPS SIGNATURE TRACKING #", or
        // spelled out "... TRACKING NUMBER" on printers without "#".
        private val USPS_TRACKING = Regex("USPS\\s*TRACK|SIGNATURE\\s*TRACK|TRACKING\\s*(#|NUMBER)?\\s*$|USPS\\s*TRAC")

        /** The anchor lines, from OCR lines with boxes in frame coordinates. */
        fun anchors(lines: List<Pair<OcrLine, IntRect?>>, numberLines: List<IntRect>): Anchors {
            val boxed = lines.mapNotNull { (l, b) -> b?.let { l.text.uppercase() to it } }
            val upsLine = boxed.filter { (t, _) -> "USPS" !in t && UPS_LINE.containsMatchIn(t) }.minByOrNull { it.second.top }?.second
            val uspsLine = boxed.filter { (t, _) -> USPS_TRACKING.containsMatchIn(t) && !UPS_LINE.containsMatchIn(t) }
                .maxByOrNull { it.second.top }?.second
            // The USPS number line: a line that reads like one (18+ digits, from 9), else the number-
            // like line nearest below "USPS TRACKING #", else the first one below the UPS block.
            // (Not just the topmost: account IDs like Stamps.com's sit higher up on the label.)
            val below = numberLines.filter { upsLine == null || it.top > upsLine.bottom }
            val readsLikeOne = boxed.filter { (t, b) ->
                val d = t.filter { it.isDigit() }
                d.length >= 18 && d[0] == '9' && !d.startsWith("96") && !FEDEX_96.containsMatchIn(t) && (upsLine == null || b.top > upsLine.bottom)
            }.map { it.second }
            val number = readsLikeOne.maxByOrNull { it.top }
                ?: uspsLine?.let { u -> below.filter { it.top > u.bottom }.minByOrNull { it.top } }
                ?: below.minByOrNull { it.top }
            return Anchors(upsLine, uspsLine, number)
        }

        /**
         * Finds the zones from OCR lines with boxes in frame coordinates. [numberLines] are boxes of
         * lines that look like the USPS number (see OcrInterpreter.numberLineBoxes).
         */
        fun find(
            lines: List<Pair<OcrLine, IntRect?>>, numberLines: List<IntRect>, width: Int, height: Int,
            learned: LearnedLayout? = null,
        ): LabelZones {
            val bounds = IntRect(0, 0, width, height)
            val a = anchors(lines, numberLines)
            val boxed = lines.mapNotNull { (l, b) -> b?.let { l.text.uppercase() to it } }
            val (upsLine, uspsLine, number) = a

            val learnedUps = upsLine?.let { learned?.area(LayoutSample.Target.UPS, LayoutSample.Anchor.UPS_LINE, it) }
            val ups = (learnedUps ?: upsLine?.let { u ->
                val h = max(12, u.height)
                // Everything printed below the UPS line that says USPS (the service banner, USPS
                // TRACKING #) or the USPS number ends the UPS block.
                val stop = boxed.filter { (t, b) -> b.top > u.bottom && ("USPS" in t || b == uspsLine) }.minOfOrNull { it.second.top }
                    ?: number?.top
                val bottom = min(stop ?: (u.bottom + 9 * h), u.bottom + 9 * h)
                val w = u.width
                IntRect(u.left - w / 4, u.top, u.right + w / 3, bottom)
            })?.intersect(bounds)?.takeIf { !it.isEmpty }

            val learnedUsps = learned?.let { l ->
                number?.let { l.area(LayoutSample.Target.USPS, LayoutSample.Anchor.NUMBER, it) }
                    ?: uspsLine?.let { l.area(LayoutSample.Target.USPS, LayoutSample.Anchor.USPS_LINE, it) }
            }
            val usps = (learnedUsps ?: when {
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
            })?.intersect(bounds)?.takeIf { !it.isEmpty && (ups == null || ups.intersect(it).height < it.height / 2) }
            // FedEx: from the "FedEx Tracking ID" line (or FedEx's own "96…" barcode line) down to
            // the next USPS text, at most nine line heights: FedEx's barcode sits in there.
            val fedexLine = boxed.filter { (t, _) -> FEDEX_LINE.containsMatchIn(t) || FEDEX_96.containsMatchIn(t) }.minByOrNull { it.second.top }?.second
            val fedex = fedexLine?.let { f ->
                val h = max(12, f.height)
                val stop = boxed.filter { (t, b) -> b.top > f.bottom + h && ("USPS" in t || "FORWARD" in t || "DELIVER" in t || b == uspsLine) }.minOfOrNull { it.second.top }
                    ?: number?.top?.takeIf { it > f.bottom }
                val bottom = min(stop ?: (f.bottom + 9 * h), f.bottom + 9 * h)
                IntRect(f.left - f.width / 3, f.top - h / 2, f.right + f.width / 3, bottom)
            }?.intersect(bounds)?.takeIf { !it.isEmpty }
            val uspsKept = usps?.takeIf { u -> fedex == null || fedex.intersect(u).height < u.height / 2 }
            return LabelZones(ups, uspsKept, learned = learnedUps != null || (learnedUsps != null && usps == learnedUsps.intersect(bounds)), fedex = fedex)
        }
    }
}
