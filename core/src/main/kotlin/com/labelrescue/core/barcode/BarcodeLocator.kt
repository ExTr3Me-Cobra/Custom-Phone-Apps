package com.labelrescue.core.barcode

import com.labelrescue.core.image.Filters
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds likely 1D barcode areas: regions with strong gradients across one axis and weak along
 * the other. Works even when the bars are too damaged to decode.
 */
object BarcodeLocator {

    /** A barcode area in frame coordinates; [vertical] means the bars run horizontally (rotate 90°). */
    data class Region(val rect: IntRect, val vertical: Boolean, val score: Double)

    fun locate(img: GrayImage, maxRegions: Int = 3): List<Region> {
        val small = img.limited(640)
        val scale = img.width.toDouble() / small.width
        val out = ArrayList<Region>()
        for (vertical in listOf(false, true)) {
            val e = GrayImage(small.width, small.height)
            for (y in 1 until small.height - 1) for (x in 1 until small.width - 1) {
                val gx = abs(small[x + 1, y] - small[x - 1, y])
                val gy = abs(small[x, y + 1] - small[x, y - 1])
                e[x, y] = if (vertical) gy - gx else gx - gy
            }
            val r = max(3, small.width / 70)
            val blurred = Filters.boxBlur(Filters.boxBlur(e, r), r)
            var sum = 0.0; var sq = 0.0; var mx = 0
            for (b in blurred.data) { val v = b.toInt() and 0xFF; sum += v; sq += v * v.toDouble(); if (v > mx) mx = v }
            val n = blurred.data.size
            val mean = sum / n; val std = sqrt(max(0.0, sq / n - mean * mean))
            val t = max(mean + 1.5 * std, 0.35 * mx).coerceAtLeast(8.0)
            for (c in merge(components(blurred, t), vertical, small.width, small.height).map { extend(it, blurred, t * 0.4, vertical) }) {
                val w = c.width; val h = c.height
                val along = if (vertical) h else w
                if (along < small.width * 0.06 && along < small.height * 0.06) continue
                val full = IntRect((c.left * scale).toInt(), (c.top * scale).toInt(), (c.right * scale).toInt(), (c.bottom * scale).toInt())
                // Pad: blur shrinks the area, and the quiet zone and end bars must be included.
                val padAlong = ((if (vertical) full.height else full.width) * 0.12).toInt() + 8
                val padAcross = ((if (vertical) full.width else full.height) * 0.25).toInt() + 4
                val padded = (if (vertical) full.inflate(padAcross, padAlong) else full.inflate(padAlong, padAcross))
                    .intersect(IntRect(0, 0, img.width, img.height))
                out += Region(padded, vertical, c.area.toDouble() * min(4.0, along.toDouble() / max(1, if (vertical) w else h)))
            }
        }
        return out.sortedByDescending { it.score }.take(maxRegions)
    }

    /**
     * Grows a region along the bar-crossing axis while the bar texture continues at a lower
     * threshold, across gaps up to 8% of the image: faded ends, glare and bands of missing bars
     * (dead printhead elements) otherwise cut the located area short of the start or stop pattern.
     */
    private fun extend(c: Comp, e: GrayImage, t: Double, vertical: Boolean): Comp {
        val len = if (vertical) e.height else e.width
        val maxGap = max(4, len * 8 / 100)
        fun strength(k: Int): Double {
            var s = 0.0; var n = 0
            if (vertical) { for (x in c.left until c.right) { s += e[x, k]; n++ } } else { for (y in c.top until c.bottom) { s += e[k, y]; n++ } }
            return if (n == 0) 0.0 else s / n
        }
        var lo = if (vertical) c.top else c.left
        var hi = (if (vertical) c.bottom else c.right) - 1
        var last = lo; var k = lo - 1
        while (k >= 0 && last - k <= maxGap) { if (strength(k) >= t) last = k; k-- }
        lo = last
        last = hi; k = hi + 1
        while (k < len && k - last <= maxGap) { if (strength(k) >= t) last = k; k++ }
        hi = last
        return if (vertical) Comp(c.left, lo, c.right, hi + 1, c.area) else Comp(lo, c.top, hi + 1, c.bottom, c.area)
    }

    private class Comp(val left: Int, val top: Int, val right: Int, val bottom: Int, val area: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Joins pieces of one barcode split by damage: aligned across the bars, separated by a small gap. */
    private fun merge(cs: List<Comp>, vertical: Boolean, w: Int, h: Int): List<Comp> {
        val list = cs.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val (a0, a1, b0, b1) = if (vertical) listOf(a.left, a.right, b.left, b.right) else listOf(a.top, a.bottom, b.top, b.bottom)
                val overlap = min(a1, b1) - max(a0, b0)
                val across = min(a1 - a0, b1 - b0)
                if (across <= 0 || overlap < across * 0.5) continue
                val gap = if (vertical) max(a.top, b.top) - min(a.bottom, b.bottom) else max(a.left, b.left) - min(a.right, b.right)
                if (gap > (if (vertical) h else w) * 0.12) continue
                list[i] = Comp(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom), a.area + b.area)
                list.removeAt(j)
                changed = true
                break@loop
            }
        }
        return list
    }

    private fun components(img: GrayImage, t: Double): List<Comp> {
        val w = img.width; val h = img.height
        val seen = BooleanArray(w * h)
        val out = ArrayList<Comp>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start] || (img.data[start].toInt() and 0xFF) < t) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var l = w; var tp = h; var r = 0; var b = 0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                area++
                if (x < l) l = x; if (x > r) r = x; if (y < tp) tp = y; if (y > b) b = y
                if (x > 0) push(p - 1, img, t, seen, stack, sp)?.let { sp = it }
                if (x < w - 1) push(p + 1, img, t, seen, stack, sp)?.let { sp = it }
                if (y > 0) push(p - w, img, t, seen, stack, sp)?.let { sp = it }
                if (y < h - 1) push(p + w, img, t, seen, stack, sp)?.let { sp = it }
            }
            if (area >= 30) out += Comp(l, tp, r + 1, b + 1, area)
        }
        return out
    }

    private fun push(q: Int, img: GrayImage, t: Double, seen: BooleanArray, stack: IntArray, sp: Int): Int? {
        if (seen[q] || (img.data[q].toInt() and 0xFF) < t) return null
        seen[q] = true
        stack[sp] = q
        return sp + 1
    }
}
