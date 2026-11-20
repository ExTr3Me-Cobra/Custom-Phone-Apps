package com.labelrescue.core.verify

import com.labelrescue.core.candidates.RankingPrior
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.ln

/**
 * USPS numbers learned from elsewhere (e.g. UPS's tracking for a package it handed to USPS):
 * searched explicitly and ranked first when the label's evidence allows them at all.
 */
class KnownNumberPrior(private val trackings: Set<String>) : RankingPrior {
    override fun logPrior(number: String, format: TrackingFormat): Double =
        if (number.substring(format.routingLength) in trackings) ln(1e6) else 0.0

    override fun suggestions(format: TrackingFormat): List<Map<Int, Char>> =
        trackings.filter { it.length == format.trackingLength }.map { t -> t.withIndex().associate { (i, c) -> format.routingLength + i to c } }
}
