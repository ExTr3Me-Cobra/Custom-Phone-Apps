package com.labelrescue.core.verify

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class VerifyStatus(val label: String) {
    /** Tracking history exists and agrees with the label. */
    CONFIRMED("Confirmed"),
    /** Real tracking history, but it conflicts with the label: a different package. */
    EXISTS_MISMATCH("Different package"),
    /** USPS has no record. */
    NOT_FOUND("Not found"),
    /** Network error, rate limited, no credentials, or nothing to compare. */
    UNKNOWN("Unknown"),
}

data class Evidence(val field: String, val label: String?, val usps: String?, val result: Result) {
    enum class Result { MATCH, MISMATCH, CLOSE }

    /** e.g. "Destination 05641 matches". */
    fun text(): String = when (result) {
        Result.MATCH -> "$field ${usps ?: label} matches"
        Result.MISMATCH -> "$field differs: label $label, USPS $usps"
        Result.CLOSE -> "$field close: label $label, USPS $usps"
    }
}

data class Verdict(val status: VerifyStatus, val evidence: List<Evidence> = emptyList(), val note: String? = null) {
    val summary: String get() = (listOfNotNull(note) + evidence.map { it.text() }).joinToString(", ")
}

/** What the API said about one number, before comparing with the label. */
sealed class LookupOutcome {
    data class Found(val facts: TrackingFacts) : LookupOutcome()
    /** USPS knows the label but has no scans/status yet (error codes 150001, 150003, 150009). */
    data class NoStatusYet(val message: String?) : LookupOutcome()
    data class NotFound(val code: String?, val message: String?) : LookupOutcome()
    data class Failed(val reason: String) : LookupOutcome()
}

/**
 * Decides whether a USPS record is this label. Existence alone is never enough: a CONFIRMED
 * result needs the destination ZIP to match (or two other independent details), with no conflict.
 */
object MatchScorer {
    /** Label date vs. first USPS event: within this many days is a match, beyond [FAR_DAYS] a conflict. */
    const val NEAR_DAYS = 4L
    const val FAR_DAYS = 10L

    fun score(outcome: LookupOutcome, ctx: LabelContext): Verdict = when (outcome) {
        is LookupOutcome.Failed -> Verdict(VerifyStatus.UNKNOWN, note = outcome.reason)
        is LookupOutcome.NotFound -> Verdict(VerifyStatus.NOT_FOUND, note = "USPS has no record" + (outcome.code?.let { " ($it)" } ?: ""))
        is LookupOutcome.NoStatusYet -> Verdict(VerifyStatus.UNKNOWN, note = "Label known to USPS but no scans yet")
        is LookupOutcome.Found -> scoreFacts(outcome.facts, ctx)
    }

    fun scoreFacts(f: TrackingFacts, ctx: LabelContext): Verdict {
        if (!f.hasHistory) return Verdict(VerifyStatus.UNKNOWN, note = "USPS returned no tracking history")
        val ev = ArrayList<Evidence>()
        var strongMismatch = false
        var softMismatches = 0
        var destZipMatch = false
        var otherMatches = 0

        zip(ctx.destinationZip, f.destinationZip)?.let { r ->
            ev += Evidence("Destination", ctx.destinationZip, f.destinationZip?.take(5), r)
            if (r == Evidence.Result.MATCH) destZipMatch = true else strongMismatch = true
        }
        city(ctx.destinationCity, f.destinationCity)?.let { r ->
            ev += Evidence("Destination city", ctx.destinationCity, f.destinationCity, r)
            if (r == Evidence.Result.MISMATCH) softMismatches++ else otherMatches++
        }
        val uspsOriginZip = f.originZip ?: acceptanceEvent(f)?.zip
        zip(ctx.originZip, uspsOriginZip)?.let { r ->
            ev += Evidence("Origin", ctx.originZip, uspsOriginZip?.take(5), r)
            if (r == Evidence.Result.MATCH) otherMatches++ else strongMismatch = true
        }
        val uspsOriginCity = f.originCity ?: acceptanceEvent(f)?.city
        city(ctx.originCity, uspsOriginCity)?.let { r ->
            ev += Evidence("Origin city", ctx.originCity, uspsOriginCity, r)
            if (r == Evidence.Result.MISMATCH) softMismatches++ else otherMatches++
        }
        mailClass(ctx.mailClass, f.mailClass)?.let { r ->
            ev += Evidence("Mail class", ctx.mailClass, f.mailClass, r)
            if (r == Evidence.Result.MATCH) otherMatches++ else strongMismatch = true
        }
        val first = firstEventDate(f)
        if (ctx.labelDate != null && first != null) {
            val days = abs(ChronoUnit.DAYS.between(ctx.labelDate, first))
            val r = when {
                days <= NEAR_DAYS -> Evidence.Result.MATCH
                days > FAR_DAYS -> Evidence.Result.MISMATCH
                else -> Evidence.Result.CLOSE
            }
            ev += Evidence("Label date", LabelContext.formatDate(ctx.labelDate), LabelContext.formatDate(first), r)
            when (r) {
                Evidence.Result.MATCH -> otherMatches++
                Evidence.Result.MISMATCH -> strongMismatch = true
                Evidence.Result.CLOSE -> {}
            }
        }

        return when {
            strongMismatch || softMismatches >= 2 -> Verdict(VerifyStatus.EXISTS_MISMATCH, ev, "Real package, but not this label")
            destZipMatch || otherMatches >= 2 -> Verdict(VerifyStatus.CONFIRMED, ev)
            ev.isEmpty() -> Verdict(VerifyStatus.UNKNOWN, ev, "Exists, but nothing on the label to compare. Enter the ZIP or date")
            else -> Verdict(VerifyStatus.UNKNOWN, ev, "Exists; not enough matching detail")
        }
    }

    private fun zip(label: String?, usps: String?): Evidence.Result? {
        val a = label?.filter { it.isDigit() }?.take(5)?.takeIf { it.length == 5 } ?: return null
        val b = usps?.filter { it.isDigit() }?.take(5)?.takeIf { it.length == 5 } ?: return null
        return if (a == b) Evidence.Result.MATCH else Evidence.Result.MISMATCH
    }

    private fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Cities are compared loosely: OCR may misread a letter or two. */
    private fun city(label: String?, usps: String?): Evidence.Result? {
        val a = label?.let(::norm)?.takeIf { it.length >= 2 } ?: return null
        val b = usps?.let(::norm)?.takeIf { it.length >= 2 } ?: return null
        if (a == b || a.endsWith(b) || b.endsWith(a)) return Evidence.Result.MATCH
        return if (editDistance(a, b) <= maxOf(1, minOf(a.length, b.length) / 5)) Evidence.Result.MATCH else Evidence.Result.MISMATCH
    }

    private val CLASS_KEYS = listOf(
        "PRIORITY MAIL EXPRESS" to "EXPRESS", "EXPRESS MAIL" to "EXPRESS", "PRIORITY" to "PRIORITY",
        "GROUND ADVANTAGE" to "GROUND", "PARCEL SELECT" to "PARCEL SELECT", "FIRST-CLASS" to "FIRST", "FIRST CLASS" to "FIRST",
        "MEDIA" to "MEDIA", "LIBRARY" to "LIBRARY", "BOUND PRINTED" to "BPM",
    )

    private fun classKey(s: String): String? {
        val u = s.uppercase()
        return CLASS_KEYS.firstOrNull { u.contains(it.first) }?.second
    }

    private fun mailClass(label: String?, usps: String?): Evidence.Result? {
        val a = label?.let(::classKey) ?: return null
        val b = usps?.let(::classKey) ?: return null
        // Ground Advantage replaced First-Class Package; treat them as the same family.
        val same = a == b || setOf(a, b) == setOf("GROUND", "FIRST")
        return if (same) Evidence.Result.MATCH else Evidence.Result.MISMATCH
    }

    private fun acceptanceEvent(f: TrackingFacts): TrackingFacts.Event? =
        f.events.lastOrNull { e ->
            val t = e.type?.uppercase().orEmpty()
            e.code == "03" || e.code == "OA" || t.contains("ACCEPT") || t.contains("POSSESSION")
        }

    /** Earliest event date (events usually come newest first): label creation or first scan. */
    fun firstEventDate(f: TrackingFacts): LocalDate? =
        f.events.mapNotNull { e -> e.timestamp?.let(::parseTimestamp) }.minOrNull()

    fun parseTimestamp(s: String): LocalDate? =
        runCatching { OffsetDateTime.parse(s).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }
}
