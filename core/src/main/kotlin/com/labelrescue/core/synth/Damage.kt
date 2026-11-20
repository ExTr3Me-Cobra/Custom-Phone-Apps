package com.labelrescue.core.synth

import com.labelrescue.core.barcode.Code128
import com.labelrescue.core.image.Filters
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Renders synthetic barcodes and applies label damage, for tests and the accuracy benchmark. */
object Synth {
    const val PAPER = 232
    const val INK = 28

    /** Renders Code 128 symbol values as a barcode image. Module width may be fractional. */
    fun renderBarcode(values: IntArray, module: Double = 3.0, height: Int = 110, quietModules: Int = 12, margin: Int = 8): GrayImage {
        val mods = Code128.encodeModules(values, quietModules)
        val w = (mods.size * module).roundToInt()
        val img = GrayImage.filled(w, height + 2 * margin, PAPER)
        for (x in 0 until w) {
            // Area coverage of the module grid for anti-aliasing.
            val a = x / module; val b = (x + 1) / module
            var cover = 0.0
            var m = a.toInt()
            while (m < b && m < mods.size) {
                val lo = max(a, m.toDouble()); val hi = min(b, m + 1.0)
                if (mods[m]) cover += hi - lo
                m++
            }
            cover /= (b - a)
            val v = (PAPER - cover * (PAPER - INK)).roundToInt()
            for (y in margin until margin + height) img[x, y] = v
        }
        return img
    }

    fun blur(img: GrayImage, sigma: Double) = Filters.gaussianBlur(img, sigma)

    fun noise(img: GrayImage, sigma: Double, rnd: Random): GrayImage {
        val out = img.copy()
        for (i in out.data.indices) {
            val g = rnd.nextGaussian() * sigma
            out.data[i] = ((out.data[i].toInt() and 0xFF) + g).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** White blobs where ink didn't print or rubbed off. */
    fun inkDropout(img: GrayImage, rnd: Random, count: Int, maxRadius: Int): GrayImage = blobs(img, rnd, count, maxRadius, PAPER)

    /** Dark smears. */
    fun smudge(img: GrayImage, rnd: Random, count: Int, maxRadius: Int, darkness: Int = 70): GrayImage = blobs(img, rnd, count, maxRadius, darkness)

    private fun blobs(img: GrayImage, rnd: Random, count: Int, maxRadius: Int, value: Int): GrayImage {
        val out = img.copy()
        repeat(count) {
            val cx = rnd.nextInt(img.width); val cy = rnd.nextInt(img.height)
            val rx = 2 + rnd.nextInt(max(1, maxRadius)); val ry = 2 + rnd.nextInt(max(1, maxRadius))
            for (y in max(0, cy - ry) until min(img.height, cy + ry)) for (x in max(0, cx - rx) until min(img.width, cx + rx)) {
                val dx = (x - cx).toDouble() / rx; val dy = (y - cy).toDouble() / ry
                val d = dx * dx + dy * dy
                if (d < 1) {
                    val a = (1 - d).coerceIn(0.0, 1.0).let { sqrt(it) }
                    out[x, y] = (out[x, y] * (1 - a) + value * a).roundToInt()
                }
            }
        }
        return out
    }

    /** Erases a region (a tear or a missing corner); [fill] is what shows through. */
    fun tear(img: GrayImage, r: IntRect, fill: Int = PAPER, rnd: Random? = null): GrayImage {
        val out = img.copy()
        val c = r.intersect(IntRect(0, 0, img.width, img.height))
        for (y in c.top until c.bottom) for (x in c.left until c.right) {
            out[x, y] = fill + (rnd?.nextInt(-6, 7) ?: 0)
        }
        return out
    }

    /** A vertical band of lost print across the whole height (destroys whole symbols). */
    fun verticalLoss(img: GrayImage, x0: Int, width: Int): GrayImage = tear(img, IntRect(x0, 0, x0 + width, img.height))

    /** Fades ink toward paper (thermal print fading). */
    fun fade(img: GrayImage, amount: Double): GrayImage {
        val out = img.copy()
        for (i in out.data.indices) {
            val v = out.data[i].toInt() and 0xFF
            out.data[i] = (v + (PAPER - v) * amount).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    fun rotate(img: GrayImage, degrees: Double) = img.rotated(degrees, PAPER).first

    /** Uneven lighting: a gradient across the image. */
    fun shade(img: GrayImage, strength: Int): GrayImage {
        val out = img.copy()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            out[x, y] = img[x, y] - strength * x / img.width
        }
        return out
    }

    private fun Random.nextGaussian(): Double {
        var u: Double; var v: Double; var s: Double
        do { u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * sqrt(-2 * kotlin.math.ln(s) / s)
    }
}
