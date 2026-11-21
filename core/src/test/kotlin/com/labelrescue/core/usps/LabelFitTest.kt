package com.labelrescue.core.usps

import com.labelrescue.core.candidates.SlotDist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelFitTest {
    private val truth = "9234690275075500211709"

    @Test fun findsGroupedNumbersInCopiedText() {
        assertEquals(truth, LabelFit.findUsps("USPS Tracking #: 9234 6902 7507 5500 2117 09 Delivered")?.tracking)
        assertEquals(truth, LabelFit.findUsps(truth)?.tracking)
        assertNull("bad check digit", LabelFit.findUsps("9234 6902 7507 5500 2117 08"))
        assertNull("unrelated text", LabelFit.findUsps("1Z5R89390357567127 shipped from NJ"))
    }

    @Test fun fitsWhenNoClearDigitDisagrees() {
        // Label: two digits unreadable, the rest clear.
        val slots = truth.mapIndexed { i, c -> if (i == 4 || i == 11) SlotDist.uniform() else SlotDist.known(c) }
        val ok = LabelFit.check(LabelFit.findUsps(truth)!!, TrackingFormat.IMPB_22, slots)
        assertTrue(ok.fits)
        // A different (valid) number that clashes with clearly read digits.
        val other = "9361289684070561859481"
        val bad = LabelFit.check(LabelFit.findUsps(other)!!, TrackingFormat.IMPB_22, slots)
        assertFalse(bad.fits)
        assertTrue(bad.conflicts.size > 5)
        // A 26-digit number against a 22-digit label.
        val wrongFormat = LabelFit.check(LabelFit.findUsps("92612903102478541514848167")!!, TrackingFormat.IMPB_22, slots)
        assertFalse(wrongFormat.sameFormat)
    }
}
