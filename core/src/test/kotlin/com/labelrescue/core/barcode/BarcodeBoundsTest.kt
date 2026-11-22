package com.labelrescue.core.barcode

import com.labelrescue.core.image.DeadLines
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.synth.Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BarcodeBoundsTest {
    private val values = Code128.impbValues("9434650106151175671291", "054489684")

    /**
     * A label in a wide frame: ruled lines above and below, the barcode, a label border just past
     * a short quiet zone, box clutter beyond; with [dead] white print-head columns through it all.
     */
    private fun scene(dead: List<Int> = emptyList()): Pair<GrayImage, IntRect> {
        val f = GrayImage.filled(1100, 400, 120)
        for (y in 20 until 380) for (x in 60 until 1040) f[x, y] = 228 // the label
        val bc = Synth.renderBarcode(values, 3.0, height = 120)
        val bx = 110; val by = 150
        for (y in 0 until bc.height) for (x in 0 until bc.width) f[bx + x, by + y] = bc[x, y]
        for (y in 70 until 80) for (x in 70 until 1030) f[x, y] = 25     // ruled line above
        for (y in 330 until 340) for (x in 70 until 1030) f[x, y] = 25   // and below
        // Where the bars themselves start and end (the rendering has its own quiet zones).
        val row = by + bc.height / 2
        val first = (bx until bx + bc.width).first { f[it, row] < 128 }
        val last = (bx until bx + bc.width).last { f[it, row] < 128 }
        for (y in 20 until 380) for (x in last + 22 until last + 26) f[x, y] = 30 // label border, short quiet zone
        for (x in dead) for (y in 20 until 380) if (f[x, y] < 128) f[x, y] = 215
        return f to IntRect(first, by, last + 1, by + bc.height)
    }

    @Test fun findsTheBarsInsideAWideArea() {
        val (f, bars) = scene()
        val b = BarcodeBounds.find(f, IntRect(0, 100, 1100, 300))
        assertNotNull(b)
        assertTrue("$b vs $bars", abs(b!!.barsLeft - bars.left) <= 4 && abs(b.barsRight - bars.right) <= 4)
        // The cleaned crop keeps the bars and paints the border out.
        val c = BarcodeBounds.cleanCrop(f, b)
        val borderX = bars.right + 22 - b.rect.left
        if (borderX < c.width) assertTrue((0 until c.height).all { c[borderX, it] > 200 })
    }

    @Test fun deadColumnsShowInTheRuledLines() {
        val dead = listOf(200, 333, 517, 760)
        val (f, bars) = scene(dead)
        val found = DeadLines.find(f, bars)
        assertEquals(2, found.bands)
        for (x in dead) assertTrue("$x in ${found.lines}", found.lines.any { abs(it.xAt(200) - x) <= 1.5 })
    }
}
