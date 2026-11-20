package com.labelrescue.app

import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.RankingPrior
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.usps.TrackingFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds one set of candidates and re-ranks it whenever the user taps a digit. */
class ResultController(
    private val scope: CoroutineScope,
    private val prior: () -> RankingPrior,
    /** Exact evidence score for whole numbers (barcode joint likelihood), if any. */
    private val exactEvidence: () -> ((String) -> Double)? = { null },
) {
    private val engine = CandidateEngine()
    private val _state = MutableStateFlow<ResultState?>(null)
    val state: StateFlow<ResultState?> = _state
    private var job: Job? = null

    fun set(format: TrackingFormat, slots: List<SlotDist>, extra: ResultState.() -> ResultState = { this }) {
        _state.value = ResultState(format, slots).extra()
        rerank()
    }

    fun update(f: (ResultState) -> ResultState) {
        _state.value = _state.value?.let(f)
    }

    fun clear() { job?.cancel(); _state.value = null }

    fun pick(position: Int, c: Char) {
        val s = _state.value ?: return
        _state.value = s.copy(overrides = s.overrides + (position to c), selected = 0)
        rerank()
    }

    fun unpick(position: Int) {
        val s = _state.value ?: return
        _state.value = s.copy(overrides = s.overrides - position, selected = 0)
        rerank()
    }

    fun select(index: Int) {
        _state.value = _state.value?.copy(selected = index)
    }

    fun rerank() {
        val s = _state.value ?: return
        job?.cancel()
        _state.value = s.copy(ranking = true)
        job = scope.launch {
            val ranked = withContext(Dispatchers.Default) {
                engine.rank(s.format, s.slots, prior(), exactEvidence())
            }
            val cur = _state.value
            if (cur != null && cur.overrides == s.overrides && cur.baseSlots === s.baseSlots) {
                _state.value = cur.copy(ranked = ranked, ranking = false, selected = 0)
            }
        }
    }
}
