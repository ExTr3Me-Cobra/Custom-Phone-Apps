package com.labelrescue.core.candidates

import com.labelrescue.core.usps.ImpbParser
import com.labelrescue.core.usps.ServiceHints
import com.labelrescue.core.usps.ServiceTypeCodes
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.ln

/**
 * Extra knowledge used when ranking check-digit-valid candidates. Neutral is 0; boosts are
 * positive, unlikely structure is negative. Priors never remove a candidate.
 */
interface RankingPrior {
    fun logPrior(number: String, format: TrackingFormat): Double

    /** Partial assignments (position → char) worth trying even if the digit evidence ranks them low. */
    fun suggestions(format: TrackingFormat): List<Map<Int, Char>> = emptyList()

    companion object {
        val NONE = object : RankingPrior {
            override fun logPrior(number: String, format: TrackingFormat) = 0.0
        }
    }
}

class CompositePrior(private val parts: List<RankingPrior>) : RankingPrior {
    override fun logPrior(number: String, format: TrackingFormat) = parts.sumOf { it.logPrior(number, format) }
    override fun suggestions(format: TrackingFormat) = parts.flatMap { it.suggestions(format) }
}

/** USPS structure plus what the printed label says (service name, ZIP). */
class StructurePrior(
    private val hints: List<ServiceHints.Hint> = emptyList(),
    private val zips: List<String> = emptyList(),
) : RankingPrior {

    override fun logPrior(number: String, format: TrackingFormat): Double {
        var lp = 0.0
        if (format.isRouted) {
            if (!number.startsWith("420")) lp += ln(0.01)
            val zip = number.substring(3, format.routingLength)
            if (zips.any { it == zip || (zip.length == 5 && it.startsWith(zip)) || (zip.length == 9 && zip.startsWith(it)) }) lp += ln(5.0)
        }
        val t = number.substring(format.routingLength)
        val tf = format.trackingFormat
        if (tf == TrackingFormat.IMPB_22 || tf == TrackingFormat.IMPB_26) {
            val channel = t.substring(0, 2)
            if (channel !in ImpbParser.CHANNELS) lp += ln(0.02)
            val stc = t.substring(2, 5)
            // Publication 199: only listed service type codes are valid (346 of 1000), a channel-92
            // Mailer ID has nine digits starting with 9, a channel-93 one six (so not starting with 9).
            if (!ServiceTypeCodes.isValid(stc)) lp += ln(0.08)
            if (channel == "92" && t[5] != '9') lp += ln(0.03)
            if (channel == "93" && t[5] == '9') lp += ln(0.1)
            if (hints.isNotEmpty()) {
                when {
                    hints.any { stc in it.serviceTypeCodes } -> lp += ln(8.0)
                    hints.any { h -> h.stcPrefixes.any { stc.startsWith(it) } } -> lp += ln(2.0)
                }
            }
        }
        if (tf == TrackingFormat.S10 && t.substring(11) != "US") lp += ln(0.05)
        return lp
    }

    override fun suggestions(format: TrackingFormat): List<Map<Int, Char>> {
        val out = mutableListOf<Map<Int, Char>>()
        val off = format.routingLength
        val tf = format.trackingFormat
        if (tf == TrackingFormat.IMPB_22 || tf == TrackingFormat.IMPB_26) {
            for (h in hints) for (stc in h.serviceTypeCodes) out += stc.withIndex().associate { (i, c) -> off + 2 + i to c }
        }
        if (format.isRouted) {
            for (z in zips) if (z.length == format.routingLength - 3) out += ("420$z").withIndex().associate { (i, c) -> i to c }
        }
        return out
    }
}

/** Counts learned from numbers the user confirmed. */
data class HistoryStats(
    val total: Int,
    val mailerIds: Map<String, Int>,
    val serviceTypes: Map<String, Int>,
    val channels: Map<String, Int>,
    /** Where each Mailer ID sits in the tracking number (5, or 7 after a channel-94 source ID). */
    val mailerIdStarts: Map<String, Int> = emptyMap(),
) {
    companion object {
        val EMPTY = HistoryStats(0, emptyMap(), emptyMap(), emptyMap())

        fun from(confirmed: Iterable<String>): HistoryStats {
            val mids = HashMap<String, Int>(); val stcs = HashMap<String, Int>(); val ch = HashMap<String, Int>()
            val starts = HashMap<String, Int>()
            var n = 0
            for (num in confirmed) {
                val p = ImpbParser.parse(num) ?: continue
                if (!p.isImpb) continue
                n++
                p.mailerId?.let { mids.merge(it, 1, Int::plus); starts[it] = p.mailerIdStart }
                p.serviceType?.let { stcs.merge(it, 1, Int::plus) }
                p.channel?.let { ch.merge(it, 1, Int::plus) }
            }
            return HistoryStats(n, mids, stcs, ch, starts)
        }
    }
}

/**
 * Learned prior: Mailer IDs and service codes the user has confirmed before are more likely.
 * Boosts are bounded so history can break ties and correct weak digits, but can't override
 * strong evidence.
 */
class HistoryPrior(private val stats: HistoryStats, private val maxSuggestions: Int = 40) : RankingPrior {

    override fun logPrior(number: String, format: TrackingFormat): Double {
        if (stats.total == 0) return 0.0
        val tf = format.trackingFormat
        if (tf != TrackingFormat.IMPB_22 && tf != TrackingFormat.IMPB_26) return 0.0
        val t = number.substring(format.routingLength)
        val p = ImpbParser.parse(t) ?: return 0.0
        var lp = 0.0
        p.mailerId?.let { lp += boost(stats.mailerIds[it] ?: 0, 40.0) }
        p.serviceType?.let { lp += boost(stats.serviceTypes[it] ?: 0, 8.0) }
        p.channel?.let { lp += boost(stats.channels[it] ?: 0, 2.0) }
        return lp
    }

    private fun boost(count: Int, k: Double) = if (count <= 0) 0.0 else ln(1 + k * count / (count + 1.0))

    override fun suggestions(format: TrackingFormat): List<Map<Int, Char>> {
        val tf = format.trackingFormat
        if (tf != TrackingFormat.IMPB_22 && tf != TrackingFormat.IMPB_26) return emptyList()
        val off = format.routingLength
        val out = mutableListOf<Map<Int, Char>>()
        for ((mid, _) in stats.mailerIds.entries.sortedByDescending { it.value }.take(maxSuggestions)) {
            val start = stats.mailerIdStarts[mid] ?: 5
            out += mid.withIndex().associate { (i, c) -> off + start + i to c }
        }
        for ((stc, _) in stats.serviceTypes.entries.sortedByDescending { it.value }.take(maxSuggestions)) {
            out += stc.withIndex().associate { (i, c) -> off + 2 + i to c }
        }
        return out
    }
}
