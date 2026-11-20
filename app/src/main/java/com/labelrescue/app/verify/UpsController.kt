package com.labelrescue.app.verify

import com.labelrescue.app.data.Settings
import com.labelrescue.core.ups.UpsNumber
import com.labelrescue.core.verify.Throttle
import com.labelrescue.core.verify.UpsLookup
import com.labelrescue.core.verify.UpsTrackingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UPS → USPS: for a 1Z number read off the label, asks UPS's Tracking API (official, with the
 * user's own keys) which USPS number the package was handed to. Runs automatically after a scan
 * when enabled; a found USPS number ranks first among the label's candidates.
 */
class UpsController(
    private val scope: CoroutineScope,
    private val settings: suspend () -> Settings,
    /** Called when a USPS number is found, so the candidates re-rank. */
    private val onLinked: () -> Unit,
) {
    sealed class Status {
        data object Waiting : Status()
        data object Looking : Status()
        /** UPS knows the package and named USPS number(s) for it. */
        data class Linked(val usps: List<String>, val upsStatus: String?) : Status()
        /** UPS knows the package but lists no USPS number (yet). */
        data class NoLink(val upsStatus: String?) : Status()
        data class NotFound(val message: String?) : Status()
        data class Failed(val message: String) : Status()
        /** One of several numbers rebuilt from a MaxiCode; another one was the real one. */
        data object Skipped : Status()
    }

    data class Item(val ups: UpsNumber, val status: Status)
    data class UiState(val items: List<Item> = emptyList(), val enabled: Boolean = false, val hasCredentials: Boolean = false, val running: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    /** Session cache: never ask UPS twice about the same number (except after a failure). */
    private val cache = HashMap<String, Status>()
    private var throttle: Throttle? = null

    /** USPS numbers UPS tied to the numbers on the current label. */
    fun linkedUsps(): Set<String> = _state.value.items.flatMap { (it.status as? Status.Linked)?.usps.orEmpty() }.toSet()

    fun clear() { job?.cancel(); _state.value = UiState() }

    fun start(numbers: List<UpsNumber>) {
        job?.cancel()
        // Exact reads first; numbers rebuilt from a short MaxiCode form are a fallback group.
        val list = numbers.sortedBy { it.corrected }.take(12)
        job = scope.launch {
            val st = settings()
            val enabled = st.upsEnabled
            val creds = st.hasUpsCredentials
            _state.value = UiState(list.map { Item(it, cache[it.tracking] ?: Status.Waiting) }, enabled, creds)
            if (list.isEmpty() || !enabled || !creds) return@launch
            _state.value = _state.value.copy(running = true)
            val client = UpsTrackingClient(credentials = { st.upsCredentials() }, store = st.upsTokenStore)
            val th = throttle ?: Throttle(MAX_PER_HOUR, burst = 5, clock = System::currentTimeMillis).also { throttle = it }
            var anyLinked = list.any { cache[it.tracking] is Status.Linked }
            var rebuiltFound = false
            for (u in list) {
                if (!isActive) break
                val cached = cache[u.tracking]
                if (cached != null && cached !is Status.Failed) continue
                if (u.source.contains("rebuilt") && rebuiltFound) { set(u, Status.Skipped); continue }
                set(u, Status.Looking)
                var result: UpsLookup = UpsLookup.Retryable("not tried")
                for (attempt in 0 until 3) {
                    val wait = th.waitMs()
                    if (wait > 0) delay(wait)
                    th.take()
                    result = withContext(Dispatchers.IO) { client.lookup(u.tracking) }
                    if (result !is UpsLookup.Retryable) break
                    delay(result.retryAfterMs ?: (2000L shl attempt))
                }
                val s = when (val r = result) {
                    is UpsLookup.Found -> if (r.usps.isNotEmpty()) Status.Linked(r.usps.map { it.tracking }, r.status) else Status.NoLink(r.status)
                    is UpsLookup.NotFound -> Status.NotFound(r.message)
                    is UpsLookup.Retryable -> Status.Failed(r.reason)
                    is UpsLookup.Fatal -> Status.Failed(r.reason)
                }
                if (s !is Status.Failed) cache[u.tracking] = s
                set(u, s)
                if (s is Status.Linked || s is Status.NoLink) { if (u.source.contains("rebuilt")) rebuiltFound = true }
                if (s is Status.Linked) anyLinked = true
                if (result is UpsLookup.Fatal) break // bad keys: the rest would fail the same way
            }
            _state.value = _state.value.copy(running = false)
            if (anyLinked) onLinked()
        }
    }

    fun retry() { start(_state.value.items.map { it.ups }) }

    private fun set(u: UpsNumber, s: Status) {
        _state.value = _state.value.copy(items = _state.value.items.map { if (it.ups.tracking == u.tracking) it.copy(status = s) else it })
    }

    private companion object {
        /** Conservative: a scan asks about one or two numbers. */
        const val MAX_PER_HOUR = 120
    }
}
