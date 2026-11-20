package com.labelrescue.core.image

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Filters {

    /** Contrast-limited adaptive histogram equalization. */
    fun clahe(img: GrayImage, tilesX: Int = 8, tilesY: Int = 8, clipLimit: Double = 2.5): GrayImage {
        val tx = max(1, min(tilesX, img.width / 8)); val ty = max(1, min(tilesY, img.height / 8))
        val tw = (img.width + tx - 1) / tx; val th = (img.height + ty - 1) / ty
        // Per-tile mapping tables.
        val maps = Array(ty) { Array(tx) { IntArray(256) } }
        for (j in 0 until ty) for (i in 0 until tx) {
            val hist = IntArray(256)
            val x0 = i * tw; val y0 = j * th
            val x1 = min(img.width, x0 + tw); val y1 = min(img.height, y0 + th)
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { hist[img[x, y]]++; n++ }
            if (n == 0) { for (v in 0..255) maps[j][i][v] = v; continue }
            val limit = max(1, (clipLimit * n / 256).toInt())
            var excess = 0
            for (v in 0..255) if (hist[v] > limit) { excess += hist[v] - limit; hist[v] = limit }
            val add = excess / 256; val rem = excess % 256
            for (v in 0..255) hist[v] += add + if (v < rem) 1 else 0
            var cum = 0
            for (v in 0..255) { cum += hist[v]; maps[j][i][v] = (cum * 255L / n).toInt() }
        }
        val out = GrayImage(img.width, img.height)
        for (y in 0 until img.height) {
            val gy = (y - th / 2.0) / th
            val j0 = gy.toInt().coerceIn(0, ty - 1); val j1 = min(j0 + 1, ty - 1)
            val wy = (gy - j0).coerceIn(0.0, 1.0)
            for (x in 0 until img.width) {
                val gx = (x - tw / 2.0) / tw
                val i0 = gx.toInt().coerceIn(0, tx - 1); val i1 = min(i0 + 1, tx - 1)
                val wx = (gx - i0).coerceIn(0.0, 1.0)
                val v = img[x, y]
                val top = maps[j0][i0][v] * (1 - wx) + maps[j0][i1][v] * wx
                val bot = maps[j1][i0][v] * (1 - wx) + maps[j1][i1][v] * wx
                out[x, y] = (top * (1 - wy) + bot * wy).roundToInt()
            }
        }
        return out
    }

    /** Integral image (with an extra leading row and column of zeros). */
    private fun integral(img: GrayImage): LongArray {
        val w = img.width + 1
        val s = LongArray(w * (img.height + 1))
        for (y in 0 until img.height) {
            var row = 0L
            for (x in 0 until img.width) {
                row += img[x, y]
                s[(y + 1) * w + x + 1] = s[y * w + x + 1] + row
            }
        }
        return s
    }

    /** Mean filter with a (2r+1)² window using an integral image. */
    fun boxBlur(img: GrayImage, r: Int): GrayImage {
        if (r <= 0) return img.copy()
        val s = integral(img); val w = img.width + 1
        val out = GrayImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val x0 = max(0, x - r); val x1 = min(img.width, x + r + 1)
            val y0 = max(0, y - r); val y1 = min(img.height, y + r + 1)
            val sum = s[y1 * w + x1] - s[y0 * w + x1] - s[y1 * w + x0] + s[y0 * w + x0]
            out[x, y] = (sum / ((x1 - x0) * (y1 - y0))).toInt()
        }
        return out
    }

    /** Local mean threshold: black where the pixel is darker than (local mean − c). */
    fun adaptiveThreshold(img: GrayImage, radius: Int = max(7, min(img.width, img.height) / 24), c: Int = 8): GrayImage {
        val mean = boxBlur(img, radius)
        val out = GrayImage(img.width, img.height)
        for (i in img.data.indices) {
            val v = img.data[i].toInt() and 0xFF
            val m = mean.data[i].toInt() and 0xFF
            out.data[i] = if (v < m - c) 0 else 255.toByte()
        }
        return out
    }

    fun gaussianBlur(img: GrayImage, sigma: Double): GrayImage {
        if (sigma <= 0.05) return img.copy()
        val r = max(1, (sigma * 3).toInt())
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = DoubleArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var a = 0.0
            for (i in -r..r) a += k[i + r] * img.getClamped(x + i, y)
            tmp[y * img.width + x] = a
        }
        val out = GrayImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var a = 0.0
            for (i in -r..r) a += k[i + r] * tmp[(y + i).coerceIn(0, img.height - 1) * img.width + x]
            out[x, y] = a.roundToInt()
        }
        return out
    }

    /** Unsharp mask. */
    fun sharpen(img: GrayImage, sigma: Double = 1.2, amount: Double = 1.2): GrayImage {
        val blur = gaussianBlur(img, sigma)
        val out = GrayImage(img.width, img.height)
        for (i in img.data.indices) {
            val v = img.data[i].toInt() and 0xFF
            val b = blur.data[i].toInt() and 0xFF
            out.data[i] = (v + amount * (v - b)).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Stretches the 1st..99th percentile to the full range. */
    fun normalize(img: GrayImage): GrayImage {
        val hist = IntArray(256)
        for (b in img.data) hist[b.toInt() and 0xFF]++
        val n = img.data.size
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] < n / 100) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] < n / 100) { acc += hist[hi]; hi-- }
        if (hi <= lo) return img.copy()
        val out = GrayImage(img.width, img.height)
        for (i in img.data.indices) out.data[i] = (((img.data[i].toInt() and 0xFF) - lo) * 255 / (hi - lo)).coerceIn(0, 255).toByte()
        return out
    }

    fun otsu(values: IntArray, from: Int = 0, to: Int = values.size): Int {
        val hist = IntArray(256)
        for (i in from until to) hist[values[i].coerceIn(0, 255)]++
        val total = to - from
        var sum = 0.0
        for (v in 0..255) sum += v * hist[v].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var t = 128
        for (v in 0..255) {
            wB += hist[v]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += v * hist[v].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = v }
        }
        return t
    }

    /**
     * Dominant edge orientation, in degrees within (-45, 45]: how far the strongest straight
     * structure (barcode bars, text baselines) is tilted from the image axes.
     */
    fun estimateSkew(img: GrayImage): Double {
        val small = img.limited(600)
        val bins = 180
        val hist = DoubleArray(bins)
        for (y in 1 until small.height - 1) for (x in 1 until small.width - 1) {
            val gx = (small[x + 1, y - 1] + 2 * small[x + 1, y] + small[x + 1, y + 1]) - (small[x - 1, y - 1] + 2 * small[x - 1, y] + small[x - 1, y + 1])
            val gy = (small[x - 1, y + 1] + 2 * small[x, y + 1] + small[x + 1, y + 1]) - (small[x - 1, y - 1] + 2 * small[x, y - 1] + small[x + 1, y - 1])
            val mag = hypot(gx.toDouble(), gy.toDouble())
            if (mag < 80) continue
            // Orientation of the gradient folded into 90 degrees (bars and text both align to axes).
            var a = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble()))
            a = ((a % 90) + 90) % 90 // 0..90
            hist[(a * 2).toInt().coerceIn(0, bins - 1)] += mag
        }
        // Smooth circularly and find the peak.
        var bestI = 0; var best = -1.0
        for (i in 0 until bins) {
            var s = 0.0
            for (k in -3..3) s += hist[((i + k) % bins + bins) % bins] * (4 - abs(k))
            if (s > best) { best = s; bestI = i }
        }
        var deg = bestI / 2.0
        if (deg > 45) deg -= 90
        return deg
    }

    /** Fraction of strong horizontal gradient energy (high for vertical barcode bars). */
    fun sharpness(img: GrayImage): Double {
        var s = 0.0
        val small = img.limited(500)
        for (y in 0 until small.height) for (x in 1 until small.width) {
            val d = small[x, y] - small[x - 1, y]
            s += d * d
        }
        return sqrt(s / (small.width * small.height))
    }
}
