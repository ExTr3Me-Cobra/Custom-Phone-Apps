package com.labelrescue.core.fusion

import com.labelrescue.core.candidates.SlotDist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingCropFilterTest {
    @Test fun keepsOnlyWhenSomeReaderAgrees() {
        val per = mapOf(
            Source.OCR to listOf(SlotDist.likely('5', 0.8), SlotDist.likely('1', 0.9), null),
            Source.BARCODE_PARTIAL to listOf(SlotDist.likely('6', 0.5), SlotDist.likely('7', 0.6), SlotDist.likely('3', 0.4)),
        )
        assertEquals(listOf(Source.OCR), TrainingCropFilter.agreeing(per, 0, '5'))
        // Nobody read a 4 at position 1: likely a misaligned crop or a wrong confirmation.
        assertTrue(TrainingCropFilter.agreeing(per, 1, '4').isEmpty())
        assertEquals(listOf(Source.BARCODE_PARTIAL), TrainingCropFilter.agreeing(per, 2, '3'))
    }
}
