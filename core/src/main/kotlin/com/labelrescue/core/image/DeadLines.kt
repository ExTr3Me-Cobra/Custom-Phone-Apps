package com.labelrescue.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dead print-head elements: the most common damage on thermal labels. A heating element that
 * died prints nothing, so a thin white line runs the whole length of the label (along the print
 * direction, top to bottom), cutting every bar it crosses in two. Decoders then read extra
 * bars, and the barcode fails even though it looks fine at a glance.
 *
 * The ruled lines above and below the barcode should be solid black: every white gap in them is
 * a dead column. With a ruled line above and one below, each dead column is traced through the
 * barcode as a straight line (so a tilted label works too); with one, it's taken as vertical and
 * snapped to the light streak in each row. Inside the barcode, a gap at a dead column that is no
 * wider than the dead line, with ink on both sides, is filled back in: a real space is wider
 * (a module is wider than one print dot), so it stays.
 */
object DeadLines {

    /** A dead column: x at the top band ([x0] at [y0]) and its drift per row (tilt), and its width in pixels. */
    data class Line(val x0: Double, val y0: Int, val slope: Double, val width: Int) {
        fun xAt(y: Int) = x0 + slope * (y - y0)
    }

    data class Found(val lines: List<Line>, val bands: Int)

    /** Dead columns crossing the barcode at [bar], found in the ruled lines above and below it. */
    fun find(img: GrayImage, bar: IntRect): Found {
        val r = bar.intersect(IntRect(0, 0, img.width, img.height))
        if (r.width < 40 || r.height < 8) return Found(emptyList(), 0)
        val reach = max(20, (r.height * 1.6).roundToInt())
        val above = IntRect(r.left, max(0, r.top - reach), r.right, r.top)
        val below = IntRect(r.left, r.bottom, r.right, min(img.height, r.bottom + reach))
        val t = threshold(img, IntRect(r.left, max(0, r.top - reach), r.right, min(img.height, r.bottom + reach)))
        // The band nearest the barcode on each side.
        val top = bands(img, above, t).maxByOrNull { it.first }
        val bottom = bands(img, below, t).minByOrNull { it.first }
        val gTop = top?.let { gaps(img, r.left, r.right, it, t) }.orEmpty()
        val gBot = bottom?.let { gaps(img, r.left, r.right, it, t) }.orEmpty()
        val lines = ArrayList<Line>()
        if (top != null && bottom != null && gTop.isNotEmpty() && gBot.isNotEmpty()) {
            val yT = (top.first + top.second) / 2; val yB = (bottom.first + bottom.second) / 2
            val dy = (yB - yT).toDouble()
            // Pair each gap above with the gap below it; the shift is the label's tilt (the same for
            // every dead column, so take the median and pair again with it).
            val maxShift = dy * 0.09 + 2
            val shifts = gTop.mapNotNull { a -> gBot.minByOrNull { abs(it.first - a.first) }?.takeIf { abs(it.first - a.first) <= maxShift }?.let { it.first - a.first } }
            if (shifts.isNotEmpty()) {
                val shift = shifts.sorted()[shifts.size / 2]
                for (a in gTop) {
                    val b = gBot.minByOrNull { abs(it.first - (a.first + shift)) } ?: continue
                    if (abs(b.first - (a.first + shift)) > 2.5) continue
                    lines += Line(a.first, yT, (b.first - a.first) / dy, max(a.second, b.second))
                }
                return Found(lines, 2)
            }
        }
        val one = top ?: bottom ?: return Found(emptyList(), 0)
        val g = if (top != null) gTop else gBot
        for (a in g) lines += Line(a.first, (one.first + one.second) / 2, 0.0, a.second)
        return Found(lines, 1)
    }

    /**
     * A copy of [img] with the dead columns inside [bar] filled where they cut through a bar: if
     * there's ink just beyond the dead line on both sides, the line is inside a bar, and its pixels
     * (white, or grey once blurred) become the ink beside it. At a bar's edge or in a space one side
     * is paper, and nothing changes.
     */
    fun repair(img: GrayImage, bar: IntRect, found: Found): GrayImage {
        if (found.lines.isEmpty()) return img
        val r = bar.intersect(IntRect(0, 0, img.width, img.height))
        val out = GrayImage(img.width, img.height, img.data.copyOf())
        val t = threshold(img, r)
        for (y in r.top until r.bottom) {
            for (ln in found.lines) {
                var c = ln.xAt(y)
                // One band: the line is taken as vertical, so snap to the lightest pixel nearby.
                if (found.bands == 1) c = ((c.roundToInt() - 2)..(c.roundToInt() + 2)).filter { it in r.left until r.right }.maxByOrNull { img[it, y] }?.toDouble() ?: continue
                // The span the dead line (and its blur) covers, and the pixels just beyond it.
                val half = (ln.width + 1) / 2.0 + 0.5
                val a = (c - half).roundToInt(); val b = (c + half).roundToInt()
                val la = a - 1; val rb = b + 1
                if (la < r.left || rb >= r.right) continue
                val left = min(img[la, y], if (la - 1 >= r.left) img[la - 1, y] else 255)
                val right = min(img[rb, y], if (rb + 1 < r.right) img[rb + 1, y] else 255)
                if (left > t || right > t) continue
                val ink = max(left, right)
                for (xx in a..b) if (img[xx, y] > ink) out[xx, y] = ink
            }
        }
        return out
    }

    /** Rows of a solid ruled line inside [area]: (first row, last row), at least 2 rows thick. */
    private fun bands(img: GrayImage, area: IntRect, t: Int): List<Pair<Int, Int>> {
        if (area.isEmpty || area.width < 20) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (y in area.top until area.bottom) {
            var dark = 0
            for (x in area.left until area.right) if (img[x, y] <= t) dark++
            // Even with many dead columns a ruled line stays mostly ink; text and bars are far below this.
            val rule = dark >= 0.72 * area.width
            if (rule && start < 0) start = y
            if (!rule && start >= 0) { if (y - start >= 2) out += start to y - 1; start = -1 }
        }
        if (start >= 0 && area.bottom - start >= 2) out += start to area.bottom - 1
        return out.filter { (a, b) -> b - a + 1 <= max(4, area.height / 3) }
    }

    /** Light gaps across a ruled line: (center x, width), each with ink on both sides. */
    private fun gaps(img: GrayImage, left: Int, right: Int, band: Pair<Int, Int>, t: Int): List<Pair<Double, Int>> {
        val (y0, y1) = band
        // The band's middle rows (its edges are blurred).
        val a = if (y1 - y0 >= 4) y0 + 1 else y0; val b = if (y1 - y0 >= 4) y1 - 1 else y1
        val light = BooleanArray(right - left) { i ->
            var s = 0; for (y in a..b) s += img[left + i, y]
            s / (b - a + 1) > t
        }
        val out = ArrayList<Pair<Double, Int>>()
        var i = 0
        while (i < light.size) {
            if (!light[i]) { i++; continue }
            var j = i; while (j + 1 < light.size && light[j + 1]) j++
            val w = j - i + 1
            // Ink on both sides (not the band's end), and thin: a dead line is a dot or a few wide.
            if (i > 0 && j < light.size - 1 && w <= 8) out += (left + (i + j) / 2.0) to w
            i = j + 1
        }
        return out
    }

    private fun threshold(img: GrayImage, r: IntRect): Int {
        val v = IntArray(r.width * r.height) { img[r.left + it % r.width, r.top + it / r.width] }
        if (v.isEmpty()) return 128
        val s = v.sortedArray()
        val lo = s[(s.size * 0.05).toInt()]; val hi = s[(s.size * 0.95).toInt()]
        return (lo + hi) / 2
    }
}
