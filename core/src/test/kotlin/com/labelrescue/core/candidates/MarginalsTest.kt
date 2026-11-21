package com.labelrescue.core.candidates

import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Test
import org.junit.Assert.assertTrue

class MarginalsTest {
    private val engine = CandidateEngine()

    private fun slots(n: String) = n.map { SlotDist.likely(it, 0.98) }

    @Test
    fun validNumberStaysSure() {
        val n = "9234690275976500211709"
        assertTrue(CheckDigits.isValidMod10(n))
        val m = engine.marginals(TrackingFormat.IMPB_22, slots(n))!!
        assertTrue(m.toString(), n.indices.all { m[it].p(n[it]) > 0.97 })
    }

    @Test
    fun failedCheckDigitMakesDigitsUnsure() {
        val n = "9234690275976500211708" // check digit off by one
        val m = engine.marginals(TrackingFormat.IMPB_22, slots(n))!!
        assertTrue(m.toString(), n.indices.any { m[it].p(n[it]) < 0.97 })
    }
}
