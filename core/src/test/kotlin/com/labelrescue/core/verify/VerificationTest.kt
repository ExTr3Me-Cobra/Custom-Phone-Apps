package com.labelrescue.core.verify

import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.CompositePrior
import com.labelrescue.core.candidates.ManualEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** A scripted USPS: answers per tracking number, records every request. */
class FakeUsps(var now: () -> Long = { 0L }) : HttpTransport {
    val requests = ArrayList<Pair<String, String>>()
    val tracking = HashMap<String, MutableList<HttpResponse>>()
    var tokenResponse = HttpResponse(200, """{"access_token":"tok1","token_type":"Bearer","expires_in":"28800","status":"approved"}""")
    var v3r2Post = true

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        requests += method to url
        if (url.endsWith("/oauth2/v3/token")) return tokenResponse
        val n = if (url.contains("/v3r2/tracking") && method == "POST") {
            if (!v3r2Post) return HttpResponse(405, "")
            Regex("\"trackingNumber\":\"([0-9A-Z]+)\"").find(body!!)!!.groupValues[1]
        } else url.substringAfterLast("/").substringBefore("?")
        val q = tracking[n] ?: return HttpResponse(400, notFoundBody)
        return if (q.size > 1) q.removeAt(0) else q.first()
    }

    companion object {
        val notFoundBody = """{"apiVersion":"/tracking/v3r2","error":{"code":"400","message":"Bad Request","errors":[{"status":"400","code":"150002","title":"7: The tracking number may be incorrect or the status update is not yet available. Please verify your tracking number and try again later."}]}}"""

        fun found(n: String, destZip: String = "05641", origZip: String = "08081", cls: String = "Priority Mail<SUP>&reg;</SUP>", date: String = "2026-11-17T09:12:00Z") =
            HttpResponse(200, """[{"trackingNumber":"$n","mailClass":"$cls","mailType":"DM","destinationCity":"BARRE","destinationState":"VT","destinationZIP":"$destZip",
              "originCity":"SEWELL","originState":"NJ","originZIP":"$origZip","status":"In Transit","statusCategory":"In Transit","statusSummary":"Your item is in transit.",
              "trackingEvents":[{"eventType":"Departed USPS Regional Facility","eventTimestamp":"2026-11-18T22:01:00Z","eventCity":"PHILADELPHIA","eventState":"PA","eventZIP":"19176","eventCode":"T1"},
                {"eventType":"Shipping Label Created, USPS Awaiting Item","eventTimestamp":"$date","eventCity":"SEWELL","eventState":"NJ","eventZIP":"08081","eventCode":"GX"}]}]""")
    }
}

class VerificationTest {
    private val n1 = "9234690275075500211709"
    private val n2 = "9234190275070500211709"
    private val ctx = LabelContext("05641", "BARRE", "08081", "SEWELL", "Priority Mail", LocalDate.of(2026, 11, 17))

    private fun client(fake: FakeUsps, clock: () -> Long = { 0L }) =
        UspsTrackingClient({ "key" to "secret" }, fake, TokenStore.Memory(), clock)

    // ---- Match scoring: the four outcomes, from mocked API responses ----

    @Test fun confirmed() {
        val fake = FakeUsps().apply { tracking[n1] = mutableListOf(FakeUsps.found(n1)) }
        val r = client(fake).lookup(n1) as ApiResult.Done
        val v = MatchScorer.score(r.outcome, ctx)
        assertEquals(VerifyStatus.CONFIRMED, v.status)
        assertTrue(v.summary, v.summary.contains("Destination 05641 matches"))
        assertTrue(v.summary.contains("Origin 08081 matches"))
        assertTrue(v.evidence.any { it.field == "Mail class" && it.result == Evidence.Result.MATCH })
        assertTrue(v.evidence.any { it.field == "Label date" && it.result == Evidence.Result.MATCH })
    }

    @Test fun existsMismatch() {
        val fake = FakeUsps().apply { tracking[n1] = mutableListOf(FakeUsps.found(n1, destZip = "90210")) }
        val v = MatchScorer.score((client(fake).lookup(n1) as ApiResult.Done).outcome, ctx)
        assertEquals(VerifyStatus.EXISTS_MISMATCH, v.status)
        assertTrue(v.summary.contains("Destination differs"))
        // Wrong date (months away) alone also means a different package.
        val old = FakeUsps().apply { tracking[n1] = mutableListOf(FakeUsps.found(n1, date = "2026-03-01T10:00:00Z")) }
        assertEquals(VerifyStatus.EXISTS_MISMATCH, MatchScorer.score((client(old).lookup(n1) as ApiResult.Done).outcome, ctx.copy(destinationZip = null)).status)
        // Mail class conflict.
        val cls = FakeUsps().apply { tracking[n1] = mutableListOf(FakeUsps.found(n1, cls = "Media Mail")) }
        assertEquals(VerifyStatus.EXISTS_MISMATCH, MatchScorer.score((client(cls).lookup(n1) as ApiResult.Done).outcome, ctx).status)
    }

    @Test fun notFound() {
        val fake = FakeUsps()
        val r = client(fake).lookup(n1) as ApiResult.Done
        assertTrue(r.outcome is LookupOutcome.NotFound)
        assertEquals("150002", (r.outcome as LookupOutcome.NotFound).code)
        assertEquals(VerifyStatus.NOT_FOUND, MatchScorer.score(r.outcome, ctx).status)
    }

    @Test fun unknown() {
        // Rate limited.
        val limited = FakeUsps().apply { tracking[n1] = mutableListOf(HttpResponse(429, """{"error":{"code":"429","message":"TOO_MANY_REQUESTS"}}""", mapOf("Retry-After" to "30"))) }
        val r = client(limited).lookup(n1)
        assertTrue(r is ApiResult.Retryable)
        assertEquals(30_000L, (r as ApiResult.Retryable).retryAfterMs)
        // No credentials.
        val none = UspsTrackingClient({ null }, FakeUsps())
        assertTrue(none.lookup(n1) is ApiResult.Fatal)
        // Network error.
        val down = UspsTrackingClient({ "k" to "s" }, HttpTransport { _, _, _, _ -> throw java.io.IOException("no route") })
        assertTrue(down.lookup(n1) is ApiResult.Retryable)
        assertEquals(VerifyStatus.UNKNOWN, MatchScorer.score(LookupOutcome.Failed("x"), ctx).status)
        // Exists but no status yet (150001): not proof of anything.
        val early = FakeUsps().apply { tracking[n1] = mutableListOf(HttpResponse(400, """{"error":{"errors":[{"code":"150001","title":"12: A status update is not yet available"}]}}""")) }
        assertEquals(VerifyStatus.UNKNOWN, MatchScorer.score((client(early).lookup(n1) as ApiResult.Done).outcome, ctx).status)
        // Exists but the user gave nothing to compare: existence is not enough.
        val fake = FakeUsps().apply { tracking[n1] = mutableListOf(FakeUsps.found(n1)) }
        assertEquals(VerifyStatus.UNKNOWN, MatchScorer.score((client(fake).lookup(n1) as ApiResult.Done).outcome, LabelContext()).status)
    }

    @Test fun tokenIsCachedUntilExpiry() {
        var t = 0L
        val fake = FakeUsps().apply { tracking[n1] = mutableListOf(FakeUsps.found(n1)); tracking[n2] = mutableListOf(FakeUsps.found(n2)) }
        val c = client(fake) { t }
        c.lookup(n1); c.lookup(n2)
        assertEquals(1, c.tokenRequests)
        t += 28_800_000L // past expires_in
        c.lookup(n1)
        assertEquals(2, c.tokenRequests)
    }

    @Test fun fallsBackToSingleNumberEndpoint() {
        val fake = FakeUsps().apply { v3r2Post = false; tracking[n1] = mutableListOf(FakeUsps.found(n1)) }
        val c = client(fake)
        assertTrue((c.lookup(n1) as ApiResult.Done).outcome is LookupOutcome.Found)
        assertTrue(fake.requests.any { it.first == "GET" && it.second.contains("/tracking/v3r2/tracking/$n1") })
        // The working endpoint is remembered: no second POST attempt.
        fake.requests.clear()
        c.lookup(n1)
        assertTrue(fake.requests.none { it.first == "POST" && it.second.contains("v3r2") })
    }

    // ---- Engine: throttling, backoff, caching, early stopping ----

    private class Clock { var t = 0L; val sleeps = ArrayList<Long>() }

    private fun engine(api: TrackingApi, cache: VerificationCache, clk: Clock, perHour: Int = 60, burst: Int = 2) =
        VerificationEngine(api, cache, Throttle(perHour, burst) { clk.t }, sleep = { clk.sleeps += it; clk.t += it }, random = Random(1))

    @Test fun throttlesToTheRateLimit() {
        val clk = Clock()
        val calls = ArrayList<Long>()
        val api = TrackingApi { calls += clk.t; ApiResult.Done(LookupOutcome.NotFound("150002", null)) }
        val nums = (0 until 5).map { "92000000000000000000%02d".format(it) }
        engine(api, VerificationCache.Memory(), clk, perHour = 60, burst = 2).run(nums, ctx)
        assertEquals(5, calls.size)
        // Two at once (burst), then one per minute.
        assertEquals(0L, calls[0]); assertEquals(0L, calls[1])
        for (i in 2 until 5) assertTrue("call $i at ${calls[i]}", calls[i] - calls[i - 1] >= 59_000)
    }

    @Test fun exponentialBackoffOn429And5xx() {
        val clk = Clock()
        val script = ArrayDeque(listOf<ApiResult>(
            ApiResult.Retryable("429"), ApiResult.Retryable("503"), ApiResult.Retryable("429", retryAfterMs = 40_000),
            ApiResult.Done(LookupOutcome.Found(TrackingFacts.fromMap(Json.parse(FakeUsps.found(n1).body.trim().removePrefix("[").removeSuffix("]")) as Map<*, *>))),
        ))
        val api = TrackingApi { script.removeFirst() }
        val rep = engine(api, VerificationCache.Memory(), clk, perHour = 3600, burst = 10).run(listOf(n1), ctx)
        assertEquals(VerifyStatus.CONFIRMED, rep.results.single().verdict.status)
        assertEquals(4, rep.apiCalls)
        // Waits grow: ~2s, ~4s, then at least Retry-After 40s.
        val waits = clk.sleeps.chunkedBySum()
        assertEquals(3, waits.size)
        assertTrue(waits[0] in 1_000..2_000); assertTrue(waits[1] in 2_000..4_000); assertTrue(waits[2] >= 40_000)
    }

    @Test fun givesUpAfterMaxRetriesAsUnknown() {
        val clk = Clock()
        val api = TrackingApi { ApiResult.Retryable("503") }
        val cache = VerificationCache.Memory()
        val rep = engine(api, cache, clk, perHour = 3600, burst = 10).run(listOf(n1), ctx)
        assertEquals(VerifyStatus.UNKNOWN, rep.results.single().verdict.status)
        assertEquals(5, rep.apiCalls)
        assertNull(cache.get(n1)) // failures are not cached
    }

    @Test fun cachedNumbersAreNeverQueriedAgain() {
        val clk = Clock()
        var calls = 0
        val api = TrackingApi { calls++; ApiResult.Done(LookupOutcome.NotFound("150002", null)) }
        val cache = VerificationCache.Memory()
        val e = engine(api, cache, clk)
        e.run(listOf(n1, n2), ctx)
        assertEquals(2, calls)
        val rep = e.run(listOf(n2, n1), ctx)
        assertEquals(2, calls)
        assertTrue(rep.results.all { it.fromCache })
        assertNotNull(cache.get(n1))
    }

    @Test fun stopsEarlyOnConfirmedMatch() {
        val clk = Clock()
        val fake = FakeUsps().apply { tracking[n2] = mutableListOf(FakeUsps.found(n2)) }
        val queried = ArrayList<String>()
        val c = client(fake)
        val api = TrackingApi { n -> queried += n; c.lookup(n) }
        val cands = listOf("9234090275079500211709", n2, n1, "9234290275071500211709")
        val rep = engine(api, VerificationCache.Memory(), clk, perHour = 3600, burst = 10).run(cands, ctx)
        assertEquals(n2, rep.confirmed)
        assertEquals(listOf(cands[0], n2), queried) // rank order, nothing after the match
        assertEquals(VerifyStatus.NOT_FOUND, rep.results[0].verdict.status)
    }

    @Test fun stopsOnFatalError() {
        val clk = Clock()
        var calls = 0
        val api = TrackingApi { calls++; ApiResult.Fatal("No credentials") }
        val rep = engine(api, VerificationCache.Memory(), clk).run(listOf(n1, n2), ctx)
        assertEquals(1, calls)
        assertEquals(VerifyStatus.UNKNOWN, rep.results.single().verdict.status)
    }

    @Test fun verificationReranks() {
        val p = (ManualEntry.parse("9234?9027507?500211709") as ManualEntry.Result.Ok).parsed
        val prior = CompositePrior(listOf(VerificationPrior(mapOf(n1 to VerifyStatus.CONFIRMED, n2 to VerifyStatus.EXISTS_MISMATCH))))
        val r = CandidateEngine().rank(p.format, p.slots, prior)
        assertEquals(n1, r.best!!.number)
        assertEquals(n2, r.candidates.last().number)
    }

    @Test fun labelContextFromText() {
        val text = """USPS PRIORITY MAIL
            |JOHN SENDER
            |12 MAIN ST
            |SEWELL NJ 08081
            |SHIP DATE: 11/17/2026
            |SHIP TO:
            |JANE DOE
            |5 ELM ST
            |BARRE VT 05641-1234
            |USPS TRACKING # EP""".trimMargin()
        val c = LabelContext.fromLabelText(text)
        assertEquals("05641", c.destinationZip)
        assertEquals("08081", c.originZip)
        assertEquals("BARRE", c.destinationCity)
        assertEquals("SEWELL", c.originCity)
        assertEquals(LocalDate.of(2026, 11, 17), c.labelDate)
        assertEquals("Priority Mail", c.mailClass)
        assertEquals("05641", LabelContext.fromLabelText("MAILED FROM ZIP 08081 SHIP TO X 12345", barcodeZip = "056411234").destinationZip)
        assertEquals("08081", LabelContext.fromLabelText("MAILED FROM ZIP 08081").originZip)
    }

    @Test fun jsonRoundTrip() {
        val f = TrackingFacts.fromJson(FakeUsps.found(n1).body.trim().removePrefix("[").removeSuffix("]"))
        assertEquals("Priority Mail", f.mailClass)
        assertEquals(f, TrackingFacts.fromJson(f.toJson()))
        assertEquals(listOf(1.0, "a\"b", null, mapOf("k" to true)), Json.parse(Json.write(listOf(1, "a\"b", null, mapOf("k" to true)))))
    }

    /** Sleeps are chunked by the engine (≤500 ms); regroup them per pause (separated by calls). */
    private fun List<Long>.chunkedBySum(): List<Long> {
        val out = ArrayList<Long>(); var acc = 0L
        for (s in this) { acc += s; if (s < 500) { out += acc; acc = 0 } }
        if (acc > 0) out += acc
        return out
    }
}
