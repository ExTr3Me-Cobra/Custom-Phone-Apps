package com.labelrescue.core.fusion

import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrElement
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.OcrSymbol
import com.labelrescue.core.ocr.TextRecognizer
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReadTest {
    private val truth = "9234690275075500211709"

    /** OCR that reads [printed] cleanly every time. */
    private fun ocr(printed: String) = TextRecognizer { _ ->
        val elements = ArrayList<OcrElement>()
        var x = 100; var i = 0
        for (g in TrackingFormat.groups(printed.length)) {
            val syms = (0 until g).map { k -> OcrSymbol(printed[i].toString(), 0.95f, IntRect(x + 20 * k, 300, x + 20 * k + 16, 330)).also { i++ } }
            elements += OcrElement(syms.joinToString("") { it.text }, 0.95f, IntRect(x, 300, x + 20 * g - 4, 330), syms)
            x += 20 * g + 20
        }
        OcrPage(listOf(
            OcrLine("USPS TRACKING #", 0.9f, IntRect(200, 200, 500, 230), 0f, emptyList()),
            OcrLine(elements.joinToString(" ") { it.text }, 0.95f, IntRect(100, 300, x, 330), 0f, elements),
        ))
    }

    private fun frames() = List(3) { GrayImage.filled(900, 600, 230) }

    @Test fun clearPrintStopsBeforeTheBarcodeWork() {
        val log = DiagnosticLog()
        val out = RecoveryPipeline(listOf(ZxingDecoder()), ocr(truth), log = log).run(frames())
        println(log.text().lines().filter { "quick" in it || "checkpoint" in it }.joinToString("\n"))
        assertEquals(truth, out.slots.joinToString("") { it.best.toString() })
        assertTrue(out.framesStoppedEarly)
        assertEquals(2, out.framesUsed)
        assertTrue(log.text(), "quick read: printed number clear" in log.text())
        assertFalse("no barcode work", "barcode regions" in log.text())
    }

    @Test fun checkDigitFailureReadsEverything() {
        val wrong = truth.substring(0, 4) + "1" + truth.substring(5) // a misread digit: check digit fails
        val log = DiagnosticLog()
        RecoveryPipeline(listOf(ZxingDecoder()), ocr(wrong), log = log).run(frames())
        assertTrue(log.text(), "check digit fails" in log.text())
        assertTrue("full scan ran", "barcode regions" in log.text())
    }

    @Test fun offWhenQuickScanIsOff() {
        val log = DiagnosticLog()
        RecoveryPipeline(listOf(ZxingDecoder()), ocr(truth), log = log).run(frames(), RecoveryPipeline.Options(earlyStop = false))
        assertFalse("quick read:" in log.text())
    }
}
