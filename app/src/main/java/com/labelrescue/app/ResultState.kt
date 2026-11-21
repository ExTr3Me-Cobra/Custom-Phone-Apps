package com.labelrescue.app

import com.labelrescue.core.candidates.Candidate
import com.labelrescue.core.candidates.RankResult
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.usps.TrackingFormat

/** A set of candidates being reviewed: from a photo or from manual entry. */
data class ResultState(
    val format: TrackingFormat,
    /** Evidence per position before the user picked anything. */
    val baseSlots: List<SlotDist>,
    /** Digits the user tapped to set. */
    val overrides: Map<Int, Char> = emptyMap(),
    val ranked: RankResult? = null,
    val selected: Int = 0,
    val ranking: Boolean = false,
    /** Photo evidence (crops, sources). Null for manual entry. */
    val photo: PhotoEvidence? = null,
) {
    val slots: List<SlotDist>
        get() = baseSlots.mapIndexed { i, s -> overrides[i]?.let { s.fixedTo(it) } ?: s }

    val current: Candidate? get() = ranked?.candidates?.getOrNull(selected) ?: ranked?.best

    /**
     * How sure each position is over check-digit-valid numbers only: a cleanly read digit in a
     * number whose check digit doesn't work out is not "sure". Computed once per state.
     */
    private val marginals: List<SlotDist>? by lazy {
        if (ranked == null) null else runCatching { com.labelrescue.core.candidates.CandidateEngine().marginals(format, slots) }.getOrNull()
    }

    /** Probability that position [i] is [c] given everything, or null before ranking. */
    fun marginal(i: Int, c: Char): Double? = marginals?.getOrNull(i)?.p(c)
}
