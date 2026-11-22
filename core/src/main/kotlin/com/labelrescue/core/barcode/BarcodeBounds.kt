package com.labelrescue.core.barcode

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The barcode's own extent inside a rough area. Located areas often run to the frame's edges
 * (box edges, tape and text beside the label look bar-like), and the damaged-barcode decoder
 * anchors on where the bars start and stop: on a real label with dead print-head lines it read
 * every digit from a tight crop and nothing from the wide one.
 *
 * Bars are dense vertical edges; the quiet zones either side (ten modules of paper) are not.
 * The longest run of edge-dense columns, with gaps no wider than a wide bar or space, is the
 * barcode; its rows are those where that span is edge-dense too.
 */
object BarcodeBounds {

    /** The crop to read ([rect], with quiet zones) and where the bars themselves start and end. */
    data class Bounds(val rect: IntRect, val barsLeft: Int, val barsRight: Int)

    fun tighten(img: GrayImage, area: IntRect): IntRect? = find(img, area)?.rect

    /**
     * The crop of [b] with its quiet zones cleaned: everything left of the first bar and right of
     * the last is painted paper (a box edge or the label's border close beside the barcode
     * otherwise reads as a bar, and the decoder anchors on where the bars end).
     */
    fun cleanCrop(img: GrayImage, b: Bounds): GrayImage {
        val c = img.crop(b.rect)
        val v = c.data.map { it.toInt() and 0xFF }.sorted()
        val paper = v[(v.size * 0.95).toInt()]
        val l = b.barsLeft - b.rect.left - 1; val r = b.barsRight - b.rect.left + 1
        for (y in 0 until c.height) for (x in 0 until c.width) if (x < l || x > r) c[x, y] = paper
        return c
    }

    fun find(img: GrayImage, area: IntRect): Bounds? {
        val r = area.intersect(IntRect(0, 0, img.width, img.height))
        if (r.width < 60 || r.height < 12) return null
        // Rows to sample: the middle half of the area.
        val y0 = r.top + r.height / 4; val y1 = r.bottom - r.height / 4
        val rows = (y0 until y1 step max(1, (y1 - y0) / 24)).toList()
        val contrast = contrastOf(img, r)
        if (contrast < 40) return null
        val edgeT = contrast * 0.25
        // Per column: share of sampled rows with a strong horizontal step within ±1 px.
        val act = DoubleArray(r.width)
        for (y in rows) for (i in 1 until r.width - 1) {
            val x = r.left + i
            if (abs(img[x + 1, y] - img[x - 1, y]) >= edgeT) { act[i - 1] += 1.0; act[i] += 1.0; act[i + 1] += 1.0 }
        }
        for (i in act.indices) act[i] /= rows.size
        val active = BooleanArray(r.width) { act[it] >= 0.3 }
        // Gaps inside a barcode are at most a 4-module bar or space; the quiet zone is 10 modules.
        // First pass with a generous gap, then again with one from the module the bars show
        // (Code 128 has 6 edges per 11 modules), so a box or label edge just past a short quiet
        // zone isn't taken for more bars.
        var run = longestRun(active, act, (r.width * 0.025).roundToInt().coerceIn(10, 32)) ?: return null
        val midRow = (y0 + y1) / 2
        var edges = 0
        for (x in r.left + run.first + 1 until r.left + run.second) if (abs(img[x + 1, midRow] - img[x - 1, midRow]) >= edgeT && abs(img[x, midRow] - img[x - 2, midRow]) < edgeT) edges++
        var module = 0.0
        if (edges >= 20) {
            module = (run.second - run.first).toDouble() * 6.0 / (11.0 * edges)
            // Lost print (dead elements side by side) can leave a white band wider than a quiet zone
            // inside the barcode: any other bar-like run at least 4 modules wide still belongs to it.
            // Only narrow slivers (a box or label edge) are left out.
            val wide = runs(active, (r.width * 0.025).roundToInt().coerceIn(10, 32)).filter { it.second - it.first + 1 >= module * 4 }
            if (wide.isNotEmpty()) run = minOf(run.first, wide.minOf { it.first }) to maxOf(run.second, wide.maxOf { it.second })
            run = trimEdges(active, run, module)
        }
        val bestL = run.first; val bestR = run.second
        if (bestR - bestL < r.width * 0.15) return null
        val xl = r.left + bestL; val xr = r.left + bestR + 1
        // Rows: those with many edges across [xl, xr), contiguous around the middle.
        val counts = IntArray(r.height)
        for (y in r.top until r.bottom) {
            var n = 0
            for (x in xl + 1 until xr - 1) if (abs(img[x + 1, y] - img[x - 1, y]) >= edgeT) n++
            counts[y - r.top] = n
        }
        val mid = counts.copyOfRange(y0 - r.top, y1 - r.top).sorted().let { it[it.size / 2] }
        if (mid <= 0) return null
        var top = (y0 + y1) / 2 - r.top; var bottom = top
        while (top > 0 && counts[top - 1] >= mid * 0.5) top--
        while (bottom < r.height - 1 && counts[bottom + 1] >= mid * 0.5) bottom++
        // A margin for the quiet zones (the decoder looks for them), and above and below the bars
        // (the decoder samples rows across the whole crop and needs the bars' ends inside it).
        // The quiet zone is ten modules: leave a little more, so the decoder sees it whole.
        val pad = if (module > 0) max(6, (module * 11).roundToInt()) else max(6, ((xr - xl) * 0.03).roundToInt())
        val vpad = max(6, ((bottom - top + 1) * 0.12).roundToInt())
        return Bounds(IntRect(xl - pad, r.top + top - vpad, xr + pad, r.top + bottom + 1 + vpad).intersect(IntRect(0, 0, img.width, img.height)), xl, xr)
    }

    /**
     * Drops a narrow island at either end that sits beyond a quiet-zone-sized gap: a box or label
     * edge just past the barcode (one or two edges, under 3 modules wide). The stop and start
     * patterns are wider (11-13 modules), so they stay.
     */
    private fun trimEdges(active: BooleanArray, run: Pair<Int, Int>, module: Double): Pair<Int, Int> {
        var (l, r) = run
        val gapMin = (module * 5).roundToInt(); val clusterMax = (module * 4).roundToInt()
        // Right end: the last quiet-zone-sized gap inside the run; what's past it, if narrow, goes.
        run {
            var x = r; var gapEnd = -1; var gap = 0
            while (x > l && r - x <= clusterMax + gapMin) {
                if (!active[x]) { if (++gap >= gapMin) { gapEnd = x; break } } else gap = 0
                x--
            }
            if (gapEnd > 0) { var e = gapEnd; while (e > l && !active[e]) e--; r = e }
        }
        run {
            var x = l; var gapStart = -1; var gap = 0
            while (x < r && x - l <= clusterMax + gapMin) {
                if (!active[x]) { if (++gap >= gapMin) { gapStart = x; break } } else gap = 0
                x++
            }
            if (gapStart > 0) { var e = gapStart; while (e < r && !active[e]) e++; l = e }
        }
        return l to r
    }

    /** Every run of edge-dense columns (gaps up to [maxGap]): (first, last). */
    private fun runs(active: BooleanArray, maxGap: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < active.size) {
            if (!active[i]) { i++; continue }
            var end = i; var gap = 0; var k = i
            while (k < active.size) {
                if (active[k]) { end = k; gap = 0 } else if (++gap > maxGap) break
                k++
            }
            out += i to end
            i = end + 1
        }
        return out
    }

    /** The edge-dense run of columns with the most activity, gaps up to [maxGap]: (first, last). */
    private fun longestRun(active: BooleanArray, act: DoubleArray, maxGap: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null; var bestScore = 0.0
        var i = 0
        while (i < active.size) {
            if (!active[i]) { i++; continue }
            var end = i; var gap = 0; var k = i; var score = 0.0
            while (k < active.size) {
                if (active[k]) { end = k; gap = 0; score += act[k] } else if (++gap > maxGap) break
                k++
            }
            if (score > bestScore) { bestScore = score; best = i to end }
            i = end + 1
        }
        return best
    }

    private fun contrastOf(img: GrayImage, r: IntRect): Double {
        val step = max(1, (r.width * r.height / 20000.0).roundToInt())
        val v = ArrayList<Int>()
        var p = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) { if (p++ % step == 0) v += img[x, y] }
        v.sort()
        return (v[(v.size * 0.95).toInt()] - v[(v.size * 0.05).toInt()]).toDouble()
    }
}
