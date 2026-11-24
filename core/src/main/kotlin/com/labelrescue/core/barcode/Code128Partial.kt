package com.labelrescue.core.barcode

import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.image.GrayImage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-symbol evidence measured from one image of a (roughly horizontal) Code 128 barcode.
 * Slot 0 is the start symbol, slots 1..[symbolCount] are data symbols ending with the checksum,
 * and slot [symbolCount]+1 is the stop symbol.
 */
class SymbolEvidence(
    val symbolCount: Int,
    /** Probabilities over values 0..105 for each slot. */
    val probs: List<DoubleArray>,
    /** 0..1: how much clean signal each slot had (share of scanlines that read it well). */
    val quality: DoubleArray,
    /** Left edge (x, image pixels) of each slot, averaged over scanlines. */
    val slotX: DoubleArray,
    val moduleWidth: Double,
    val reversed: Boolean,
    val linesUsed: Int,
    val yTop: Int,
    val yBottom: Int,
    /** Grid placed from the outermost bars (start/stop patterns unreadable). */
    val extentAnchored: Boolean = false,
)

/** A digit of the tracking number that the partial decoder filled in or confirmed. */
data class RecoveredDigit(
    /** Index within the tracking number. */
    val position: Int,
    val digit: Char,
    /** Posterior probability after applying the checksums. */
    val confidence: Double,
    /** Best probability from the bars alone, before the checksums. */
    val rawConfidence: Double,
)

class PartialResult(
    val structure: Gs1Structure,
    /** Distributions for every data digit ("420" + ZIP + tracking). */
    val dataDigits: List<SlotDist>,
    val recovered: List<RecoveredDigit>,
    val logZ: Double,
    /** log of the total probability of all symbol sequences that satisfy both checksums. */
    val dataLogZ: Double,
    /** Aggregated, unconstrained symbol probabilities (slots 1..symbolCount). */
    private val symbolProbs: List<DoubleArray>,
    val evidence: SymbolEvidence,
) {
    val trackingDigits: List<SlotDist> get() = dataDigits.subList(structure.routingDigits, structure.dataDigits)
    val zipDigits: List<SlotDist> get() = if (structure.routingDigits > 3) dataDigits.subList(3, structure.routingDigits) else emptyList()

    val bestTracking: String get() = trackingDigits.joinToString("") { it.best.toString() }

    /** Ranks layouts against each other (see [Code128Partial.decodeAll]). */
    val selectionScore: Double get() = ln(max(1e-6, confidence)) + structure.prior

    /** 0..1: how sure the barcode is about its tracking digits (mean best probability, squared). */
    val confidence: Double get() = trackingDigits.map { it.bestP }.average().let { it * it }

    /**
     * Exact log-likelihood of a whole tracking number under the barcode evidence and the mod-103
     * checksum, summing over the ZIP part. Keeps the pair structure that per-digit
     * distributions lose.
     */
    fun logLikelihood(tracking: String): Double {
        if (tracking.length != structure.trackingDigits) return Double.NEGATIVE_INFINITY
        var alpha = DoubleArray(103).also { it[Code128.START_C % 103] = 1.0 }
        var logScale = 0.0
        for ((i, slot) in structure.slots.withIndex()) {
            val p = symbolProbs[i]
            val w = i + 1
            val next = DoubleArray(103)
            val fixedV: Int? = when (slot) {
                Gs1Structure.Slot.Fnc1 -> Code128.FNC1
                is Gs1Structure.Slot.Pair -> {
                    val t = slot.digitIndex - structure.routingDigits
                    if (t >= 0) (tracking[t] - '0') * 10 + (tracking[t + 1] - '0') else null
                }
            }
            for (s in 0 until 103) {
                val a = alpha[s]; if (a == 0.0) continue
                if (fixedV != null) {
                    next[(s + w * fixedV) % 103] += a * p[fixedV]
                } else {
                    for (v in 0..99) if (structure.allows(i, v)) next[(s + w * v) % 103] += a * p[v]
                }
            }
            val sum = next.sum()
            if (sum <= 0) return Double.NEGATIVE_INFINITY
            for (s in 0 until 103) next[s] /= sum
            logScale += ln(sum)
            alpha = next
        }
        val check = symbolProbs[structure.slots.size]
        var z = 0.0
        for (s in 0 until 103) z += alpha[s] * check[s]
        return if (z <= 0) Double.NEGATIVE_INFINITY else logScale + ln(z)
    }
}

/**
 * Partial Code 128 decoder for damaged barcodes.
 *
 * Samples many horizontal scanlines, finds the start/stop patterns to anchor the symbol grid and
 * estimate the module width, then scores every 11-module slot against all symbol patterns using
 * both measured bar/space widths and a template match of the intensity profile. Damaged slots get
 * flat distributions instead of confident wrong ones. The known GS1-128 layout, the mod-103
 * checksum and the USPS mod-10 check digit are then applied exactly (forward–backward over the
 * checksum states) to fill in damaged symbols.
 */
class Code128Partial(private val maxLines: Int = 32) {
    companion object {
        /** Judge bar darkness against nearby ink and paper (see localDark); a switch for comparison runs. */
        @JvmStatic var LOCAL_CONTRAST = System.getenv("LR_GLOBAL_CONTRAST") == null
        /** Bar width offsets (modules) tried when matching templates. */
        val BAR_BIAS = doubleArrayOf(-0.6, -0.3, 0.0, 0.3, 0.6)
    }


    private val samplesPerModule = 3
    private val templates: Array<DoubleArray> = Array(107) { v ->
        val m = Code128.modules(v)
        val raw = DoubleArray(m.size * samplesPerModule) { if (m[it / samplesPerModule]) 1.0 else 0.0 }
        // Soften edges a little, like a slightly blurred print.
        DoubleArray(raw.size) { i ->
            val a = raw[max(0, i - 1)]; val b = raw[i]; val c = raw[min(raw.size - 1, i + 1)]
            0.25 * a + 0.5 * b + 0.25 * c
        }
    }

    /**
     * Templates with bars printed wider or narrower than spec by [BAR_BIAS] modules: thermal
     * labels commonly lose (or spread) ink, shifting every bar edge.
     */
    private val biasTemplates: Array<Array<DoubleArray>> = Array(BAR_BIAS.size) { bi -> Array(107) { v -> template(v, BAR_BIAS[bi]) } }

    private fun template(v: Int, bias: Double): DoubleArray {
        val p = Code128.PATTERNS[v]
        val bars = ArrayList<Pair<Double, Double>>()
        var x = 0.0
        for ((k, w) in p.withIndex()) { if (k % 2 == 0) bars += (x - bias / 2) to (x + w + bias / 2); x += w }
        val n = p.sum() * samplesPerModule
        val raw = DoubleArray(n) { m ->
            val a = m.toDouble() / samplesPerModule; val b = (m + 1.0) / samplesPerModule
            var cover = 0.0
            for ((s0, s1) in bars) cover += (min(b, s1) - max(a, s0)).coerceAtLeast(0.0)
            (cover * samplesPerModule).coerceIn(0.0, 1.0)
        }
        return DoubleArray(n) { i -> 0.25 * raw[max(0, i - 1)] + 0.5 * raw[i] + 0.25 * raw[min(n - 1, i + 1)] }
    }

    /** [masked]: columns under a dead print-head line, where the print says nothing (null: none). */
    private class Line(val y: Int, val dark: DoubleArray, val edges: DoubleArray, val edgeDarkAfter: BooleanArray, val masked: BooleanArray? = null) {
        fun isMasked(x: Double): Boolean { val m = masked ?: return false; val i = x.toInt(); return i in m.indices && m[i] }
        /** True if an edge at [x] is next to a masked column: a dead line's own edge, not a bar's. */
        fun edgeAtMask(x: Double): Boolean { val m = masked ?: return false; val i = x.roundToInt(); for (k in i - 2..i + 1) if (k in m.indices && m[k]) return true; return false }
    }

    private class Anchor(val y: Int, val x: Double, val module: Double)

    private class Oriented(val reversed: Boolean, val lines: List<Line>, val starts: List<Anchor>, val stops: List<Anchor>) {
        val anchors get() = starts.size + stops.size
    }

    /**
     * Measures symbol evidence. Both reading directions are anchored; the one with clearly more
     * start/stop matches is measured (both if it's close, e.g. heavy damage).
     */
    fun measure(img: GrayImage): List<SymbolEvidence> =
        closings(img).flatMap { measure(img, it) }

    /**
     * As [measure], with columns known to be dead print-head lines ([dead] (x, y) in [img]'s
     * pixels) treated as unknown: they're left out of the template match instead of read as white
     * (which reads as extra spaces), and edges they make aren't taken for bar edges. A symbol with
     * a few of its modules hidden still narrows to a few patterns; the checksums do the rest.
     */
    fun measure(img: GrayImage, dead: (Int, Int) -> Boolean): List<SymbolEvidence> =
        closings(img).flatMap { measure(img, it, dead) }

    /**
     * Gap-closing sizes to try: none, plus hairline sizes that stay well under half a module
     * (a closing as wide as a real 1-module space would merge bars). The module is estimated
     * from the median run width (about 2 modules on Code 128).
     */
    fun closings(img: GrayImage): List<Int> {
        if (img.width < 300) return listOf(0)
        val runs = ArrayList<Double>()
        val all = scanlines(img, false)
        if (all.isEmpty()) return listOf(0) // nothing barcode-like (a blank area)
        for (ln in all.subList(all.size / 3, minOf(all.size, maxOf(all.size / 3 + 1, 2 * all.size / 3)))) {
            for (i in 1 until ln.edges.size) runs += ln.edges[i] - ln.edges[i - 1]
        }
        if (runs.size < 20) return listOf(0)
        val module = runs.sorted()[runs.size / 2] / 1.8
        val rMax = kotlin.math.floor((0.75 * module - 1) / 2).toInt().coerceIn(0, 3)
        return (0..rMax).toList()
    }

    fun measure(img: GrayImage, close: Int, dead: ((Int, Int) -> Boolean)? = null): List<SymbolEvidence> {
        val o = listOf(false, true).map { rev ->
            // Gap-closing passes also average a taller band, so dotted (faded) bars read as bars.
            val lines = scanlines(img, rev, close, band = if (close > 0) 4 else 1, dead = dead)
            val starts = ArrayList<Anchor>(); val stops = ArrayList<Anchor>() // stop x = right end of the stop pattern
            for (ln in lines) { findStart(ln)?.let { starts += it }; findStop(ln)?.let { stops += it } }
            Oriented(rev, lines, starts, stops)
        }
        val (a, b) = o
        val use = when {
            a.anchors >= 2 * b.anchors + 2 -> listOf(a)
            b.anchors >= 2 * a.anchors + 2 -> listOf(b)
            else -> o
        }
        val out = use.mapNotNull { measureOriented(it) }.flatten().toMutableList()
        // Faded end bars can hide the start/stop patterns. The outermost bars usually survive:
        // anchor the grid on them (the symbol count then sets the module width).
        if (use.sumOf { it.anchors } < a.lines.size) {
            for (rev in listOf(false, true)) {
                val lines = if (rev) b.lines else a.lines
                val st = ArrayList<Anchor>(); val en = ArrayList<Anchor>()
                for (ln in lines) extent(ln)?.let { (x0, x1) -> st += Anchor(ln.y, x0, Double.NaN); en += Anchor(ln.y, x1, Double.NaN) }
                if (st.size >= max(2, lines.size / 4)) measureOriented(Oriented(rev, lines, st, en))?.let { out += it }
            }
        }
        return out
    }

    /** Diagnostics: best value and quality per slot for symbol count [n], anchored on the outer bars. */
    fun debugExtentSlots(img: GrayImage, close: Int, n: Int): List<String> {
        val lines = scanlines(img, false, close, band = if (close > 0) 4 else 1)
        val st = ArrayList<Anchor>(); val en = ArrayList<Anchor>()
        for (ln in lines) extent(ln)?.let { (x0, x1) -> st += Anchor(ln.y, x0, Double.NaN); en += Anchor(ln.y, x1, Double.NaN) }
        val ev = measureWithCount(lines, st, en, n, false) ?: return listOf("no evidence (${st.size} extents of ${lines.size} lines)")
        return ev.probs.mapIndexed { k, p -> val b = p.indices.maxBy { p[it] }; "$b(${"%.2f".format(p[b])},q${"%.2f".format(ev.quality[k])})" }
    }

    private fun measureOriented(o: Oriented): List<SymbolEvidence>? {
        val lines = o.lines; val starts = o.starts; val stops = o.stops; val reversed = o.reversed
        if (lines.isEmpty()) return null
        if (starts.isEmpty() && stops.isEmpty()) return null

        // Symbol counts to try: from lines with both anchors, else every known layout.
        val counts = LinkedHashSet<Int>()
        val both = starts.mapNotNull { s -> stops.firstOrNull { it.y == s.y }?.let { s to it } }
        val estimates = both.filter { (s, e) -> s.module.isFinite() && e.module.isFinite() }.map { (s, e) ->
            val x = (s.module + e.module) / 2
            (((e.x - s.x) / x - 24) / 11).roundToInt()
        }.filter { it in 3..40 }
        if (estimates.isNotEmpty()) {
            val mode = estimates.groupingBy { it }.eachCount().maxBy { it.value }.key
            counts += mode
            for (c in Gs1Structure.SYMBOL_COUNTS) if (abs(c - mode) <= 1) counts += c
        } else counts += Gs1Structure.SYMBOL_COUNTS

        val out = ArrayList<SymbolEvidence>()
        for (n in counts) {
            measureWithCount(lines, starts, stops, n, reversed)?.let { out += it }
        }
        return out
    }

    /**
     * [close] > 0 fills light gaps up to 2·close+1 px inside bars (dilate then erode the
     * darkness): thermal labels with dead printhead dots have hairline white streaks through
     * every bar, which would otherwise split each bar in two.
     */
    private fun scanlines(img: GrayImage, reversed: Boolean, close: Int = 0, band: Int = 1, dead: ((Int, Int) -> Boolean)? = null): List<Line> {
        val h = img.height; val w = img.width
        if (w < 40 || h < 4) return emptyList()
        val count = min(maxLines, max(1, h / 3))
        val top = (h * 0.08).toInt(); val bottom = (h * 0.92).toInt().coerceAtLeast(top + 1)
        val out = ArrayList<Line>()
        for (i in 0 until count) {
            val y = if (count == 1) h / 2 else top + (bottom - top - 1) * i / (count - 1)
            val prof = DoubleArray(w)
            for (x in 0 until w) {
                val xx = if (reversed) w - 1 - x else x
                var s = 0.0; var nn = 0
                for (yy in max(0, y - band)..min(h - 1, y + band)) { s += img[xx, yy]; nn++ }
                prof[x] = s / nn
            }
            val sorted = prof.sortedArray()
            val lo = sorted[(w * 0.03).toInt()]; val hi = sorted[min(w - 1, (w * 0.97).toInt())]
            if (hi - lo < 30) continue
            var dark = if (LOCAL_CONTRAST && unevenContrast(prof)) localDark(prof, lo, hi) else DoubleArray(w) { ((hi - prof[it]) / (hi - lo)).coerceIn(0.0, 1.0) }
            if (close > 0) dark = erode(dilate(dark, close), close)
            val masked = dead?.let { d -> BooleanArray(w) { x -> d(if (reversed) w - 1 - x else x, y) } }?.takeIf { m -> m.any { it } }
            // Sub-pixel edges where darkness crosses 0.5.
            val edges = ArrayList<Double>(); val after = ArrayList<Boolean>()
            for (x in 1 until w) {
                val a = dark[x - 1]; val b = dark[x]
                if ((a < 0.5) != (b < 0.5)) {
                    edges += x - 1 + (0.5 - a) / (b - a)
                    after += b >= 0.5
                }
            }
            out += Line(y, dark, edges.toDoubleArray(), after.toBooleanArray(), masked)
        }
        return out
    }

    /**
     * True if one side of the line is much fainter than the other (a label faded on one side):
     * the contrast in the outer thirds differs by more than a third. Evenly printed lines keep the
     * line's own levels, which read cleaner there.
     */
    private fun unevenContrast(prof: DoubleArray): Boolean {
        val w = prof.size; val third = w / 3
        if (third < 20) return false
        fun range(a: Int, b: Int): Double { val v = prof.copyOfRange(a, b).sortedArray(); return v[(v.size * 0.95).toInt().coerceAtMost(v.size - 1)] - v[(v.size * 0.05).toInt()] }
        val l = range(0, third); val r = range(w - third, w)
        return min(l, r) < 0.65 * max(l, r)
    }

    /**
     * Darkness judged against the ink and paper nearby rather than the whole line: on a label
     * faded on one side, the faint half's bars are lighter than the global midpoint and vanish.
     * Ink and paper levels are the darkest and lightest values within about two symbols, smoothed.
     * Where there's too little contrast nearby (a quiet zone, blank paper) the line's own levels
     * are used, so noise in blank areas isn't stretched into bars.
     */
    private fun localDark(prof: DoubleArray, lo: Double, hi: Double): DoubleArray {
        val w = prof.size
        val r = max(8, w / 16)
        val mn = DoubleArray(w); val mx = DoubleArray(w)
        for (x in 0 until w) {
            var a = 255.0; var b = 0.0
            for (k in max(0, x - r)..min(w - 1, x + r)) { val v = prof[k]; if (v < a) a = v; if (v > b) b = v }
            mn[x] = a; mx[x] = b
        }
        val sr = max(2, r / 2)
        fun smooth(v: DoubleArray) = DoubleArray(w) { x -> var s = 0.0; var n = 0; for (k in max(0, x - sr)..min(w - 1, x + sr)) { s += v[k]; n++ }; s / n }
        val l = smooth(mn); val h = smooth(mx)
        val minRange = max(30.0, 0.3 * (hi - lo))
        return DoubleArray(w) { x ->
            val (a, b) = if (h[x] - l[x] >= minRange) l[x] to h[x] else lo to hi
            ((b - prof[x]) / (b - a)).coerceIn(0.0, 1.0)
        }
    }

    private fun dilate(a: DoubleArray, r: Int) = DoubleArray(a.size) { i ->
        var m = 0.0; for (k in max(0, i - r)..min(a.size - 1, i + r)) if (a[k] > m) m = a[k]; m
    }

    private fun erode(a: DoubleArray, r: Int) = DoubleArray(a.size) { i ->
        var m = 1.0; for (k in max(0, i - r)..min(a.size - 1, i + r)) if (a[k] < m) m = a[k]; m
    }

    /**
     * The barcode's outer edges on a line: the first bar after a wide light run (quiet zone) and
     * the last bar before one. Null if they can't be told apart from noise.
     */
    private fun extent(ln: Line): Pair<Double, Double>? {
        val e = ln.edges
        if (e.size < 40) return null
        val runs = DoubleArray(e.size - 1) { e[it + 1] - e[it] }
        val med = runs.sortedArray()[runs.size / 2]
        val quiet = 3.5 * med
        var first = -1
        for (i in e.indices) {
            if (!ln.edgeDarkAfter[i]) continue
            val before = if (i == 0) e[0] else e[i] - e[i - 1]
            if (before >= quiet || (i == 0 && before >= 1.5 * med)) { first = i; break }
        }
        var last = -1
        for (i in e.indices.reversed()) {
            if (ln.edgeDarkAfter[i]) continue
            val after = if (i == e.size - 1) ln.dark.size - e[i] else e[i + 1] - e[i]
            if (after >= quiet || (i == e.size - 1 && after >= 1.5 * med)) { last = i; break }
        }
        if (first < 0 || last <= first || last - first < 30) return null
        return e[first] to e[last]
    }

    /** Runs: widths between consecutive edges, starting at edge index [i]. */
    private fun widths(ln: Line, i: Int, count: Int): DoubleArray? {
        if (i + count >= ln.edges.size) return null
        return DoubleArray(count) { ln.edges[i + it + 1] - ln.edges[i + it] }
    }

    private fun patternError(w: DoubleArray, pattern: IntArray): Double {
        val total = w.sum(); val modules = pattern.sum()
        var e = 0.0
        for (k in w.indices) e += abs(w[k] * modules / total - pattern[k])
        return e
    }

    private fun findStart(ln: Line): Anchor? {
        var best: Anchor? = null; var bestErr = 1.6
        val p = Code128.PATTERNS[Code128.START_C]
        for (i in ln.edges.indices) {
            if (!ln.edgeDarkAfter[i]) continue
            val w = widths(ln, i, 6) ?: break
            if ((0..6).any { ln.edgeAtMask(ln.edges[i + it]) }) continue
            val total = w.sum()
            val quiet = if (i == 0) ln.edges[0] else ln.edges[i] - ln.edges[i - 1]
            if (quiet < 2.5 * total / 11) continue
            val err = patternError(w, p)
            if (err < bestErr) { bestErr = err; best = Anchor(ln.y, ln.edges[i], total / 11) }
        }
        return best
    }

    private fun findStop(ln: Line): Anchor? {
        var best: Anchor? = null; var bestErr = 1.8
        val p = Code128.PATTERNS[Code128.STOP]
        for (i in ln.edges.indices) {
            if (!ln.edgeDarkAfter[i]) continue
            val w = widths(ln, i, 7) ?: break
            if ((0..7).any { ln.edgeAtMask(ln.edges[i + it]) }) continue
            val total = w.sum()
            val end = ln.edges[i + 7]
            val quiet = if (i + 8 < ln.edges.size) ln.edges[i + 8] - end else ln.dark.size - end
            if (quiet < 2.5 * total / 13) continue
            val err = patternError(w, p)
            if (err <= bestErr) { bestErr = err; best = Anchor(ln.y, end, total / 13) }
        }
        return best
    }

    /** Least-squares line x(y) through anchors, or a constant if they don't span enough rows. */
    private fun fit(anchors: List<Anchor>, value: (Anchor) -> Double): (Int) -> Double {
        if (anchors.isEmpty()) return { Double.NaN }
        val vals = anchors.map(value)
        val med = vals.sorted()[vals.size / 2]
        // Drop outliers (wrong pattern matches) before fitting.
        val spread = max(1.0, vals.map { abs(it - med) }.sorted()[vals.size / 2] * 3)
        val good = anchors.filter { abs(value(it) - med) <= spread * 2 + 1 }
        if (good.size < 3) return { med }
        val my = good.sumOf { it.y.toDouble() } / good.size
        val mv = good.sumOf(value) / good.size
        var sxy = 0.0; var sxx = 0.0
        for (a in good) { sxy += (a.y - my) * (value(a) - mv); sxx += (a.y - my) * (a.y - my) }
        if (sxx < 1e-6) return { mv }
        val slope = sxy / sxx
        return { y -> mv + slope * (y - my) }
    }

    private fun measureWithCount(lines: List<Line>, starts: List<Anchor>, stops: List<Anchor>, n: Int, reversed: Boolean): SymbolEvidence? {
        val totalModules = 11 * (n + 1) + 13
        val startX = fit(starts) { it.x }
        val stopX = fit(stops) { it.x }
        val modFromAnchors = (starts.map { it.module } + stops.map { it.module }).filter { it.isFinite() }.sorted()
        val moduleMedian = modFromAnchors.getOrNull(modFromAnchors.size / 2) ?: Double.NaN
        val module: Double = if (starts.isNotEmpty() && stops.isNotEmpty()) {
            val ys = lines.map { it.y }
            val m = ys.map { (stopX(it) - startX(it)) / totalModules }.sorted()[ys.size / 2]
            // If the anchors disagree badly with this symbol count, it's the wrong count.
            if (moduleMedian.isFinite() && abs(m - moduleMedian) / moduleMedian > 0.12) return null
            m
        } else moduleMedian
        if (!module.isFinite()) return null
        if (module < 0.8) return null

        val slots = n + 2
        val sums = Array(slots) { DoubleArray(106) }
        val weights = DoubleArray(slots)
        val xSum = DoubleArray(slots); val xCount = IntArray(slots)
        var used = 0
        for (ln in lines) {
            val x0 = when {
                starts.isNotEmpty() -> startX(ln.y)
                else -> stopX(ln.y) - module * totalModules
            }
            if (x0.isNaN()) continue
            if (x0 + module * totalModules < 0 || x0 > ln.dark.size) continue
            used++
            var drift = 0.0
            for (k in 0 until slots) {
                val expected = x0 + 11 * k * module + drift
                val modules = if (k == slots - 1) 13 else 11
                // Re-synchronize on the bar edge that starts this symbol.
                val e = nearestRisingEdge(ln, expected, 0.7 * module)
                val xs = if (e != null) e else expected
                if (e != null) drift = e - (x0 + 11 * k * module)
                if (xs < -module || xs + modules * module > ln.dark.size + module) continue
                val scored = scoreSlot(ln, xs, module, k, slots) ?: continue
                val (post, q) = scored
                if (q <= 0) continue
                for (v in 0 until 106) sums[k][v] += q * post[v]
                weights[k] += q
                xSum[k] += xs; xCount[k]++
            }
        }
        if (used == 0) return null
        val probs = ArrayList<DoubleArray>(slots)
        val quality = DoubleArray(slots)
        for (k in 0 until slots) {
            val prior = 0.3
            val u = 1.0 / 106
            probs += DoubleArray(106) { (sums[k][it] + prior * u) / (weights[k] + prior) }
            quality[k] = weights[k] / used
        }
        val slotX = DoubleArray(slots) { k ->
            val local = if (xCount[k] > 0) xSum[k] / xCount[k] else Double.NaN
            if (local.isNaN()) {
                val base = if (starts.isNotEmpty()) startX(lines[lines.size / 2].y) else stopX(lines[lines.size / 2].y) - module * totalModules
                base + 11 * k * module
            } else local
        }
        val w = lines.first().dark.size
        val realX = if (reversed) DoubleArray(slots) { k -> w - (slotX[k] + (if (k == slots - 1) 13 else 11) * module) } else slotX
        return SymbolEvidence(n, probs, quality, realX, module, reversed, used, lines.first().y, lines.last().y,
            extentAnchored = starts.all { it.module.isNaN() } && stops.all { it.module.isNaN() })
    }

    private fun nearestRisingEdge(ln: Line, x: Double, tol: Double): Double? {
        var best: Double? = null; var bestD = tol
        // Binary search for position.
        var lo = 0; var hi = ln.edges.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (ln.edges[mid] < x - tol) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < ln.edges.size && ln.edges[i] <= x + tol) {
            if (ln.edgeDarkAfter[i] && !ln.edgeAtMask(ln.edges[i])) {
                val d = abs(ln.edges[i] - x)
                if (d < bestD) { bestD = d; best = ln.edges[i] }
            }
            i++
        }
        return best
    }

    private fun sampleDark(ln: Line, x: Double): Double {
        if (x <= 0) return ln.dark[0].let { if (x < -1) 0.0 else it }
        val w = ln.dark.size
        if (x >= w - 1) return if (x > w) 0.0 else ln.dark[w - 1]
        val i = x.toInt(); val f = x - i
        return ln.dark[i] * (1 - f) + ln.dark[i + 1] * f
    }

    /**
     * Scores one slot. Returns the posterior over values 0..105 for this scanline and a quality
     * weight (0 = unreadable here).
     */
    private fun scoreSlot(ln: Line, xs: Double, module: Double, k: Int, slots: Int): Pair<DoubleArray, Double>? {
        val isStart = k == 0
        val isStop = k == slots - 1
        val values: IntRange = when {
            isStart -> Code128.START_A..Code128.START_C
            isStop -> Code128.STOP..Code128.STOP
            else -> 0..Code128.START_C
        }
        val modules = if (isStop) 13 else 11
        val ns = modules * samplesPerModule
        val step = module / samplesPerModule
        val shifts = doubleArrayOf(-0.35 * module, 0.0, 0.35 * module)
        val samples = Array(shifts.size) { si -> DoubleArray(ns) { m -> sampleDark(ln, xs + shifts[si] + (m + 0.5) * step) } }
        // Samples under a dead print-head line say nothing: left out of every measure below.
        val hidden = Array(shifts.size) { si -> BooleanArray(ns) { m -> ln.isMasked(xs + shifts[si] + (m + 0.5) * step) } }
        val center = samples[1]; val cHidden = hidden[1]
        val seen = (0 until ns).count { !cHidden[it] }
        // Too much of the symbol hidden to tell patterns apart.
        if (seen < 0.55 * ns) return null
        val visible = (0 until ns).filter { !cHidden[it] }.map { center[it] }
        val range = visible.max() - visible.min()
        if (range < 0.45) return null
        // Every symbol's bars cover 4-8 of its 11 modules: a slot that is nearly blank (lost
        // print) or nearly solid (smear) is unreadable, whatever template happens to fit.
        if (!isStop) {
            val ink = visible.average()
            if (ink < 0.12 || ink > 0.9) return null
        }
        // Code 128 never has a space wider than 4 modules. A wider light run is a band of lost
        // print (dead printhead elements, scuffs): any template "fitting" it is a guess.
        run {
            var run = 0; var longest = 0
            for (m in 0 until ns) { if (!cHidden[m] && center[m] < 0.3) { run++; if (run > longest) longest = run } else run = 0 }
            if (longest > 4.6 * samplesPerModule) return null
        }

        val sse = DoubleArray(106) { Double.MAX_VALUE }
        var bestSse = Double.MAX_VALUE
        // Patterns more than this much worse than the best have negligible posterior (e^-23):
        // stop summing early for them.
        val margin = 14.0
        fun score(v: Int) {
            val t = templates[v]
            var b = Double.MAX_VALUE
            for ((si, s) in samples.withIndex()) {
                val limit = if (bestSse == Double.MAX_VALUE) Double.MAX_VALUE else minOf(b, bestSse + margin)
                val hd = hidden[si]
                var e = 0.0
                var m = 0
                while (m < ns) { if (!hd[m]) { val d = s[m] - t[m]; e += d * d; if (e > limit) break }; m++ }
                if (e < b) b = e
            }
            if (v < 106) sse[v] = b
            if (b < bestSse) bestSse = b
        }
        // Seed with the pattern the bar/space widths suggest, so pruning starts early.
        val seedW = if (!isStart && !isStop) edgeWidths(ln, xs, module, modules) else null
        if (seedW != null) {
            val tot = seedW.sum()
            val seed = values.minBy { v -> val p = Code128.PATTERNS[v]; (0 until 6).sumOf { i -> abs(seedW[i] * 11 / tot - p[i]) } }
            score(seed)
        }
        for (v in values) score(v)
        // Refine the best few with bar-width bias (ink loss or spread), only when the plain
        // template fits poorly: clean prints keep sharp, unbiased probabilities.
        if (!isStop && bestSse / ns > 0.05) {
            val top = values.filter { it < 106 }.sortedBy { sse[it] }.take(12)
            for (v in top) for (bi in BAR_BIAS.indices) {
                if (BAR_BIAS[bi] == 0.0) continue
                val t = biasTemplates[bi][v]
                for ((si, smp) in samples.withIndex()) {
                    var e = 0.0; var m = 0
                    val limit = sse[v]; val hd = hidden[si]
                    while (m < ns) { if (!hd[m]) { val d = smp[m] - t[m]; e += d * d; if (e > limit) break }; m++ }
                    if (e < sse[v]) sse[v] = e
                }
                if (sse[v] < bestSse) bestSse = sse[v]
            }
        }
        val perSample = bestSse / seen
        // Fewer samples seen, less this line's reading counts.
        val q = ((0.16 - perSample) / 0.11).coerceIn(0.0, 1.0) * seen / ns
        if (isStop) return DoubleArray(106) { 1.0 / 106 } to q
        if (q <= 0) return null

        // Measured bar/space widths, if this slot has exactly six clean elements.
        val edgeLl = DoubleArray(106)
        val w = edgeWidths(ln, xs, module, modules)
        if (w != null) {
            // Edge-to-similar-edge distances (bar+space pairs) don't change when every bar is
            // printed wider or thinner: the classic ink-spread-tolerant Code 128 measure.
            val total = w.sum()
            for (v in values) {
                val p = Code128.PATTERNS[v]
                var e = 0.0
                for (i in 0 until 4) { val d = (w[i] + w[i + 1]) * 11 / total - (p[i] + p[i + 1]); e += d * d }
                // Plain widths too (sharper on clean prints), at lower weight.
                var e2 = 0.0
                for (i in 0 until 6) { val d = w[i] * 11 / total - p[i]; e2 += d * d }
                edgeLl[v] = -e / (2 * 0.15) - 0.5 * e2 / (2 * 0.12)
            }
        }
        val twoSigma2 = 2 * 0.3
        val ll = DoubleArray(106) { v -> if (v in values) -sse[v] / twoSigma2 + 0.5 * edgeLl[v] else Double.NEGATIVE_INFINITY }
        val mx = ll.max()
        val post = DoubleArray(106) { if (ll[it] == Double.NEGATIVE_INFINITY) 0.0 else exp(ll[it] - mx) }
        val s = post.sum()
        for (i in post.indices) post[i] /= s
        return post to q
    }

    private fun edgeWidths(ln: Line, xs: Double, module: Double, modules: Int): DoubleArray? {
        val end = xs + modules * module
        var i = 0
        while (i < ln.edges.size && ln.edges[i] < xs - 0.5 * module) i++
        if (i >= ln.edges.size || !ln.edgeDarkAfter[i] || abs(ln.edges[i] - xs) > 0.5 * module) return null
        if (i + 6 >= ln.edges.size) return null
        val last = ln.edges[i + 6]
        if (abs(last - end) > 0.8 * module) return null
        if ((0..6).any { ln.edgeAtMask(ln.edges[i + it]) }) return null
        return DoubleArray(6) { ln.edges[i + it + 1] - ln.edges[i + it] }
    }

    /**
     * Combines evidence (from many frames and image variants) and applies the GS1 layout and
     * checksums. Returns the most likely layout's result, or null if nothing fits.
     */
    fun decode(evidence: List<SymbolEvidence>): PartialResult? = decodeAll(evidence).firstOrNull()

    /** Results for every layout that fits, best (highest likelihood) first. */
    fun decodeAll(evidence: List<SymbolEvidence>): List<PartialResult> {
        val all = ArrayList<PartialResult>()
        for ((n, group0) in evidence.filter { isAnchored(it) }.groupBy { it.symbolCount }) {
            // Measurements that read well dominate; poor ones still add a little.
            val group = group0
            val slots = n + 2
            // Quality-weighted average of each slot over all measurements.
            val probs = List(slots) { k ->
                val acc = DoubleArray(106); var wsum = 0.0
                // Grids placed from the outer bars are a fallback: when a start/stop-anchored grid
                // exists for this layout, they count for much less.
                val hasPattern = group.any { !it.extentAnchored }
                for (e in group) {
                    val q = (e.quality[k] + 1e-3) * meanQuality(e).let { it * it } * (if (e.extentAnchored && hasPattern) 0.15 else 1.0)
                    wsum += q; for (v in 0 until 106) acc[v] += q * e.probs[k][v]
                }
                DoubleArray(106) { acc[it] / wsum }
            }
            // The start symbol is often the damaged part (faded first bars); its probability only
            // weighs in through logZ, it doesn't veto the reading.
            val representative = group.maxBy { it.quality.sum() }
            for (st in Gs1Structure.ALL) {
                if (st.symbolCount != n) continue
                all += solve(st, probs.subList(1, n + 1), representative) ?: continue
            }
        }
        // Layouts with fewer symbols always have a higher likelihood, so they are compared by how
        // cleanly their digits read, plus how common the layout is on real labels.
        return all.sortedByDescending { it.selectionScore }
    }

    private fun meanQuality(e: SymbolEvidence): Double {
        var s = 0.0
        for (k in 1..e.symbolCount) s += e.quality[k]
        return s / e.symbolCount
    }

    /**
     * Keeps measurements whose symbol grid is anchored by a real start or stop pattern and that
     * read most slots: a grid laid on a fragment or on text only adds noise.
     */
    private fun isAnchored(e: SymbolEvidence): Boolean {
        val start = e.quality[0] >= 0.3 && e.probs[0][Code128.START_C] >= 0.5
        val stop = e.quality[e.symbolCount + 1] >= 0.3
        // Placed from the outer bars: trust it only if the slots read cleanly.
        if (e.extentAnchored) return meanQuality(e) >= 0.5
        return (start || stop) && meanQuality(e) >= 0.3
    }

    private fun solve(st: Gs1Structure, symbolProbs: List<DoubleArray>, ev: SymbolEvidence): PartialResult? {
        val nData = st.slots.size
        val states = 103 * 10
        // alpha[i] = state distribution after data slot i-1 (alpha[0] = after start).
        val alpha = Array(nData + 1) { DoubleArray(states) }
        val logScale = DoubleArray(nData + 1)
        alpha[0][(Code128.START_C % 103) * 10] = 1.0
        fun contrib(i: Int, v: Int): Int { // mod-10 contribution of slot i with value v
            val sl = st.slots[i] as? Gs1Structure.Slot.Pair ?: return 0
            return (st.mod10Weight(sl.digitIndex) * (v / 10) + st.mod10Weight(sl.digitIndex + 1) * (v % 10)) % 10
        }
        val allowed = Array(nData) { i -> (0 until 106).filter { st.allows(i, it) && symbolProbs[i][it] > 0 }.toIntArray() }
        for (i in 0 until nData) {
            val w = i + 1
            val next = alpha[i + 1]
            for (s in 0 until states) {
                val a = alpha[i][s]; if (a == 0.0) continue
                val s103 = s / 10; val s10 = s % 10
                for (v in allowed[i]) {
                    val ns = ((s103 + w * v) % 103) * 10 + (s10 + contrib(i, v)) % 10
                    next[ns] += a * symbolProbs[i][v]
                }
            }
            val sum = next.sum()
            if (sum <= 0) return null
            for (s in 0 until states) next[s] /= sum
            logScale[i + 1] = logScale[i] + ln(sum)
        }
        val check = symbolProbs[nData]
        // beta[i][s] = probability of completing from state s after slot i-1.
        val beta = Array(nData + 1) { DoubleArray(states) }
        for (s in 0 until states) beta[nData][s] = if (s % 10 == 0) check[s / 10] else 0.0
        var z = 0.0
        for (s in 0 until states) z += alpha[nData][s] * beta[nData][s]
        if (z <= 0) return null
        for (i in nData - 1 downTo 0) {
            val w = i + 1
            val cur = beta[i]
            for (s in 0 until states) {
                val s103 = s / 10; val s10 = s % 10
                var acc = 0.0
                for (v in allowed[i]) {
                    val ns = ((s103 + w * v) % 103) * 10 + (s10 + contrib(i, v)) % 10
                    acc += symbolProbs[i][v] * beta[i + 1][ns]
                }
                cur[s] = acc
            }
            val m = cur.max(); if (m > 0) for (s in 0 until states) cur[s] /= m
        }
        // Posterior marginals for each data slot.
        val digitDists = arrayOfNulls<SlotDist>(st.dataDigits)
        val recovered = ArrayList<RecoveredDigit>()
        "420".forEachIndexed { idx, c -> if (idx < st.routingDigits) digitDists[idx] = SlotDist.known(c) }
        for (i in 0 until nData) {
            val sl = st.slots[i] as? Gs1Structure.Slot.Pair ?: continue
            val w = i + 1
            val post = DoubleArray(100)
            for (s in 0 until states) {
                val a = alpha[i][s]; if (a == 0.0) continue
                val s103 = s / 10; val s10 = s % 10
                for (v in allowed[i]) {
                    val ns = ((s103 + w * v) % 103) * 10 + (s10 + contrib(i, v)) % 10
                    post[v] += a * symbolProbs[i][v] * beta[i + 1][ns]
                }
            }
            val total = post.sum()
            if (total <= 0) return null
            val tens = DoubleArray(10); val units = DoubleArray(10)
            val rawTens = DoubleArray(10); val rawUnits = DoubleArray(10)
            for (v in 0 until 100) {
                tens[v / 10] += post[v] / total; units[v % 10] += post[v] / total
                if (st.allows(i, v)) { rawTens[v / 10] += symbolProbs[i][v]; rawUnits[v % 10] += symbolProbs[i][v] }
            }
            for ((off, dist, raw) in listOf(Triple(0, tens, rawTens), Triple(1, units, rawUnits))) {
                val di = sl.digitIndex + off
                val sd = SlotDist(SlotDist.DIGITS, dist)
                digitDists[di] = sd
                val t = di - st.routingDigits
                if (t >= 0) {
                    val rawSum = raw.sum()
                    val rawBest = if (rawSum > 0) raw.max() / rawSum else 0.1
                    if (rawBest < 0.9) recovered += RecoveredDigit(t, sd.best, sd.bestP, rawBest)
                }
            }
        }
        val dataLogZ = logScale[nData] + ln(z)
        val logZ = dataLogZ + ln(max(1e-3, ev.probs[0][Code128.START_C]))
        return PartialResult(st, digitDists.map { it ?: SlotDist.uniform() }, recovered, logZ, dataLogZ, symbolProbs, ev)
    }
}
