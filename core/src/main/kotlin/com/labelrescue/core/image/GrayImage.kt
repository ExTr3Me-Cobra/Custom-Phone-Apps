package com.labelrescue.core.image

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 8-bit grayscale image, row-major, 0 = black, 255 = white. */
class GrayImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init {
        require(width > 0 && height > 0 && data.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, v: Int) {
        data[y * width + x] = v.coerceIn(0, 255).toByte()
    }

    fun getClamped(x: Int, y: Int): Int = get(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    /** Bilinear sample; outside the image returns [outside]. */
    fun sample(x: Double, y: Double, outside: Int = 255): Double {
        if (x < -0.5 || y < -0.5 || x > width - 0.5 || y > height - 0.5) return outside.toDouble()
        val fx = (x).coerceIn(0.0, width - 1.0)
        val fy = (y).coerceIn(0.0, height - 1.0)
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val ax = fx - x0; val ay = fy - y0
        val top = get(x0, y0) * (1 - ax) + get(x1, y0) * ax
        val bot = get(x0, y1) * (1 - ax) + get(x1, y1) * ax
        return top * (1 - ay) + bot * ay
    }

    fun copy() = GrayImage(width, height, data.copyOf())

    fun crop(r: IntRect): GrayImage {
        val c = r.intersect(IntRect(0, 0, width, height))
        require(!c.isEmpty) { "empty crop" }
        val out = GrayImage(c.width, c.height)
        for (y in 0 until c.height) System.arraycopy(data, (c.top + y) * width + c.left, out.data, y * c.width, c.width)
        return out
    }

    /** Bilinear resize. */
    fun scaled(newW: Int, newH: Int): GrayImage {
        val out = GrayImage(max(1, newW), max(1, newH))
        val sx = width.toDouble() / out.width
        val sy = height.toDouble() / out.height
        if (sx >= 2 || sy >= 2) {
            // Downscale by area averaging to avoid aliasing thin bars.
            for (y in 0 until out.height) for (x in 0 until out.width) {
                val x0 = (x * sx).toInt(); val x1 = max(x0 + 1, ((x + 1) * sx).toInt())
                val y0 = (y * sy).toInt(); val y1 = max(y0 + 1, ((y + 1) * sy).toInt())
                var s = 0; var n = 0
                for (yy in y0 until min(y1, height)) for (xx in x0 until min(x1, width)) { s += get(xx, yy); n++ }
                out[x, y] = if (n > 0) s / n else 255
            }
            return out
        }
        for (y in 0 until out.height) for (x in 0 until out.width) {
            out[x, y] = sample((x + 0.5) * sx - 0.5, (y + 0.5) * sy - 0.5).roundToInt()
        }
        return out
    }

    fun scaledBy(f: Double) = scaled((width * f).roundToInt(), (height * f).roundToInt())

    /** Limits the longest side to [maxSide] (returns this if already small enough). */
    fun limited(maxSide: Int): GrayImage {
        val m = max(width, height)
        return if (m <= maxSide) this else scaledBy(maxSide.toDouble() / m)
    }

    /**
     * Rotates by [degrees] counter-clockwise around the center, expanding the canvas. Returns the
     * image and the transform from the new image's coordinates back to this image's.
     */
    fun rotated(degrees: Double, fill: Int = 255): Pair<GrayImage, Affine> {
        if (abs(degrees) < 1e-6) return this to Affine.IDENTITY
        val rad = Math.toRadians(degrees)
        val c = cos(rad); val s = sin(rad)
        val nw = (abs(width * c) + abs(height * s)).roundToInt()
        val nh = (abs(width * s) + abs(height * c)).roundToInt()
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        val ncx = (nw - 1) / 2.0; val ncy = (nh - 1) / 2.0
        // new -> old: rotate back by -degrees (image y axis points down, so CCW visual = this form).
        val back = Affine(c, -s, cx - c * ncx + s * ncy, s, c, cy - s * ncx - c * ncy)
        val out = GrayImage(nw, nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val ox = back.a * x + back.b * y + back.c
            val oy = back.d * x + back.e * y + back.f
            out[x, y] = sample(ox, oy, fill).roundToInt()
        }
        return out to back
    }

    /** 90° steps, clockwise. Transform maps new coordinates back to this image. */
    fun rotated90(times: Int): Pair<GrayImage, Affine> {
        return when (((times % 4) + 4) % 4) {
            0 -> this to Affine.IDENTITY
            1 -> { // clockwise: new(x,y) = old(y, H-1-x)
                val out = GrayImage(height, width)
                for (y in 0 until out.height) for (x in 0 until out.width) out[x, y] = get(y, height - 1 - x)
                out to Affine(0.0, 1.0, 0.0, -1.0, 0.0, height - 1.0)
            }
            2 -> {
                val out = GrayImage(width, height)
                for (y in 0 until height) for (x in 0 until width) out[x, y] = get(width - 1 - x, height - 1 - y)
                out to Affine(-1.0, 0.0, width - 1.0, 0.0, -1.0, height - 1.0)
            }
            else -> { // counter-clockwise: new(x,y) = old(W-1-y, x)
                val out = GrayImage(height, width)
                for (y in 0 until out.height) for (x in 0 until out.width) out[x, y] = get(width - 1 - y, x)
                out to Affine(0.0, -1.0, width - 1.0, 1.0, 0.0, 0.0)
            }
        }
    }

    fun inverted(): GrayImage = GrayImage(width, height, ByteArray(data.size) { (255 - (data[it].toInt() and 0xFF)).toByte() })

    fun mean(): Double = data.sumOf { it.toInt() and 0xFF }.toDouble() / data.size

    companion object {
        fun filled(w: Int, h: Int, v: Int) = GrayImage(w, h, ByteArray(w * h) { v.toByte() })

        /** From ARGB pixels (Android Bitmap.getPixels layout), using luma. */
        fun fromArgb(w: Int, h: Int, argb: IntArray): GrayImage {
            val out = GrayImage(w, h)
            for (i in argb.indices) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                out.data[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            return out
        }
    }
}

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
    val centerX get() = (left + right) / 2.0
    val centerY get() = (top + bottom) / 2.0

    fun intersect(o: IntRect) = IntRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))
    fun union(o: IntRect) = IntRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun inflate(dx: Int, dy: Int) = IntRect(left - dx, top - dy, right + dx, bottom + dy)
}

/** 2D affine transform: x' = a x + b y + c, y' = d x + e y + f. */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun mapX(x: Double, y: Double) = a * x + b * y + c
    fun mapY(x: Double, y: Double) = d * x + e * y + f

    /** this ∘ other: apply [other] first, then this. */
    fun then(outer: Affine): Affine = Affine(
        outer.a * a + outer.b * d, outer.a * b + outer.b * e, outer.a * c + outer.b * f + outer.c,
        outer.d * a + outer.e * d, outer.d * b + outer.e * e, outer.d * c + outer.e * f + outer.f,
    )

    /** Maps a rectangle and returns the bounding box. */
    fun mapRect(r: IntRect): IntRect {
        val xs = doubleArrayOf(mapX(r.left.toDouble(), r.top.toDouble()), mapX(r.right.toDouble(), r.top.toDouble()), mapX(r.left.toDouble(), r.bottom.toDouble()), mapX(r.right.toDouble(), r.bottom.toDouble()))
        val ys = doubleArrayOf(mapY(r.left.toDouble(), r.top.toDouble()), mapY(r.right.toDouble(), r.top.toDouble()), mapY(r.left.toDouble(), r.bottom.toDouble()), mapY(r.right.toDouble(), r.bottom.toDouble()))
        return IntRect(xs.min().toInt(), ys.min().toInt(), xs.max().toInt() + 1, ys.max().toInt() + 1)
    }

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        fun translate(dx: Double, dy: Double) = Affine(1.0, 0.0, dx, 0.0, 1.0, dy)
        fun scale(s: Double) = Affine(s, 0.0, 0.0, 0.0, s, 0.0)
    }
}
