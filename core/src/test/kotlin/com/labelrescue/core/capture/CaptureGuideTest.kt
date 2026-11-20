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
    private val W = 1080; private val H = 1440

    /**
     * A portrait frame: a white label of [scale] × the guide size, shifted by [dx],[dy] pixels, on a
     * brown box (or on [background]), with the barcode across 70% of the label's width.
     */
    private fun frame(scale: Double = 1.0, dx: Int = 0, dy: Int = 0, blur: Double = 0.0, background: Int = 95): GrayImage {
        val f = GrayImage.filled(W, H, background)
        val g = CaptureGuide.DEFAULT_GUIDE.toPixels(W, H)
        val lw = (g.width * scale).toInt(); val lh = (g.height * scale).toInt()
        val lx = (W - lw) / 2 + dx; val ly = (H - lh) / 2 + dy
        for (y in ly until ly + lh) for (x in lx until lx + lw) if (x in 0 until W && y in 0 until H) f[x, y] = 225
        val module = lw * 0.7 / (values.size * 11 + 2 + 20)
        var bc = Synth.renderBarcode(values, module, height = (lh * 0.12).toInt())
        if (blur > 0) bc = Synth.blur(bc, blur)
        val bx = lx + (lw - bc.width) / 2; val by = ly + (lh * 0.6).toInt()
        for (y in 0 until bc.height) for (x in 0 until bc.width) {
            val xx = x + bx; val yy = y + by
            if (xx in 0 until W && yy in 0 until H) f[xx, yy] = bc[x, y]
        }
        return f
    }

    @Test fun readyWhenTheLabelFillsTheCornerMarks() {
        val g = CaptureGuide()
        g.evaluate(frame())
        val s = g.evaluate(frame())
        assertTrue(s.message, s.ready)
        assertTrue(s.aligned && s.focused)
        assertTrue("label found", s.label != null)
    }

    @Test fun onAWhiteBoxTheBarcodeDecides() {
        val g = CaptureGuide()
        g.evaluate(frame(background = 225))
        val s = g.evaluate(frame(background = 225))
        assertTrue(s.message, s.ready)
    }

    @Test fun tooFarOffCenterOrTooClose() {
        assertEquals(CaptureGuide.Issue.TOO_SMALL, CaptureGuide().evaluate(frame(scale = 0.6)).issue)
        assertEquals(CaptureGuide.Issue.OFF_CENTER, CaptureGuide().evaluate(frame(scale = 0.85, dx = 260)).issue)
        val close = CaptureGuide().evaluate(frame(scale = 1.3))
        assertFalse(close.ready)
    }

    @Test fun blurryAfterSharpIsNotReady() {
        val g = CaptureGuide()
        repeat(3) { g.evaluate(frame()) }
        val s = g.evaluate(frame(blur = 4.0))
        assertFalse(s.ready)
        assertEquals(CaptureGuide.Issue.BLURRY, s.issue)
    }

    @Test fun nothingInView() {
        val s = CaptureGuide().evaluate(GrayImage.filled(W, H, 180))
        assertEquals(CaptureGuide.Issue.NO_BARCODE, s.issue)
    }
}
