package com.labelrescue.app.verify

import com.labelrescue.app.data.HistoryDb
import com.labelrescue.app.data.RoomVerificationCache
import com.labelrescue.app.data.Settings
import com.labelrescue.core.verify.LabelContext
import com.labelrescue.core.verify.Throttle
import com.labelrescue.core.verify.UspsTrackingClient
import com.labelrescue.core.verify.VerificationEngine
import com.labelrescue.core.verify.VerifiedCandidate
import com.labelrescue.core.verify.VerifyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Automatic online verification of ranked candidates (optional; recovery never needs it).
 * Before the first lookup the user confirms the label context (ZIPs, date), since that is what
 * decides CONFIRMED vs. EXISTS_MISMATCH.
 */
class VerificationController(
    private val scope: CoroutineScope,
    private val db: HistoryDb,
    private val settings: suspend () -> Settings,
    /** Called when results change, so the candidate list re-ranks. */
    private val onResults: () -> Unit,
) {
    data class UiState(
        val results: Map<String, VerifiedCandidate> = emptyMap(),
        val running: Boolean = false,
        val status: String? = null,
        /** Context read from the label, to be confirmed or edited by the user. */
        val context: LabelContext = LabelContext(),
        val contextConfirmed: Boolean = false,
        val askContext: Boolean = false,
        val canVerify: Boolean = false,
        val hasCredentials: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    private var auto = true

    fun statuses(): Map<String, VerifyStatus> = _state.value.results.mapValues { it.value.verdict.status }

    /** A new set of candidates: keep cached verdicts out of the UI until re-checked, suggest context. */
    fun reset(suggested: LabelContext) {
        job?.cancel()
        scope.launch {
            val st = settings()
            auto = st.autoVerify
            _state.value = UiState(
                context = suggested, canVerify = st.canVerify, hasCredentials = st.hasCredentials,
                askContext = st.canVerify && st.autoVerify,
            )
        }
    }

    fun askContext() { _state.value = _state.value.copy(askContext = true) }
    fun dismissContext() { _state.value = _state.value.copy(askContext = false) }

    fun confirmContext(ctx: LabelContext, candidates: List<String>) {
        _state.value = _state.value.copy(context = ctx, contextConfirmed = true, askContext = false, results = emptyMap())
        start(candidates)
    }

    /** After a re-rank: verify new top candidates automatically if the context was confirmed. */
    fun onRanked(candidates: List<String>) {
        val s = _state.value
        if (!auto || !s.canVerify || !s.contextConfirmed || s.running) return
        if (s.results.values.any { it.verdict.status == VerifyStatus.CONFIRMED }) return
        if (candidates.take(MAX_LOOKUPS).all { it in s.results }) return
        start(candidates)
    }

    fun cancel() { job?.cancel(); _state.value = _state.value.copy(running = false, status = "Stopped") }

    private fun start(candidates: List<String>) {
        job?.cancel()
        val ctx = _state.value.context
        job = scope.launch {
            val st = settings()
            if (!st.canVerify) {
                _state.value = _state.value.copy(status = "Turn on USPS verification and add your Consumer Key and Secret in Settings.")
                return@launch
            }
            _state.value = _state.value.copy(running = true, status = "Checking with USPS…")
            val throttle = throttleFor(st.requestsPerHour)
            val report = withContext(Dispatchers.IO) {
                val client = UspsTrackingClient(credentials = { st.credentials() }, store = st.tokenStore)
                val engine = VerificationEngine(
                    client, RoomVerificationCache(db.verification()), throttle,
                    sleep = { ms ->
                        if (ms >= 2000) _state.value = _state.value.copy(status = "Waiting ${(ms + 999) / 1000}s (USPS rate limit)…")
                        Thread.sleep(ms)
                    },
                )
                engine.run(candidates.take(MAX_LOOKUPS), ctx, MAX_LOOKUPS, cancelled = { !isActive }) { vc ->
                    _state.value = _state.value.copy(
                        results = _state.value.results + (vc.trackingNumber to vc),
                        status = "Checked ${_state.value.results.size + 1}…",
                    )
                    scope.launch { onResults() }
                }
            }
            _state.value = _state.value.copy(
                running = false,
                status = report.confirmed?.let { "Confirmed by USPS: $it" } ?: report.stopReason,
            )
            onResults()
        }
    }

    companion object {
        /** At most this many numbers per run (each uncached one is one API call). */
        const val MAX_LOOKUPS = 20

        // One throttle per process so separate runs share the hourly budget.
        private var throttle: Throttle? = null
        private var throttleRate = 0

        @Synchronized
        fun throttleFor(perHour: Int): Throttle {
            if (throttle == null || throttleRate != perHour) {
                throttle = Throttle(perHour, burst = minOf(5, perHour), clock = System::currentTimeMillis)
                throttleRate = perHour
            }
            return throttle!!
        }

        /** The official USPS tracking page with several numbers (comma-separated tLabels). */
        fun uspsComUrl(numbers: List<String>): String =
            "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + numbers.take(10).joinToString(",")
    }
}
