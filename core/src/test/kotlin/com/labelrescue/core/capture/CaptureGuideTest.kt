package com.labelrescue.core.capture

import com.labelrescue.core.barcode.Code128
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.synth.Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGuideTest {
    private val values = Code128.impbValues("9234690275075500211709", "05641")

    /** A portrait 1080x1440 frame with the barcode placed at [x0],[y0] with module [module]. */
    private fun frame(module: Double, x0: Int, y0: Int, blur: Double = 0.0): GrayImage {
        var bc = Synth.renderBarcode(values, module, height = (module * 45).toInt())
        if (blur > 0) bc = Synth.blur(bc, blur)
        val f = GrayImage.filled(1080, 1440, 200)
        for (y in 0 until bc.height) for (x in 0 until bc.width) {
            val xx = x + x0; val yy = y + y0
            if (xx in 0 until f.width && yy in 0 until f.height) f[xx, yy] = bc[x, y]
        }
        return f
    }

    @Test fun readyWhenLinedUpAndSharp() {
        val g = CaptureGuide()
        val f = frame(3.6, 110, 560)
        g.evaluate(f)
        val s = g.evaluate(f)
        assertTrue(s.message, s.ready)
        assertTrue(s.aligned && s.focused)
    }

    @Test fun tooSmallOrOffCenter() {
        assertEquals(CaptureGuide.Issue.TOO_SMALL, CaptureGuide().evaluate(frame(1.6, 380, 600)).issue)
        val off = CaptureGuide().evaluate(frame(3.6, 110, 100))
        assertFalse(off.ready)
        assertTrue(off.issue == CaptureGuide.Issue.NO_BARCODE || off.issue == CaptureGuide.Issue.OFF_CENTER)
    }

    @Test fun blurryAfterSharpIsNotReady() {
        val g = CaptureGuide()
        repeat(3) { g.evaluate(frame(3.6, 110, 560)) }
        val s = g.evaluate(frame(3.6, 110, 560, blur = 4.0))
        assertFalse(s.ready)
        assertEquals(CaptureGuide.Issue.BLURRY, s.issue)
    }

    @Test fun nothingInView() {
        val s = CaptureGuide().evaluate(GrayImage.filled(1080, 1440, 180))
        assertEquals(CaptureGuide.Issue.NO_BARCODE, s.issue)
    }
}
