package com.labelrescue.core.verify

import com.labelrescue.core.candidates.RankingPrior
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.ln

/**
 * Online results as a ranking prior: a CONFIRMED number goes to the top, a real but different
 * package (EXISTS_MISMATCH) goes far down, NOT_FOUND goes down (a brand-new label may not be in
 * the system yet), UNKNOWN changes nothing.
 */
class VerificationPrior(private val results: Map<String, VerifyStatus>) : RankingPrior {
    override fun logPrior(number: String, format: TrackingFormat): Double =
        when (results[number.substring(format.routingLength)]) {
            VerifyStatus.CONFIRMED -> ln(1e8)
            VerifyStatus.EXISTS_MISMATCH -> ln(1e-6)
            VerifyStatus.NOT_FOUND -> ln(1e-2)
            VerifyStatus.UNKNOWN, null -> 0.0
        }
}
