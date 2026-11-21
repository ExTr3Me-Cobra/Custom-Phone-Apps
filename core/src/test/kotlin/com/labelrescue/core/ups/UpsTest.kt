package com.labelrescue.core.ups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpsTest {
    // Published test numbers (tracking_number_data): valid and invalid.
    private val valid = listOf("1Z5R89390357567127", "1Z879E930346834440", "1Z410E7W0392751591", "1Z8V92A70367203024", "1ZXX3150YW44070023")

    @Test fun checkDigit() {
        for (v in valid) assertTrue(v, UpsNumber.isValid(v))
        for (v in listOf("2Z5R89390357567127", "1A5R89390357567127", "1Z1111111111111111", "1Z5R89390357567128")) assertFalse(v, UpsNumber.isValid(v))
        assertTrue(UpsNumber("1ZXX3150YW44070023", "t").isUspsHandoff)
        assertEquals("1Z 5R8 939 03 5756 712 7", UpsNumber("1Z5R89390357567127", "t").grouped)
    }

    @Test fun findsPrintedAndOcrDamagedNumbers() {
        assertEquals(listOf("1Z8V92A70367203024"), UpsNumber.find("TRACKING #: 1Z 8V9 2A7 03 6720 302 4\nBILLING: P/P", "ocr").map { it.tracking })
        // OCR read the 0 as O: one lookalike swap restores the check digit.
        val fixed = UpsNumber.find("1Z 5R8 939 O3 5756 712 7", "ocr", allowCorrection = true).single()
        assertEquals("1Z5R89390357567127", fixed.tracking); assertTrue(fixed.corrected)
        // "1Z" read as "IZ".
        assertEquals("1Z879E930346834440", UpsNumber.find("IZ 879 E93 03 4683 444 0", "ocr", allowCorrection = true).single().tracking)
        assertTrue(UpsNumber.find("1Z 5R8 939 O3 5756 712 7", "ocr").isEmpty())
    }

    @Test fun noUpsNumberInventedFromAUspsNumber() {
        // A real Parcel Select label: OCR of its USPS number once produced three "corrected" 1Z numbers.
        for (t in listOf(
            "9361 2896 8407 0561 8594 81", "9361 2896 8407 0561 5 94 8", "USPS TRACKING # eVS\n9361 2896 8407 0561 8594 81",
            "93612896840705618594 81", "XEW5 BOS5 1/817 9361 2896 8407 0561", "1 2896 8407 0561 8594 81 9693", "9361 2896 8407 0561 1 2 6 6 9 3 6 1 2 8 9 6 8 4 0 7 0 5",
        )) assertTrue(t, UpsNumber.find(t, "ocr", allowCorrection = true).isEmpty())
    }

    @Test fun maxiCodeWithTheFullNumber() {
        val payload = "[)>\u001E01\u001D96841706672\u001D840\u001D003\u001D1Z5R89390357567127\u001DUPSN\u001D\u001E\u0004"
        assertEquals(listOf("1Z5R89390357567127"), UpsNumber.fromMaxiCode(payload).map { it.tracking })
    }
}
