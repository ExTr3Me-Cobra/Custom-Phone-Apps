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
}
