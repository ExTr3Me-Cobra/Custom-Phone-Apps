package com.labelrescue.core.verify

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Local cache of lookups, so a number is never queried twice. Failures are not cached. */
interface VerificationCache {
    fun get(trackingNumber: String): LookupOutcome?
    fun put(trackingNumber: String, outcome: LookupOutcome)

    class Memory : VerificationCache {
        val map = LinkedHashMap<String, LookupOutcome>()
        override fun get(trackingNumber: String) = map[trackingNumber]
        override fun put(trackingNumber: String, outcome: LookupOutcome) { map[trackingNumber] = outcome }
    }
}

/**
 * Token bucket: at most [perHour] requests per rolling hour, with up to [burst] at once.
 * USPS publishes no number for the default quota; the app's default is conservative and the
 * engine also backs off on 429.
 */
class Throttle(
    private val perHour: Int,
    private val burst: Int,
    private val clock: () -> Long,
) {
    private val intervalMs = 3_600_000.0 / perHour
    private var tokens = burst.toDouble()
    private var last = clock()

    @Synchronized
    fun waitMs(): Long {
        refill()
        return if (tokens >= 1) 0 else ((1 - tokens) * intervalMs).toLong() + 1
    }

    @Synchronized
    fun take() { refill(); tokens -= 1 }

    private fun refill() {
        val now = clock()
        tokens = min(burst.toDouble(), tokens + (now - last) / intervalMs)
        last = now
    }
}

data class VerifiedCandidate(val trackingNumber: String, val verdict: Verdict, val fromCache: Boolean)

data class VerificationReport(
    val results: List<VerifiedCandidate>,
    /** API calls made (including retries). */
    val apiCalls: Int,
    val confirmed: String?,
    /** Why the loop ended. */
    val stopReason: String,
)

/**
 * Checks candidates in rank order, best first. Throttles to the rate limit, retries 429/5xx and
 * network errors with exponential backoff (honoring Retry-After), caches every definite answer,
 * and stops as soon as one candidate is CONFIRMED.
 */
class VerificationEngine(
    private val api: TrackingApi,
    private val cache: VerificationCache,
    private val throttle: Throttle,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val random: Random = Random.Default,
    private val maxRetries: Int = 4,
    private val baseBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 64_000,
) {
    class Cancelled : RuntimeException()

    fun run(
        candidates: List<String>,
        context: LabelContext,
        maxLookups: Int = 10,
        cancelled: () -> Boolean = { false },
        onResult: (VerifiedCandidate) -> Unit = {},
    ): VerificationReport {
        val out = ArrayList<VerifiedCandidate>()
        var calls = 0
        var lookups = 0
        try {
        for (n in candidates.distinct()) {
            if (cancelled()) return VerificationReport(out, calls, null, "Cancelled")
            val cached = cache.get(n)
            val vc: VerifiedCandidate
            if (cached != null) {
                vc = VerifiedCandidate(n, MatchScorer.score(cached, context), fromCache = true)
            } else {
                if (lookups >= maxLookups) return VerificationReport(out, calls, null, "Checked $lookups numbers (limit)")
                lookups++
                var attempt = 0
                var verdict: Verdict
                while (true) {
                    pause(throttle.waitMs(), cancelled)
                    throttle.take()
                    calls++
                    when (val r = api.lookup(n)) {
                        is ApiResult.Done -> {
                            cache.put(n, r.outcome)
                            verdict = MatchScorer.score(r.outcome, context)
                            break
                        }
                        is ApiResult.Fatal -> {
                            val v = Verdict(VerifyStatus.UNKNOWN, note = r.reason)
                            val item = VerifiedCandidate(n, v, false)
                            out += item; onResult(item)
                            return VerificationReport(out, calls, null, r.reason)
                        }
                        is ApiResult.Retryable -> {
                            if (attempt >= maxRetries) { verdict = Verdict(VerifyStatus.UNKNOWN, note = r.reason); break }
                            pause(backoffMs(attempt, r.retryAfterMs), cancelled)
                            attempt++
                        }
                    }
                }
                vc = VerifiedCandidate(n, verdict, fromCache = false)
            }
            out += vc
            onResult(vc)
            if (vc.verdict.status == VerifyStatus.CONFIRMED) return VerificationReport(out, calls, n, "Confirmed match found")
        }
        } catch (e: Cancelled) {
            return VerificationReport(out, calls, null, "Cancelled")
        }
        return VerificationReport(out, calls, null, "All candidates checked")
    }

    /** Exponential backoff with jitter: base·2^attempt, between 50% and 100%, capped; at least Retry-After. */
    fun backoffMs(attempt: Int, retryAfterMs: Long?): Long {
        val exp = min(maxBackoffMs, baseBackoffMs shl min(attempt, 20))
        val jittered = (exp * (0.5 + 0.5 * random.nextDouble())).toLong()
        return max(jittered, retryAfterMs ?: 0)
    }

    private fun pause(ms: Long, cancelled: () -> Boolean) {
        var left = ms
        while (left > 0) {
            if (cancelled()) throw Cancelled()
            val step = min(left, 500)
            sleep(step)
            left -= step
        }
    }
}
