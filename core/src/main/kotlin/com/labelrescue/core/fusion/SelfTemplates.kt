package com.labelrescue.core.fusion

import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads damaged printed digits against the clearly printed digits on the same label: same
 * printer, font, stroke width, blur and lighting. The clear digits become templates; each
 * uncertain position is scored against them.
 *
 * Two things make this work on faded and half-erased print where OCR and the digit model don't:
 *  - Positions come from the clear digits' spacing (the number is printed at a fixed pitch, in
 *    groups of four), not from the uncertain digit's own box. A faded 6 whose surviving stroke
 *    OCR boxes as a narrow "1" is still cropped where a whole digit sits.
 *  - The comparison is lopsided: damage removes ink far more often than it adds it. Ink where
 *    the template has none costs fully; template ink that's missing costs a fraction, and the
 *    template may be lightened (fading).
 */
object SelfTemplates {
    private const val SIZE = 24
    /** Cost of ink the template doesn't have, and of template ink that's missing. */
    private const val EXTRA = 1.0
    private const val MISSING = 0.3
    /** Temperature: cost (in pixels of full mismatch) per unit of log probability. */
    private const val TAU = 6.0

    /**
     * Per-position distributions (null where not scored: anchors, or nothing to compare), from
     * one frame. [boxes] are the OCR boxes per position (frame pixels); [anchor] says which
     * positions are clear and what they read.
     */
    fun read(frame: GrayImage, format: TrackingFormat, boxes0: List<IntRect?>, anchor: (Int) -> Char?): List<DoubleArray?>? {
        val n = format.length
        // The clear digits' own ink (their OCR boxes can be loose): where they really sit.
        val boxes = boxes0.mapIndexed { i, b -> if (b != null && anchor(i) != null) com.labelrescue.core.ml.CharCrop.glyphAt(frame, b) else b }
        val groupOf = IntArray(n).also { g -> var i = 0; for ((gi, len) in TrackingFormat.groups(n).withIndex()) repeat(len) { if (i < n) g[i++] = gi } }
        val anchors = (0 until n).filter { boxes.getOrNull(it) != null && anchor(it) != null }
        if (anchors.size < 6 || anchors.map { anchor(it) }.distinct().size < 3) return null
        // Where each position sits: x = a + b·i + c·group(i) (fixed pitch, a gap per group), and
        // y and height from the anchors (y may tilt with i).
        val xs = anchors.map { boxes[it]!!.centerX }; val ys = anchors.map { (boxes[it]!!.top + boxes[it]!!.bottom) / 2.0 }
        val fx = lsq(anchors.map { doubleArrayOf(1.0, it.toDouble(), groupOf[it].toDouble()) }, xs) ?: return null
        val fy = lsq(anchors.map { doubleArrayOf(1.0, it.toDouble()) }, ys) ?: return null
        val h = anchors.map { boxes[it]!!.height }.sorted()[anchors.size / 2].toDouble()
        val pitch = fx[1]
        if (pitch < 0.3 * h || pitch > 1.5 * h) return null
        // Ink and paper levels of the clear digits, for every crop alike (so faded stays light).
        val levels = ArrayList<Int>()
        for (i in anchors) { val b = boxes[i]!!; for (y in b.top until b.bottom step 2) for (x in b.left until b.right step 2) if (x in 0 until frame.width && y in 0 until frame.height) levels += frame[x, y] }
        levels.sort()
        if (levels.size < 50) return null
        val ink = levels[(levels.size * 0.05).toInt()].toDouble(); val paper = levels[(levels.size * 0.95).toInt()].toDouble()
        if (paper - ink < 30) return null
        fun crop(i: Int): DoubleArray? {
            val cx = fx[0] + fx[1] * i + fx[2] * groupOf[i]; val cy = fy[0] + fy[1] * i
            // One digit cell: the pitch wide (no neighbours), a little taller than the digits.
            val cw = 0.95 * pitch; val ch = 1.15 * h
            val x0 = cx - cw / 2; val y0 = cy - ch / 2
            if (x0 < 0 || y0 < 0 || x0 + cw > frame.width - 1 || y0 + ch > frame.height - 1) return null
            val sx = cw / SIZE; val sy = ch / SIZE
            return DoubleArray(SIZE * SIZE) { k -> ((paper - frame.sample(x0 + (k % SIZE + 0.5) * sx, y0 + (k / SIZE + 0.5) * sy)) / (paper - ink)).coerceIn(0.0, 1.0) }
        }
        val crops = (0 until n).map { crop(it) }
        val templates = HashMap<Char, DoubleArray>(); val counts = HashMap<Char, Int>()
        for (i in anchors) { val c = crops[i] ?: continue; val d = anchor(i)!!; val t = templates.getOrPut(d) { DoubleArray(SIZE * SIZE) }; for (k in t.indices) t[k] += c[k]; counts[d] = (counts[d] ?: 0) + 1 }
        for ((d, t) in templates) { val c = counts[d]!!; for (k in t.indices) t[k] /= c }
        if (templates.size < 3) return null
        return (0 until n).map { i ->
            val q = crops[i]
            if (q == null || i in anchors) null else {
                val cost = DoubleArray(10) { Double.NaN }
                for ((d, t) in templates) cost[d - '0'] = bestCost(q, t)
                val known = cost.filter { !it.isNaN() }
                // Digits with no clear example on the label: as likely as the median template.
                val neutral = known.sorted()[known.size / 2]
                val ll = DoubleArray(10) { -(if (cost[it].isNaN()) neutral else cost[it]) / TAU }
                val m = ll.max()
                val p = DoubleArray(10) { exp(ll[it] - m) }
                val s = p.sum(); DoubleArray(10) { p[it] / s }
            }
        }
    }

    /** Lowest lopsided cost over small shifts and fade levels of the template. */
    private fun bestCost(q: DoubleArray, t: DoubleArray): Double {
        var best = Double.MAX_VALUE
        for (gain in doubleArrayOf(1.0, 0.75, 0.55, 0.4)) for (dy in -2..2) for (dx in -4..4) {
            var c = 0.0
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val tx = x - dx; val ty = y - dy
                val tv = if (tx in 0 until SIZE && ty in 0 until SIZE) t[ty * SIZE + tx] * gain else 0.0
                val d = q[y * SIZE + x] - tv
                c += if (d > 0) EXTRA * d * d else MISSING * d * d
            }
            if (c < best) best = c
        }
        return best
    }

    /** Least squares: coefficients for rows [a] against [b], or null if degenerate. */
    private fun lsq(a: List<DoubleArray>, b: List<Double>): DoubleArray? {
        val k = a[0].size
        val m = Array(k) { DoubleArray(k + 1) }
        for ((r, row) in a.withIndex()) for (i in 0 until k) { for (j in 0 until k) m[i][j] += row[i] * row[j]; m[i][k] += row[i] * b[r] }
        // Light ridge, so a missing group spread (all anchors in one group) stays solvable.
        for (i in 0 until k) m[i][i] += 1e-6
        for (c in 0 until k) {
            val p = (c until k).maxBy { kotlin.math.abs(m[it][c]) }
            if (kotlin.math.abs(m[p][c]) < 1e-9) return null
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            for (r in 0 until k) if (r != c) { val f = m[r][c] / m[c][c]; for (j in c..k) m[r][j] -= f * m[c][j] }
        }
        return DoubleArray(k) { m[it][k] / m[it][it] }
    }
}
