package com.labelrescue.core.ups

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.TextRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpsStickerTest {
    private fun ocr(vararg texts: String): TextRecognizer {
        var i = 0
        return TextRecognizer { _ -> OcrPage(texts[i++ % texts.size].lines().map { OcrLine(it, 0.9f, null, 0f, emptyList()) }) }
    }
    private val photo = GrayImage.filled(800, 600, 200)

    @Test fun agreeingReadsMakeItSure() {
        // As on a real sticker: the 1Z number with its last 4 digits set apart, and a "12" misread once.
        val r = UpsSticker.read(ocr("P: SMALLS S: DDU I: IRR\nULD + D001\n1Z201266YW2698   1154\n7229437 ZT411 NOV 20", "12201266YW2698 1154"), photo)!!
        assertEquals("1Z201266YW26981154", r.number.tracking)
        assertTrue(r.agreeing >= 3)
        assertEquals(0.99, r.number.confidence, 1e-9)
    }

    @Test fun nothingReadableIsNull() {
        assertNull(UpsSticker.read(ocr("P: SMALLS S: DDU\nULD + D001"), photo))
    }
}
