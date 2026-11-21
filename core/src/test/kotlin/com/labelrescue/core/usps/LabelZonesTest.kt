package com.labelrescue.core.usps

import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelZonesTest {
    private fun line(t: String, l: Int, top: Int, r: Int, h: Int = 24) = OcrLine(t, 0.9f, null, 0f, emptyList()) to IntRect(l, top, r, top + h)

    // A UPS Ground Saver label (portrait frame 1080x1440), laid out like the real ones.
    private val groundSaver = listOf(
        line("UPS GROUND SAVER", 180, 300, 560, 30),
        line("TRACKING #: 12 201 266 YW 2698 1154", 180, 340, 640),
        // UPS barcode: 360..470
        line("USPS PARCEL SELECT", 340, 520, 560),
        line("USPS TRACKING #", 300, 1000, 560),
        // USPS barcode: 1030..1160
        line("9261 2909 8886 4257 1048 2573 40", 200, 1180, 880),
    )

    @Test
    fun upsBlockAndUspsAreaOnGroundSaver() {
        val number = listOf(IntRect(200, 1180, 880, 1204))
        val z = LabelZones.find(groundSaver, number, 1080, 1440)
        val ups = z.ups; val usps = z.usps
        assertNotNull(ups); assertNotNull(usps)
        // The UPS barcode band sits in the UPS block; the USPS barcode band doesn't.
        assertTrue(z.centerInUps(IntRect(200, 380, 620, 450)))
        assertFalse(z.centerInUps(IntRect(220, 1050, 860, 1140)))
        assertTrue(z.inUps(IntRect(190, 370, 630, 460)))
        // The USPS area holds the USPS barcode and stops before the UPS block.
        assertTrue(usps!!.top <= 1040 && usps.bottom >= 1150)
        assertTrue(ups!!.bottom <= 520)
        // The UPS line itself is not taken as the USPS number line.
        assertTrue(z.centerInUps(IntRect(180, 340, 640, 364)))
    }

    @Test
    fun plainUspsLabelHasNoUpsBlock() {
        val lines = listOf(
            line("USPS GROUND ADVANTAGE", 200, 200, 700),
            line("USPS TRACKING # EP", 300, 900, 600),
            line("9234 6902 7597 6500 2117 09", 220, 1150, 860),
        )
        val z = LabelZones.find(lines, listOf(IntRect(220, 1150, 860, 1174)), 1080, 1440)
        assertNull(z.ups)
        assertNotNull(z.usps)
        assertFalse(z.inUps(IntRect(220, 950, 860, 1120)))
    }
}
