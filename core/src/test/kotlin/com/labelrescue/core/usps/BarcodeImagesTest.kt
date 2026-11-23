package com.labelrescue.core.usps

import com.google.zxing.common.BitMatrix
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.image.GrayImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeImagesTest {
    /** The barcode as an image (each module [scale] pixels, a white border). */
    private fun image(m: BitMatrix, scale: Int, height: Int? = null): GrayImage {
        val h = height ?: (m.height * scale)
        val img = GrayImage.filled(m.width * scale + 40, h + 40, 255)
        for (y in 0 until h) for (x in 0 until m.width * scale) {
            val my = if (height != null) 0 else y / scale
            if (m.get(x / scale, my)) img[x + 20, y + 20] = 0
        }
        return img
    }

    private fun read(img: GrayImage) = ZxingDecoder().decode(img).flatMap { ImpbParser.extractFromPayload(it.text) }

    @Test fun impbReadsBackAsTheNumber() {
        for ((t, zip) in listOf("9434650106151175671291" to null, "9234690275075500211709" to "05641", "92612909888642571048257340" to "054489684")) {
            val found = read(image(BarcodeImages.impb(t, zip), 3, height = 120))
            assertTrue("$t: $found", found.any { it.tracking == t && it.zip == zip })
        }
    }

    @Test fun immbReadsBackAsTheNumber() {
        val t = "9434650106151175671291"
        val found = read(image(BarcodeImages.immb(t, "05448"), 6))
        assertEquals(t, found.first { it.checkValid }.tracking)
    }
}
