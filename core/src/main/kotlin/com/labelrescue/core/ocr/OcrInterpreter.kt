package com.labelrescue.core.ocr

import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.fusion.Observation
import com.labelrescue.core.fusion.Source
import com.labelrescue.core.image.Affine
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns OCR lines into per-position evidence for the printed tracking number.
 *
 * The printed number is grouped 4-4-4-4-4-2. Characters are placed into positions by their
 * x-positions (pitch and group gaps fitted per line), so a character that is smudged away
 * becomes an unknown position instead of shifting every digit after it.
 */
object OcrInterpreter {

    private class Ch(
        val c: Char, val conf: Float, val u: Double, val v: Double, val w: Double, val h: Double, val element: Int, val box: IntRect?,
        val alts: Map<Char, Float>? = null,
    ) {
        /** A smudge mark standing where a digit should be. */
        val placeholder: Boolean get() = DigitConfusion.digitFor(c) == null && c in DigitConfusion.SMUDGE
    }

    /** Length priors: 22-digit IMpb is by far the most common; legacy 20-digit labels are rare. */
    private fun formatPenalty(f: TrackingFormat) = when (f) {
        TrackingFormat.IMPB_22 -> 0.0
        TrackingFormat.IMPB_26 -> 2.0
        else -> 4.0
    }

    class Alignment(val format: TrackingFormat, val assigned: IntArray, val cost: Double, val u0: Double, val pitch: Double, val gap: Double, val matched: Int)

    private val TRACKING_FORMATS = listOf(TrackingFormat.IMPB_22, TrackingFormat.IMPB_26, TrackingFormat.IMPB_20)
    private val S10_REGEX = Regex("([A-Z]{2})\\s?([0-9OIlSB]{3})\\s?([0-9OIlSB]{3})\\s?([0-9OIlSB]{3})\\s?([A-Z]{2})")

    /**
     * Re-alignment hint: the current per-position evidence for [format] (from all sources,
     * including the barcode's shift-free reading). With it, a character OCR dropped shows up
     * as a gap because the following digits agree with the reference one position later.
     * [xWeight] < 1 trusts character x-positions less (PaddleOCR's come from CTC time steps).
     */
    class Reference(val format: TrackingFormat, val slots: List<SlotDist>, val xWeight: Double = 1.0)

    fun interpret(
        page: OcrPage, toFrame: Affine, frame: Int, variant: String, source: Source = Source.OCR, ref: Reference? = null,
    ): List<Observation> {
        val out = ArrayList<Observation>()
        for (line in page.lines) {
            interpretLine(line, toFrame, frame, variant, source, ref)?.let { out += it }
            s10(line, frame, variant)?.let { out += it }
        }
        return out
    }

    /** Boxes (in the recognized image's coordinates) of lines that look like the tracking number. */
    fun numberLineBoxes(page: OcrPage): List<IntRect> =
        page.lines.filter { l -> l.text.count { it.isDigit() } >= 12 }.mapNotNull { it.box }

    private fun chars(line: OcrLine): List<Ch> {
        val a = Math.toRadians(line.angle.toDouble())
        val ca = cos(a); val sa = sin(a)
        val out = ArrayList<Ch>()
        for ((ei, el) in line.elements.withIndex()) {
            val syms = if (el.symbols.isNotEmpty() && el.symbols.all { it.box != null }) el.symbols
            else synthesize(el)
            for (s in syms) {
                for ((k, c) in s.text.withIndex()) {
                    val b = s.box
                    if (b == null) {
                        out += Ch(c, s.confidence, out.size.toDouble(), 0.0, 1.0, 1.0, ei, null, s.alternatives)
                    } else {
                        val n = s.text.length
                        val cw = b.width.toDouble() / n
                        val cx = b.left + cw * (k + 0.5); val cy = b.centerY
                        out += Ch(c, s.confidence, cx * ca + cy * sa, -cx * sa + cy * ca, cw, b.height.toDouble(), ei, b, s.alternatives)
                    }
                }
            }
        }
        return out.sortedBy { it.u }
    }

    /** Splits an element box evenly between its characters when no symbol boxes are given. */
    private fun synthesize(el: OcrElement): List<OcrSymbol> {
        val b = el.box ?: return el.text.map { OcrSymbol(it.toString(), el.confidence, null) }
        val n = el.text.length.coerceAtLeast(1)
        return el.text.mapIndexed { i, c ->
            val l = b.left + b.width * i / n; val r = b.left + b.width * (i + 1) / n
            OcrSymbol(c.toString(), el.confidence, IntRect(l, b.top, r, b.bottom))
        }
    }

    private fun interpretLine(line: OcrLine, toFrame: Affine, frame: Int, variant: String, source: Source, ref: Reference?): Observation? {
        val all = chars(line)
        // Keep the elements that are mostly digits (drops "USPS", "TRACKING", "#").
        val numericElements = all.groupBy { it.element }.filter { (_, cs) ->
            val digits = cs.count { it.c.isDigit() }
            val like = cs.count { DigitConfusion.digitFor(it.c) != null || it.placeholder }
            digits * 2 >= cs.size && like * 4 >= cs.size * 3
        }.keys
        val cs = all.filter { it.element in numericElements && (DigitConfusion.digitFor(it.c) != null || it.placeholder) }
        if (cs.size < 14) return null

        var best: Alignment? = null
        for (f in TRACKING_FORMATS) {
            if (ref != null && f != ref.format) continue
            if (cs.size < f.length - 4 || cs.size > f.length + 3) continue
            val a = align(cs, f, ref) ?: continue
            // A label cut off at the edge loses leading digits: a 22-digit number with two missing
            // beats a "complete" legacy 20-digit read.
            if (best == null || a.cost + formatPenalty(a.format) < best.cost + formatPenalty(best.format)) best = a
        }
        val al = best ?: return null
        val f = al.format
        if (al.matched < f.length - 4) return null
        // (With a reference, the cost also counts disagreement with it, so allow more.)
        if (al.cost / f.length > (if (ref != null) 2.5 else 0.9)) return null

        val slots = ArrayList<SlotDist?>(f.length)
        val boxes = ArrayList<IntRect?>(f.length)
        val quality = ArrayList<Double>(f.length)
        val meanV = cs.map { it.v }.average()
        val charH = cs.map { it.h }.sorted()[cs.size / 2]
        val a = Math.toRadians(line.angle.toDouble())
        val ca = cos(a); val sa = sin(a)
        var confSum = 0.0
        for (i in 0 until f.length) {
            val j = al.assigned[i]
            if (j >= 0) {
                val ch = cs[j]
                val d = if (ch.placeholder) null
                    else ch.alts?.let { DigitConfusion.fromAlternatives(it) } ?: DigitConfusion.distribution(ch.c, ch.conf)
                slots += d
                confSum += d?.bestP ?: 0.0
                // Tight character box (the digit model is trained on these; UI crops add padding).
                boxes += ch.box?.let { toFrame.mapRect(it) }
                quality += if (ch.box != null) 1.0 else 0.0
            } else {
                slots += null
                // Where the missing character should be: estimated from the fitted pitch.
                val u = al.u0 + al.pitch * i + al.gap * groupIndex(f, i)
                val cx = u * ca - meanV * sa; val cy = u * sa + meanV * ca
                val half = cs.map { it.w }.sorted()[cs.size / 2] / 2
                val r = IntRect((cx - half).roundToInt(), (cy - charH / 2).roundToInt(), (cx + half).roundToInt(), (cy + charH / 2).roundToInt())
                boxes += toFrame.mapRect(r)
                quality += 0.5
            }
        }
        val weight = (confSum / f.length).coerceIn(0.05, 1.0)
        return Observation(source, f, slots, weight, frame, variant, boxes, quality)
    }

    private fun groupIndex(f: TrackingFormat, i: Int) = i / 4

    /** Dynamic-programming alignment of characters to positions along the line. */
    fun alignForTest(us: List<Double>, elements: List<Int>, f: TrackingFormat): Alignment? =
        align(us.mapIndexed { i, u -> Ch('0', 1f, u, 0.0, 1.0, 1.0, elements[i], null) }, f, null)

    private fun align(cs: List<Ch>, f: TrackingFormat, ref: Reference?): Alignment? {
        val m = cs.size
        val L = f.length
        val within = ArrayList<Double>(); val between = ArrayList<Double>()
        for (j in 1 until m) {
            val d = cs[j].u - cs[j - 1].u
            if (cs[j].element == cs[j - 1].element) within += d else between += d
        }
        var pitch = if (within.size >= 3) within.sorted()[within.size / 2] else cs.map { it.w }.sorted()[m / 2] * 1.1
        if (pitch <= 0) pitch = 1.0
        var gap = if (between.isNotEmpty()) (between.sorted()[between.size / 2] - pitch).coerceIn(0.0, 3 * pitch) else 0.0

        var best: Alignment? = null
        val gaps = listOf(gap, 0.0, 0.5 * pitch, pitch, 1.5 * pitch).distinct()
        for (g in gaps) for (j in 0 until min(3, m)) for (i in 0 until min(4, L)) {
            val u0 = cs[j].u - pitch * i - g * groupIndex(f, i)
            val a = dp(cs, f, u0, pitch, g, ref)
            if (best == null || a.cost < best.cost) best = a
        }
        var cur = best ?: return null
        // Refine pitch, gap and origin from the matched characters, then align again.
        repeat(2) {
            val pts = (0 until L).filter { cur.assigned[it] >= 0 }.map { Triple(it.toDouble(), groupIndex(f, it).toDouble(), cs[cur.assigned[it]].u) }
            if (pts.size >= 6) {
                val fit = leastSquares3(pts)
                if (fit != null && fit.second > 0 && fit.third >= -0.5 * fit.second) {
                    val a = dp(cs, f, fit.first, fit.second, fit.third.coerceAtLeast(0.0), ref)
                    if (a.cost <= cur.cost + 1e-9) cur = a
                    pitch = fit.second; gap = fit.third
                }
            }
        }
        return cur
    }

    /**
     * IMpb numbers start with the channel 92-95: a character that can't be there is probably not
     * the first digit (e.g. a cut-off label whose OCR starts at "34...").
     */
    private fun structureCost(f: TrackingFormat, slot: Int, ch: Ch): Double {
        if (f != TrackingFormat.IMPB_22 && f != TrackingFormat.IMPB_26) return 0.0
        if (ch.placeholder) return 0.0
        val d = DigitConfusion.digitFor(ch.c)?.first ?: return 0.0
        return when (slot) {
            0 -> if (d == 9) 0.0 else 2.0
            1 -> if (d in 2..5) 0.0 else 1.2
            else -> 0.0
        }
    }

    /** Disagreement of a character with the reference at [slot] (0 = agrees). */
    private fun refCost(ref: Reference?, slot: Int, ch: Ch): Double {
        val r = ref?.slots?.getOrNull(slot) ?: return 0.0
        if (ch.placeholder || r.alphabet != SlotDist.DIGITS) return 0.0
        val d = (ch.alts?.let { DigitConfusion.fromAlternatives(it) } ?: DigitConfusion.distribution(ch.c, ch.conf)) ?: return 0.0
        var agree = 0.0
        for (k in 0..9) agree += d.probs[k] * r.probs[k]
        return -0.6 * ln(max(0.03, agree))
    }

    private fun dp(cs: List<Ch>, f: TrackingFormat, u0: Double, pitch: Double, gap: Double, ref: Reference? = null): Alignment {
        val xw = ref?.xWeight ?: 1.0
        val m = cs.size; val L = f.length
        val missSlot = 1.0; val extraObs = 1.6
        val cost = Array(m + 1) { DoubleArray(L + 1) { Double.MAX_VALUE } }
        val back = Array(m + 1) { IntArray(L + 1) }
        cost[0][0] = 0.0
        for (j in 0..m) for (i in 0..L) {
            if (j == 0 && i == 0) continue
            var bc = Double.MAX_VALUE; var bb = 0
            if (j > 0 && i > 0 && cost[j - 1][i - 1] < Double.MAX_VALUE) {
                val pos = u0 + pitch * (i - 1) + gap * groupIndex(f, i - 1)
                val d = (cs[j - 1].u - pos) / pitch
                val c = cost[j - 1][i - 1] + xw * min(4.0, d * d) + structureCost(f, i - 1, cs[j - 1]) + refCost(ref, i - 1, cs[j - 1])
                if (c < bc) { bc = c; bb = 0 }
            }
            if (i > 0 && cost[j][i - 1] < Double.MAX_VALUE) {
                // Missing digits at the ends are cheaper: labels get cut off or torn at the edge.
                val c = cost[j][i - 1] + if (i - 1 < 2 || i - 1 >= L - 2) 0.6 else missSlot
                if (c < bc) { bc = c; bb = 1 }
            }
            if (j > 0 && cost[j - 1][i] < Double.MAX_VALUE) {
                val c = cost[j - 1][i] + extraObs
                if (c < bc) { bc = c; bb = 2 }
            }
            cost[j][i] = bc; back[j][i] = bb
        }
        val assigned = IntArray(L) { -1 }
        var j = m; var i = L; var matched = 0
        while (j > 0 || i > 0) {
            when (back[j][i]) {
                0 -> {
                    val pos = u0 + pitch * (i - 1) + gap * groupIndex(f, i - 1)
                    // A "match" more than a pitch away is really a miss plus an extra.
                    if (abs(cs[j - 1].u - pos) / pitch < 0.75 / xw.coerceIn(0.4, 1.0)) { assigned[i - 1] = j - 1; matched++ }
                    j--; i--
                }
                1 -> i--
                else -> j--
            }
        }
        return Alignment(f, assigned, cost[m][L], u0, pitch, gap, matched)
    }

    /** Fits u = a + b·i + c·g. Returns (a, b, c). */
    private fun leastSquares3(pts: List<Triple<Double, Double, Double>>): Triple<Double, Double, Double>? {
        // Normal equations for [1, i, g].
        val m = Array(3) { DoubleArray(4) }
        for ((i, g, u) in pts) {
            val x = doubleArrayOf(1.0, i, g)
            for (r in 0..2) { for (c in 0..2) m[r][c] += x[r] * x[c]; m[r][3] += x[r] * u }
        }
        for (col in 0..2) {
            val piv = (col..2).maxBy { abs(m[it][col]) }
            if (abs(m[piv][col]) < 1e-9) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0..2) if (r != col) {
                val k = m[r][col] / m[col][col]
                for (c in col..3) m[r][c] -= k * m[col][c]
            }
        }
        return Triple(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2])
    }

    private fun s10(line: OcrLine, frame: Int, variant: String): Observation? {
        val t = line.text.uppercase()
        val mt = S10_REGEX.find(t) ?: return null
        val s = mt.groupValues.drop(1).joinToString("")
        if (s.length != 13) return null
        val f = TrackingFormat.S10
        val slots = s.mapIndexed { i, c ->
            if (f.isLetterPosition(i)) SlotDist.likely(c, 0.9, SlotDist.LETTERS)
            else DigitConfusion.distribution(c, line.confidence)
        }
        return Observation(Source.OCR, f, slots, line.confidence.toDouble().takeIf { !it.isNaN() } ?: 0.7, frame, variant)
    }
}
