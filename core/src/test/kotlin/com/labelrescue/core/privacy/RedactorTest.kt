package com.labelrescue.core.privacy

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrLine
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RedactorTest {
    @Test fun keepsBoilerplateAndNumbers() {
        for (t in listOf(
            "USPS GROUND ADVANTAGE", "SP: GROUND ADVANTAGE", "USPS TRACKING # USPS Ship", "PS T ACKING # US S Ship",
            "9234 6902 7507 5500 2117 09", "Mailed from 08081", "Created 11/16/2026", "1 lb 1", "RDC 01", "G",
            "U.S. POSTAGE PAID", "PRIORITY MAIL 2-DAY", "usps.com",
        )) assertTrue(t, Redactor.isSafe(t))
    }

    @Test fun blanksNamesAndAddresses() {
        for (t in listOf(
            "JANE EXAMPLE JANE EXAMPLE", "742 EVERGREEN TERRACE", "SPRINGFIELD OR 97403", "101 SAMPLE PARKWAY",
            "ACME WIDGETS LLC", "SHIP TO: JOHN Q PUBLIC",
        )) assertFalse(t, Redactor.isSafe(t))
    }

    @Test fun whitesOutOnlyUnsafeLinesAndFailsSafe() {
        val name = OcrLine("JANE EXAMPLE", 0.9f, IntRect(10, 10, 60, 20), 0f, emptyList())
        val service = OcrLine("USPS GROUND ADVANTAGE", 0.9f, IntRect(10, 40, 90, 50), 0f, emptyList())
        val boxes = Redactor.blankBoxes(listOf(name to name.box, service to service.box), 100, 100)!!
        assertEquals(1, boxes.size)
        val img = Redactor.apply(GrayImage(100, 100), boxes)
        assertEquals(255, img[30, 15]); assertEquals(0, img[30, 45])
        assertNull("unsafe line without a position → no photo", Redactor.blankBoxes(listOf(name to null), 100, 100))
        assertNull("no OCR at all → no photo", Redactor.blankBoxes(emptyList(), 100, 100))
    }
}
