package com.labelrescue.core.candidates

import com.labelrescue.core.usps.TrackingFormat
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

data class Candidate(
    val number: String,
    val format: TrackingFormat,
    /** Sum of log probabilities of each character from the evidence. */
    val evidenceScore: Double,
    /** Structure + learned history prior (log). */
    val priorScore: Double,
    /** Share of the total probability of all check-digit-valid numbers, 0..1. */
    val confidence: Double,
    /** Positions that were not certain from the evidence (shown highlighted). */
    val guessed: Set<Int>,
) {
    val score: Double get() = evidenceScore + priorScore
    /** The tracking part (without any 420 + ZIP routing prefix). */
    val tracking: String get() = number.substring(format.routingLength)
}

data class RankResult(
    val format: TrackingFormat,
    val candidates: List<Candidate>,
    /** How many check-digit-valid numbers exist for this evidence (may be huge). */
    val totalValid: Double,
    /** Total combinations before the check-digit filter. */
    val totalCombinations: Double,
) {
    val best: Candidate? get() = candidates.firstOrNull()
    /** True if every valid number is in [candidates]. */
    val complete: Boolean get() = candidates.size.toDouble() >= totalValid
}

/**
 * Finds the most likely check-digit-valid numbers for per-position evidence.
 *
 * This is an exact k-best search over (position, check-sum state): it returns the same numbers as
 * enumerating every combination of the unknown positions and keeping the valid ones, ranked by
 * evidence, but without listing 10^n combinations. Learned and structural priors then re-rank the
 * list, and prior "suggestions" (known Mailer IDs, service codes, ZIPs) are searched explicitly so
 * a likely number can't fall outside the list.
 */
class CandidateEngine(
    private val beam: Int = 2000,
    private val maxResults: Int = 200,
    /** Options below this fraction of a position's best are dropped (0 keeps every non-zero option). */
    private val pruneRatio: Double = 1e-6,
) {

    /**
     * @param exactEvidence optional exact log-likelihood of a whole number (e.g. the barcode's
     *   joint likelihood under its checksum) added to the per-position score of each listed candidate.
     */
    fun rank(
        format: TrackingFormat,
        slots: List<SlotDist>,
        prior: RankingPrior = RankingPrior.NONE,
        exactEvidence: ((String) -> Double)? = null,
    ): RankResult {
        require(slots.size == format.length) { "expected ${format.length} positions, got ${slots.size}" }
        val model = CheckModel(format)
        val main = search(model, slots, beam)

        val found = LinkedHashMap<String, Double>()
        for ((num, sc) in main.results) found[num] = sc

        // Explicitly try prior suggestions (each fixes some positions) that are compatible with the evidence.
        for (s in prior.suggestions(format).distinct()) {
            if (s.keys.any { it !in slots.indices }) continue
            if (s.all { (i, c) -> slots[i].p(c) >= slots[i].bestP * 0.999 }) continue // already covered by the main search
            if (s.any { (i, c) -> slots[i].p(c) <= 0.0 }) continue
            val fixed = slots.mapIndexed { i, sd -> s[i]?.let { c -> SlotDist.known(c, sd.alphabet) } ?: sd }
            val sub = search(model, fixed, 20)
            for ((num, _) in sub.results) {
                if (num !in found) found[num] = evidenceScore(num, slots)
            }
        }

        val guessedPositions = slots.indices.filter { !slots[it].isCertain }.toSet()
        val scored = found.map { (num, ev) ->
            val pr = prior.logPrior(num, format) + (exactEvidence?.invoke(num) ?: 0.0)
            Triple(num, ev, pr)
        }.filter { it.third > Double.NEGATIVE_INFINITY }.sortedByDescending { it.second + it.third }

        // Confidence: normalize over the listed numbers plus the evidence mass of everything not listed.
        val top = scored.firstOrNull()?.let { it.second + it.third } ?: 0.0
        var listedEvidenceMass = 0.0
        var listedMass = 0.0
        for ((_, ev, pr) in scored) {
            listedMass += exp(ev + pr - top)
            listedEvidenceMass += exp(ev - top)
        }
        val restMass = max(0.0, exp(main.logZ - top) - listedEvidenceMass)
        val denom = listedMass + restMass

        val candidates = scored.take(maxResults).map { (num, ev, pr) ->
            Candidate(num, format, ev, pr, if (denom > 0) exp(ev + pr - top) / denom else 0.0, guessedPositions)
        }
        return RankResult(format, candidates, main.validCount, main.combinations)
    }

    fun evidenceScore(number: String, slots: List<SlotDist>): Double =
        number.indices.sumOf { ln(max(slots[it].p(number[it]), 1e-300)) }

    private class SearchResult(val results: List<Pair<String, Double>>, val logZ: Double, val validCount: Double, val combinations: Double)

    private class Entries(capacity: Int) {
        var size = 0
        val score = DoubleArray(capacity)
        val prevState = IntArray(capacity)
        val prevRank = IntArray(capacity)
        val char = CharArray(capacity)
    }

    private fun search(model: CheckModel, slots: List<SlotDist>, k: Int): SearchResult {
        val n = slots.size
        val states = model.stateCount
        val options = slots.map { sd ->
            val best = sd.bestP
            sd.alphabet.indices.filter { sd.probs[it] > 0 && sd.probs[it] >= best * pruneRatio }
                .map { sd.alphabet[it] to ln(sd.probs[it]) }
        }
        val combinations = options.fold(1.0) { acc, o -> acc * o.size }

        // Forward sums: total probability and number of paths per state.
        var logZ = DoubleArray(states) { Double.NEGATIVE_INFINITY }.also { it[model.initial] = 0.0 }
        var count = DoubleArray(states).also { it[model.initial] = 1.0 }

        val layers = ArrayList<Array<Entries?>>(n)
        var prev: Array<Entries?> = arrayOfNulls(states)
        prev[model.initial] = Entries(1).apply { size = 1; score[0] = 0.0; prevState[0] = -1; prevRank[0] = -1 }

        for (pos in 0 until n) {
            val nextZ = DoubleArray(states) { Double.NEGATIVE_INFINITY }
            val nextCount = DoubleArray(states)
            // Sources per target state: (prev state, char, logp).
            val sources = Array(states) { ArrayList<Triple<Int, Char, Double>>() }
            for (s in 0 until states) {
                val pe = prev[s] ?: continue
                if (pe.size == 0) continue
                for ((c, lp) in options[pos]) {
                    val ns = model.step(s, pos, c)
                    if (ns < 0) continue
                    sources[ns].add(Triple(s, c, lp))
                    nextZ[ns] = logAdd(nextZ[ns], logZ[s] + lp)
                    nextCount[ns] += count[s]
                }
            }
            val cur = arrayOfNulls<Entries>(states)
            for (ns in 0 until states) {
                val src = sources[ns]
                if (src.isEmpty()) continue
                val e = Entries(k)
                // k-way merge of sorted source lists.
                val pq = PriorityQueue<IntArray>(compareByDescending<IntArray> { prev[src[it[0]].first]!!.score[it[1]] + src[it[0]].third })
                for (si in src.indices) pq.add(intArrayOf(si, 0))
                while (e.size < k && pq.isNotEmpty()) {
                    val (si, r) = pq.poll().let { it[0] to it[1] }
                    val (ps, c, lp) = src[si]
                    val pl = prev[ps]!!
                    e.score[e.size] = pl.score[r] + lp
                    e.prevState[e.size] = ps
                    e.prevRank[e.size] = r
                    e.char[e.size] = c
                    e.size++
                    if (r + 1 < pl.size) pq.add(intArrayOf(si, r + 1))
                }
                cur[ns] = e
            }
            layers.add(cur)
            prev = cur
            logZ = nextZ
            count = nextCount
        }

        val results = ArrayList<Pair<String, Double>>()
        val acc = model.accept
        val final = prev[acc]
        if (final != null) {
            for (r in 0 until final.size) {
                val chars = CharArray(n)
                var s = acc
                var rank = r
                for (pos in n - 1 downTo 0) {
                    val e = layers[pos][s]!!
                    chars[pos] = e.char[rank]
                    val ps = e.prevState[rank]
                    rank = e.prevRank[rank]
                    s = ps
                }
                results.add(String(chars) to final.score[r])
            }
        }
        return SearchResult(results, logZ[acc], count[acc], combinations)
    }

    private fun logAdd(a: Double, b: Double): Double {
        if (a == Double.NEGATIVE_INFINITY) return b
        if (b == Double.NEGATIVE_INFINITY) return a
        val m = max(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }
}
