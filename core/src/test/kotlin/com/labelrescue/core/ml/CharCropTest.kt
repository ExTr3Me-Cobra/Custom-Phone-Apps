package com.labelrescue.core.ml

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharCropTest {
    /** A row of five dark "glyphs" 12 wide, 30 tall, 6 apart, on paper. */
    private val img = GrayImage.filled(200, 80, 235).also { im ->
        for (k in 0 until 5) for (y in 20 until 50) for (x in 40 + k * 18 until 52 + k * 18) im[x, y] = 30
    }
    private fun glyph(k: Int) = IntRect(40 + k * 18, 20, 52 + k * 18, 50)

    @Test fun offCenterBoxSnapsToTheGlyph() {
        // Shifted by a third of the pitch, and a box spanning half the neighbor.
        assertEquals(glyph(2), CharCrop.refine(img, IntRect(82, 18, 94, 52)))
        assertEquals(glyph(2), CharCrop.refine(img, IntRect(70, 20, 100, 50)))
    }

    @Test fun cropShowsOneGlyphOnly() {
        val c = CharCrop.extract(img, IntRect(80, 20, 100, 50))
        val s = CharCrop.SIZE
        // Ink in the middle columns, none at the sides (the neighbors are blanked).
        val mid = (0 until s).sumOf { y -> c[y * s + s / 2].toDouble() }
        val side = (0 until s).sumOf { y -> (c[y * s + 1] + c[y * s + s - 2]).toDouble() }
        assertTrue("mid=$mid side=$side", mid > 10 && side == 0.0)
    }

    @Test fun blankAreaKeepsTheBox() {
        val blank = GrayImage.filled(100, 60, 235)
        assertEquals(IntRect(40, 10, 52, 40), CharCrop.refine(blank, IntRect(40, 10, 52, 40)))
    }

    @Test fun deadLineThroughADigitDoesNotSplitIt() {
        // One glyph 18 wide, 30 tall, with a 3 px dead column through it; neighbours 6 px away.
        val im = GrayImage.filled(200, 80, 235)
        for (k in 0 until 3) for (y in 20 until 50) for (x in 40 + k * 24 until 58 + k * 24) im[x, y] = 30
        for (y in 0 until 80) for (x in 71 until 74) im[x, y] = 232
        assertEquals(IntRect(64, 20, 82, 50), CharCrop.refine(im, IntRect(64, 20, 82, 50)))
        assertEquals(IntRect(64, 20, 82, 50), CharCrop.refine(im, IntRect(60, 20, 72, 50)))
    }
}
