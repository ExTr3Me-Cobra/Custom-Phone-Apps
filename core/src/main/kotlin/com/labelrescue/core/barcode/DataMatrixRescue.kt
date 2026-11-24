package com.labelrescue.core.barcode

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.datamatrix.decoder.ZxingDataMatrixAccess
import com.google.zxing.datamatrix.detector.Detector
import com.labelrescue.core.image.GrayImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads a damaged DataMatrix (the IMmb: the same tracking data as the barcode, twice on every
 * label) beyond what ZXing corrects. ZXing finds the symbol and its grid, then treats every
 * codeword as equally trustworthy, so it fixes at most ecc/2 of them. Here each module is read
 * from the photo with how sure the read is, and codewords that can't be trusted are marked as
 * erasures before Reed-Solomon runs, which fixes up to ecc of those:
 *  - modules under a dead print-head line ([dead], image pixels) are unknown;
 *  - then, if that isn't enough, the least certain codewords are erased a few at a time (GMD).
 * Every result must still check with spare redundancy, then parse as a GS1 payload; the caller
 * also checks the tracking number's own check digit.
 */
object DataMatrixRescue {

    class Result(val text: String, val erasures: Int, val errors: Int, val modules: Int)

    /** Spare check codewords a correction must leave (a correction using all of them always "succeeds"). */
    private const val SPARE = 2
    /** More when erasures were guessed (GMD) rather than known. */
    private const val SPARE_GUESSED = 3

    /** Square ECC 200 sizes (modules per side), up to what a tracking number needs. */
    private val SIZES = intArrayOf(10, 12, 14, 16, 18, 20, 22, 24, 26, 32)

    fun decode(img: GrayImage, dead: ((Int, Int) -> Boolean)? = null): Result? {
        val squares = inkSquares(img)
        decodeOne(img, dead, squares)?.let { return it }
        // Dead print-head lines can run through every column of modules (each module split by
        // thin white lines, reading half white). Fill thin white gaps across the print direction
        // (a horizontal closing of the ink) around each candidate patch, then read again.
        for (box in squares) {
            val m = max(8, box.width / 6)
            val area = com.labelrescue.core.image.IntRect(box.left - m, box.top - m, box.right + m, box.bottom + m).intersect(com.labelrescue.core.image.IntRect(0, 0, img.width, img.height))
            val crop = img.crop(area)
            val d = dead?.let { f -> { x: Int, y: Int -> f(x + area.left, y + area.top) } }
            val local = listOf(com.labelrescue.core.image.IntRect(box.left - area.left, box.top - area.top, box.right - area.left, box.bottom - area.top))
            for (r in 1..3) decodeOne(closeH(crop, r), d, local)?.let { return it }
        }
        return null
    }

    /** [img] with light gaps up to 2·[r] px wide filled between dark pixels, along rows. */
    fun closeH(img: GrayImage, r: Int): GrayImage {
        val w = img.width; val h = img.height
        val a = GrayImage(w, h); val b = GrayImage(w, h)
        for (y in 0 until h) for (x in 0 until w) { var m = 255; for (k in max(0, x - r)..min(w - 1, x + r)) { val v = img[k, y]; if (v < m) m = v }; a[x, y] = m }
        for (y in 0 until h) for (x in 0 until w) { var m = 0; for (k in max(0, x - r)..min(w - 1, x + r)) { val v = a[k, y]; if (v > m) m = v }; b[x, y] = m }
        return b
    }

    private fun decodeOne(img: GrayImage, dead: ((Int, Int) -> Boolean)?, squares: List<com.labelrescue.core.image.IntRect>): Result? {
        val source = ZxingDecoder.GraySource(img)
        for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            val det = try { Detector(BinaryBitmap(bin).blackMatrix).detect() } catch (e: Exception) { continue }
            // Dead bands also break its count of the timing pattern, so the size can be off (even
            // non-square) while the corners are close; one side is often cut short.
            val dw = det.bits.width; val dh = det.bits.height
            if (det.points.size < 4 || dw < 4 || dh < 4) continue
            val d = max(dw, dh)
            // ZXing's corners are the corner modules' centers: move them out by half a module to
            // the symbol's outer corners, then try nearby sizes and keep the best-fitting grid.
            val p = det.points.map { it.x.toDouble() to it.y.toDouble() }
            val (tl, bl, br, tr) = p
            fun out(c: Pair<Double, Double>, h: Pair<Double, Double>, v: Pair<Double, Double>) =
                (c.first - 0.5 * (h.first - c.first) / (dw - 1) - 0.5 * (v.first - c.first) / (dh - 1)) to
                    (c.second - 0.5 * (h.second - c.second) / (dw - 1) - 0.5 * (v.second - c.second) / (dh - 1))
            val corners = listOf(out(tl, tr, bl), out(bl, br, tl), out(br, bl, tr), out(tr, tl, br))
            val tried = SIZES.filter { abs(it - d) <= 8 }.sortedBy { abs(it - d) }
            val outlines = squared(corners) + listOfNotNull(edgeOutline(img, corners, d)).flatMap { squared(it) }
            val grids = outlines.flatMap { cs -> tried.mapNotNull { n -> sample(img, n, cs, dead) } }.filter { it.fit >= 0.8 }.sortedByDescending { it.softFit }
            for (g in grids.take(3)) (decodeGrid(g) ?: decodeGrid(refine(img, g, dead)))?.let { return it }
        }
        // ZXing's detector found nothing usable (dead lines through the finder pattern break the
        // edge tracing it relies on): find square patches of ink, and try each orientation.
        for (box in squares) {
            val tl = box.left.toDouble() to box.top.toDouble(); val tr = box.right.toDouble() to box.top.toDouble()
            val br = box.right.toDouble() to box.bottom.toDouble(); val bl = box.left.toDouble() to box.bottom.toDouble()
            // (tl, bl, br, tr) with the solid L at left and bottom; the other three turns of it.
            val turns = listOf(listOf(tl, bl, br, tr), listOf(bl, br, tr, tl), listOf(br, tr, tl, bl), listOf(tr, tl, bl, br))
            val side = min(box.width, box.height)
            val sizes = SIZES.filter { side / it.toDouble() >= 2.5 }
            val grids = turns.flatMap { cs -> sizes.mapNotNull { n -> sample(img, n, cs, dead) } }.filter { it.fit >= 0.6 }.sortedByDescending { it.softFit }
            for (g in grids.take(4)) (decodeGrid(g) ?: decodeGrid(refine(img, g, dead)))?.let { return it }
        }
        return null
    }

    /**
     * Square, dense patches of ink that could be a DataMatrix: dark pixels with small gaps closed,
     * connected, about square, at least 30 px, filled 30-85%. Largest first, at most 4.
     */
    private fun inkSquares(img: GrayImage): List<com.labelrescue.core.image.IntRect> {
        val sc = if (max(img.width, img.height) > 900) 900.0 / max(img.width, img.height) else 1.0
        val g = if (sc < 1.0) img.scaledBy(sc) else img
        val w = g.width; val h = g.height
        val v = IntArray(w * h) { g[it % w, it / w] }.sortedArray()
        val thr = (v[(v.size * 0.05).toInt()] + v[(v.size * 0.95).toInt()]) / 2
        val base = BooleanArray(w * h) { g[it % w, it / w] < thr }
        val out = ArrayList<Pair<com.labelrescue.core.image.IntRect, Int>>()
        // Light modules and dead lines leave gaps of about a module inside the symbol: bridge
        // them at a few widths (the right one depends on the module size).
        for (r in intArrayOf(2, 4, 7)) out += inkComponents(base, w, h, r, sc)
        return out.sortedByDescending { it.second }.distinctBy { it.first }.take(6).map { it.first }
    }

    private fun inkComponents(base: BooleanArray, w: Int, h: Int, r: Int, sc: Double): List<Pair<com.labelrescue.core.image.IntRect, Int>> {
        var m = base
        fun grow(a: BooleanArray, on: Boolean): BooleanArray {
            val hz = BooleanArray(w * h); for (y in 0 until h) for (x in 0 until w) { var o = !on; for (k in max(0, x - r)..min(w - 1, x + r)) if (a[y * w + k] == on) { o = on; break }; hz[y * w + x] = o }
            val out = BooleanArray(w * h); for (y in 0 until h) for (x in 0 until w) { var o = !on; for (k in max(0, y - r)..min(h - 1, y + r)) if (hz[k * w + x] == on) { o = on; break }; out[y * w + x] = o }
            return out
        }
        m = grow(grow(m, true), false)
        val seen = BooleanArray(w * h); val stack = IntArray(w * h)
        val out = ArrayList<Pair<com.labelrescue.core.image.IntRect, Int>>()
        for (i in m.indices) {
            if (!m[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var l = w; var rr = 0; var t = h; var b = 0; var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w; n++
                if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > b) b = y
                for ((dx, dy) in NB) { val nx = x + dx; val ny = y + dy; if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue; val q = ny * w + nx; if (m[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
            }
            val bw = rr - l + 1; val bh = b - t + 1
            if (bw < 30 * sc || bh < 30 * sc || bw > 0.9 * w || bh > 0.9 * h) continue
            if (bw < 0.8 * bh || bw > 1.25 * bh) continue
            val fill = n.toDouble() / (bw * bh)
            if (fill < 0.3 || fill > 0.85) continue
            // Back to full resolution; closing grew the ink by about a pixel each way.
            out += com.labelrescue.core.image.IntRect(((l + 0.5) / sc).toInt(), ((t + 0.5) / sc).toInt(), ((rr + 0.5) / sc).toInt(), ((b + 0.5) / sc).toInt()) to n
        }
        return out
    }

    /**
     * The outline from the solid bottom edge of the finder. Dead print-head lines run across it
     * (top to bottom) and only nick it, so its ends are where the symbol's left and right edges
     * are, even when a dead band hides the solid left edge or the right timing column (which is
     * what throws ZXing's corners off). The symbol is square: its height is that width.
     */
    private fun edgeOutline(img: GrayImage, c: List<Pair<Double, Double>>, d: Int): List<Pair<Double, Double>>? {
        val (tl, bl, br, tr) = c
        val ux = (br.first - bl.first); val uy = (br.second - bl.second); val ul = kotlin.math.hypot(ux, uy)
        val vx = (bl.first - tl.first); val vy = (bl.second - tl.second); val vl = kotlin.math.hypot(vx, vy)
        if (ul < 10 || vl < 10) return null
        val ex = ux / ul; val ey = uy / ul; val fx = vx / vl; val fy = vy / vl
        val m = (ul + vl) / 2 / d
        // Threshold from the area around the symbol.
        val vals = ArrayList<Int>()
        for (i in 0..40) for (j in 0..40) { val x = tl.first + ex * ul * (i / 40.0) + fx * vl * (j / 40.0); val y = tl.second + ey * ul * (i / 40.0) + fy * vl * (j / 40.0); if (x >= 0 && y >= 0 && x < img.width - 1 && y < img.height - 1) vals += img.sample(x, y).toInt() }
        if (vals.size < 100) return null
        vals.sort(); val thr = (vals[(vals.size * 0.05).toInt()] + vals[(vals.size * 0.95).toInt()]) / 2.0
        // Lines parallel to the bottom edge, from 2 modules above it to 2 below; along each, the
        // longest dark run (gaps up to 1.6 modules: dead bands) from 1.5 symbol widths either way.
        var best: Triple<Double, Double, Double>? = null // (v, u0, u1)
        var v = -2.0 * m
        while (v <= 2.0 * m) {
            val step = 0.5
            val from = -0.6 * ul; val to = 1.6 * ul
            var runStart = Double.NaN; var lastDark = Double.NaN; var u = from
            while (u <= to) {
                val x = bl.first + ex * u + fx * v; val y = bl.second + ey * u + fy * v
                val dark = x >= 0 && y >= 0 && x < img.width - 1 && y < img.height - 1 && img.sample(x, y) < thr
                if (dark) {
                    if (runStart.isNaN() || u - lastDark > 1.6 * m) runStart = u
                    lastDark = u
                    val len = lastDark - runStart
                    if (best == null || len > best.third - best.second) best = Triple(v, runStart, lastDark)
                }
                u += step
            }
            v += 0.25 * m
        }
        val (bv, u0, u1) = best ?: return null
        val side = u1 - u0
        if (side < 0.6 * ul || side > 1.6 * ul) return null
        // The run's line is the bottom row's middle; the outer edge is half a module below.
        val bottom = bv + 0.5 * side / d
        fun at(u: Double, vv: Double) = (bl.first + ex * u + fx * vv) to (bl.second + ey * u + fy * vv)
        return listOf(at(u0, bottom - side), at(u0, bottom), at(u1, bottom), at(u1, bottom - side))
    }

    private val NB = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /**
     * The IMmb is square. A dead band over the solid left (or bottom) edge makes ZXing take the
     * band's edge for the symbol's side, so the found shape comes out too narrow (or short):
     * also try it widened to square, from either end, along its own edges.
     */
    private fun squared(c: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
        val (tl, bl, br, tr) = c
        fun len(a: Pair<Double, Double>, b: Pair<Double, Double>) = kotlin.math.hypot(b.first - a.first, b.second - a.second)
        fun along(a: Pair<Double, Double>, b: Pair<Double, Double>, k: Double) = (a.first + (b.first - a.first) * k) to (a.second + (b.second - a.second) * k)
        val w = (len(tl, tr) + len(bl, br)) / 2; val h = (len(tl, bl) + len(tr, br)) / 2
        val out = arrayListOf(c)
        if (w < 0.92 * h) {
            val k = h / w - 1
            out += listOf(along(tl, tr, -k), along(bl, br, -k), br, tr) // extend left
            out += listOf(tl, bl, along(bl, br, 1 + k), along(tl, tr, 1 + k)) // extend right
        } else if (h < 0.92 * w) {
            val k = w / h - 1
            out += listOf(tl, along(tl, bl, 1 + k), along(tr, br, 1 + k), tr) // extend down
            out += listOf(along(bl, tl, 1 + k), bl, br, along(br, tr, 1 + k)) // extend up
        }
        return out
    }

    private class Grid(val n: Int, val black: BooleanArray, val conf: DoubleArray, val hidden: BooleanArray, val fit: Double, val corners: List<Pair<Double, Double>>, val softFit: Double)

    /**
     * Nudges each corner (a quarter, then an eighth of a module at a time) while the finder and
     * timing pattern fit better: ZXing's corners are often a fraction of a module off on damaged
     * symbols, and a grid off by half a module reads every edge module wrong.
     */
    private fun refine(img: GrayImage, g0: Grid, dead: ((Int, Int) -> Boolean)?): Grid {
        var g = g0
        val (tl, _, _, tr) = g.corners
        val module = kotlin.math.hypot(tr.first - tl.first, tr.second - tl.second) / g.n
        for (step in doubleArrayOf(0.25, 0.125)) {
            var improved = true; var rounds = 0
            while (improved && rounds++ < 6) {
                improved = false
                for (ci in 0 until 4) for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val cs = g.corners.toMutableList()
                    cs[ci] = (cs[ci].first + dx * step * module) to (cs[ci].second + dy * step * module)
                    val t = sample(img, g.n, cs, dead) ?: continue
                    if (t.softFit > g.softFit + 1e-4) { g = t; improved = true }
                }
            }
        }
        return g
    }

    /** Reads an [n]×[n] grid between outer [corners] (tl, bl, br, tr). */
    private fun sample(img: GrayImage, n: Int, corners: List<Pair<Double, Double>>, dead: ((Int, Int) -> Boolean)?): Grid? {
        val (tl, bl, br, tr) = corners
        val t = PerspectiveTransform.quadrilateralToQuadrilateral(
            0f, 0f, n.toFloat(), 0f, n.toFloat(), n.toFloat(), 0f, n.toFloat(),
            tl.first.toFloat(), tl.second.toFloat(), tr.first.toFloat(), tr.second.toFloat(),
            br.first.toFloat(), br.second.toFloat(), bl.first.toFloat(), bl.second.toFloat(),
        )
        val soft = DoubleArray(n * n); val hidden = BooleanArray(n * n)
        val pt = FloatArray(2)
        for (r in 0 until n) for (c in 0 until n) {
            var s = 0.0; var k = 0; var h = 0
            for (dy in -1..1) for (dx in -1..1) {
                pt[0] = (c + 0.5 + dx * 0.22).toFloat(); pt[1] = (r + 0.5 + dy * 0.22).toFloat(); t.transformPoints(pt)
                val x = pt[0].toDouble(); val y = pt[1].toDouble()
                if (x < 0 || y < 0 || x > img.width - 1 || y > img.height - 1) continue
                s += img.sample(x, y); k++
                if (dead != null && dead(x.toInt(), y.toInt())) h++
            }
            soft[r * n + c] = if (k > 0) s / k else 255.0
            hidden[r * n + c] = h >= 3
        }
        // Ink and paper: two-means on the module values.
        var lo = soft.min(); var hi = soft.max()
        repeat(8) {
            val m = (lo + hi) / 2
            val a = soft.filter { it < m }; val b = soft.filter { it >= m }
            if (a.isEmpty() || b.isEmpty()) return null
            lo = a.average(); hi = b.average()
        }
        if (hi - lo < 25) return null
        val thr = (lo + hi) / 2
        val black = BooleanArray(n * n) { soft[it] < thr }
        val conf = DoubleArray(n * n) { min(1.0, abs(soft[it] - thr) / ((hi - lo) / 2)) }
        // Finder (solid left and bottom edges) and timing (alternating top and right edges).
        var good = 0; var all = 0
        for (i in 0 until n) {
            fun want(r: Int, c: Int, b: Boolean) { val j = r * n + c; if (hidden[j]) return; all++; if (black[j] == b) good++ }
            want(i, 0, true); want(n - 1, i, true)
            want(0, i, i % 2 == 0); want(i, n - 1, i % 2 == 1)
        }
        if (all < 2 * n) return null
        // The same, soft: how far each pattern module is on the right side of the threshold.
        var sf = 0.0
        for (i in 0 until n) {
            fun want(r: Int, c: Int, b: Boolean) { val j = r * n + c; if (hidden[j]) return; sf += ((if (b) thr - soft[j] else soft[j] - thr) / (hi - lo)).coerceIn(-1.0, 1.0) }
            want(i, 0, true); want(n - 1, i, true)
            want(0, i, i % 2 == 0); want(i, n - 1, i % 2 == 1)
        }
        return Grid(n, black, conf, hidden, good.toDouble() / all, corners, sf / all)
    }

    private fun decodeGrid(g: Grid): Result? {
        val rows = g.n; val cols = g.n
        if (g.fit < 0.6) return null
        val total = try { ZxingDataMatrixAccess.totalCodewords(rows, cols) } catch (e: Exception) { return null }
        // Codewords and how sure each is (its least certain bit); unknown if any bit is hidden.
        val map = ZxingDataMatrixAccess.moduleMap(rows, cols)
        val value = IntArray(total); val conf = DoubleArray(total) { 1.0 }; val unknown = BooleanArray(total)
        for (i in map.indices) {
            val m = map[i]; if (m < 0) continue
            val cw = m / 8; val bit = m % 8
            if (cw >= total) continue
            if (g.black[i]) value[cw] = value[cw] or (1 shl bit)
            conf[cw] = min(conf[cw], g.conf[i])
            if (g.hidden[i]) unknown[cw] = true
        }
        val data = ArrayList<Int>(); var erased = 0; var fixed = 0
        for ((idx, nData) in ZxingDataMatrixAccess.blocks(rows, cols)) {
            val ecc = idx.size - nData
            val known = idx.indices.filter { unknown[idx[it]] }
            val order = idx.indices.filter { !unknown[idx[it]] }.sortedBy { conf[idx[it]] }
            var ok: IntArray? = null
            // Attempts, most trusted first: the known erasures (plus a few errors); errors only, as
            // ZXing does; then the least certain codewords erased as well, two more at a time.
            val attempts = ArrayList<Pair<List<Int>, Int>>()
            attempts += known to (ecc - known.size - SPARE) / 2
            attempts += emptyList<Int>() to ecc / 2
            var extra = 2
            while (known.size + extra <= ecc - SPARE_GUESSED) {
                val er = known + order.take(extra)
                attempts += er to (ecc - er.size - SPARE_GUESSED) / 2
                val g = order.take(extra)
                attempts += g to (ecc - g.size - SPARE_GUESSED) / 2
                extra += 2
            }
            for ((er, maxErr) in attempts) {
                if (maxErr < 0) continue
                val cw = IntArray(idx.size) { value[idx[it]] }
                if (ReedSolomonErasures.decode(cw, ecc, er, maxErrors = maxErr)) {
                    ok = cw; erased += er.size
                    fixed += idx.indices.count { cw[it] != value[idx[it]] }
                    break
                }
            }
            if (ok == null) return null
            for (k in 0 until nData) data += ok[k]
        }
        // Blocks interleave: data codeword j of block b sits at j·blocks + b.
        val blocks = ZxingDataMatrixAccess.blocks(rows, cols)
        val bytes = ByteArray(blocks.sumOf { it.second })
        var off = 0
        for ((b, blk) in blocks.withIndex()) { for (k in 0 until blk.second) bytes[k * blocks.size + b] = data[off + k].toByte(); off += blk.second }
        val text = ZxingDataMatrixAccess.parse(bytes) ?: return null
        return Result(text, erased, fixed, rows * cols)
    }
}
