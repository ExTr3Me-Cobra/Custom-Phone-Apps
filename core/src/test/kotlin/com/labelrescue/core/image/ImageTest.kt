package com.labelrescue.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImageTest {
    private fun gradient(w: Int, h: Int) = GrayImage(w, h).also { for (y in 0 until h) for (x in 0 until w) it[x, y] = (x * 3 + y * 5) % 256 }

    @Test fun rotate90TransformsMapBack() {
        val img = gradient(30, 20)
        for (t in 0..3) {
            val (r, back) = img.rotated90(t)
            for ((x, y) in listOf(0 to 0, r.width - 1 to 0, 3 to 7, r.width - 1 to r.height - 1)) {
                val ox = back.mapX(x.toDouble(), y.toDouble()).toInt(); val oy = back.mapY(x.toDouble(), y.toDouble()).toInt()
                assertEquals("t=$t ($x,$y)", img[ox, oy], r[x, y])
            }
        }
    }

    @Test fun arbitraryRotationTransformMapsBack() {
        val img = gradient(80, 50)
        val (r, back) = img.rotated(7.0)
        val x = r.width / 2 + 5; val y = r.height / 2 - 3
        val v = img.sample(back.mapX(x.toDouble(), y.toDouble()), back.mapY(x.toDouble(), y.toDouble()))
        assertTrue(abs(v - r[x, y]) <= 1.0)
    }

    @Test fun affineComposition() {
        val a = Affine.translate(10.0, 5.0)
        val s = Affine.scale(2.0)
        val c = s.then(a) // scale, then translate
        assertEquals(14.0, c.mapX(2.0, 0.0), 1e-9)
        assertEquals(9.0, c.mapY(0.0, 2.0), 1e-9)
    }

    @Test fun filtersKeepSize() {
        val img = gradient(64, 40)
        for (f in listOf(Filters.clahe(img), Filters.adaptiveThreshold(img), Filters.sharpen(img), Filters.normalize(img), Filters.boxBlur(img, 2))) {
            assertEquals(64, f.width); assertEquals(40, f.height)
        }
        val t = Filters.adaptiveThreshold(img)
        assertTrue(t.data.all { (it.toInt() and 0xFF) == 0 || (it.toInt() and 0xFF) == 255 })
    }

    @Test fun variantsMapToFrame() {
        val img = gradient(400, 300)
        val vs = Variants.make(img, IntRect(100, 50, 300, 150))
        assertTrue(vs.size >= 6)
        val g = vs.first { it.name == "gray" }
        assertEquals(100.0, g.toFrame.mapX(0.0, 0.0), 1e-9)
        val up = vs.first { it.name.startsWith("upscale") }
        assertEquals(300.0, up.toFrame.mapX(up.image.width.toDouble(), 0.0), 1.0)
    }
}
