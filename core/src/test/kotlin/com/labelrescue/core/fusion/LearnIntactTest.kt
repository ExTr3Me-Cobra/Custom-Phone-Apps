package com.labelrescue.core.fusion

import com.labelrescue.core.barcode.Code128
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrElement
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.OcrSymbol
import com.labelrescue.core.ocr.TextRecognizer
import com.labelrescue.core.synth.Synth
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnIntactTest {
    private val truth = "9234690275075500211709"

    /** OCR that reads the printed number with digit 5 (index 4) misread as 1. */
    private val ocr = TextRecognizer { _ ->
        val elements = ArrayList<OcrElement>()
        var x = 100; var i = 0
        for (g in TrackingFormat.groups(truth.length)) {
            val syms = (0 until g).map { k ->
                val c = if (i == 4) '1' else truth[i]
                OcrSymbol(c.toString(), 0.95f, IntRect(x + 20 * k, 300, x + 20 * k + 16, 330)).also { i++ }
            }
            x += 20 * g + 20
            elements += OcrElement(syms.joinToString("") { it.text }, 0.9f, IntRect(syms.first().box!!.left, 300, syms.last().box!!.right, 330), syms)
        }
        OcrPage(listOf(OcrLine("USPS GROUND ADVANTAGE", 0.9f, IntRect(100, 200, 500, 230), 0f, emptyList()), OcrLine(elements.joinToString(" ") { it.text }, 0.9f, IntRect(100, 300, x, 330), 0f, elements)))
    }

    private fun label(): GrayImage {
        val bc = Synth.renderBarcode(Code128.impbValues(truth, "05641"), 3.0, height = 120)
        val img = GrayImage.filled(bc.width + 200, bc.height + 400, Synth.PAPER)
        for (y in 0 until bc.height) for (x in 0 until bc.width) img[x + 100, y + 40] = bc[x, y]
        return img
    }

    @Test fun intactLabelYieldsLocatedDigitsExceptMisreadOnes() {
        val out = RecoveryPipeline(listOf(ZxingDecoder()), ocr).run(listOf(label()), RecoveryPipeline.Options(learnFromIntact = true))
        assertEquals(truth, out.fullDecode?.tracking)
        assertFalse("the misread digit is not a training example", 4 in out.locations)
        assertEquals(truth.length - 1, out.locations.size)
        assertTrue(out.locations.values.all { it.source == Source.OCR })
        assertTrue("OCR evidence for the agreement check", out.perSource[Source.OCR]?.get(0)?.best == '9')
    }

    @Test fun offByDefault() {
        val out = RecoveryPipeline(listOf(ZxingDecoder()), ocr).run(listOf(label()))
        assertEquals(truth, out.fullDecode?.tracking)
        assertTrue(out.locations.isEmpty())
    }
}
