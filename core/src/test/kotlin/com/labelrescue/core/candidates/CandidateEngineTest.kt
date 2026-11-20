package com.labelrescue.core.candidates

import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CandidateEngineTest {
    private val engine = CandidateEngine()

    private fun manual(s: String) = (ManualEntry.parse(s) as ManualEntry.Result.Ok).parsed

    /** Required case: 9234?9027507?500211709 has exactly 10 check-digit-valid completions. */
    @Test fun requiredManualCase() {
        for (input in listOf("9234?9027507?500211709", "9234 ?902 7507 ?500 2117 09", "9234_9027507_500211709")) {
            val p = manual(input)
            assertEquals(TrackingFormat.IMPB_22, p.format)
            val r = engine.rank(p.format, p.slots)
            assertEquals(10, r.candidates.size)
            assertEquals(10.0, r.totalValid, 0.0)
            assertEquals(100.0, r.totalCombinations, 0.0)
            assertTrue(r.complete)
            val nums = r.candidates.map { it.number }.toSet()
            assertTrue("9234190275070500211709" in nums)
            assertTrue("9234690275075500211709" in nums)
            assertTrue(nums.all { CheckDigits.isValidMod10(it) })
            assertEquals(setOf(4, 12), r.candidates.first().guessed)
            assertEquals(1.0, r.candidates.sumOf { it.confidence }, 1e-9)
        }
    }

    @Test fun unknownCheckDigitIsSolved() {
        val p = manual("923469027507550021170?")
        val r = engine.rank(p.format, p.slots)
        assertEquals("9234690275075500211709", r.candidates.single().number)
    }

    @Test fun oneOfBrackets() {
        val p = manual("9234[16]9027507?500211709")
        val r = engine.rank(p.format, p.slots)
        assertEquals(setOf("9234190275070500211709", "9234690275075500211709"), r.candidates.map { it.number }.toSet())
    }

    @Test fun matchesBruteForceWithEvidence() {
        val rnd = Random(7)
        repeat(30) {
            val truth = randomTracking(rnd)
            val unknown = (0 until 22).shuffled(rnd).take(4).toSet()
            val slots = truth.mapIndexed { i, c ->
                if (i in unknown) SlotDist(SlotDist.DIGITS, DoubleArray(10) { rnd.nextDouble() + if (it == c - '0') 1.0 else 0.0 })
                else SlotDist.known(c)
            }
            val r = CandidateEngine(maxResults = 10000).rank(TrackingFormat.IMPB_22, slots)
            // Brute force.
            val idx = unknown.sorted()
            val brute = ArrayList<Pair<String, Double>>()
            for (k in 0 until 10000) {
                val ch = truth.toCharArray()
                var x = k
                for (i in idx) { ch[i] = '0' + x % 10; x /= 10 }
                val s = String(ch)
                if (CheckDigits.isValidMod10(s)) brute += s to engine.evidenceScore(s, slots)
            }
            brute.sortByDescending { it.second }
            assertEquals(brute.size.toDouble(), r.totalValid, 0.0)
            assertEquals(brute.size, r.candidates.size)
            for (i in brute.indices) assertEquals(brute[i].second, r.candidates[i].evidenceScore, 1e-9)
            assertTrue(r.candidates.any { it.number == truth })
        }
    }

    @Test fun beamFindsTruthWithManyUnknowns() {
        val rnd = Random(3)
        repeat(20) {
            val truth = randomTracking(rnd)
            val unknown = (0 until 22).shuffled(rnd).take(9).toSet()
            // Weak evidence: the truth is the most likely digit but only at 30-50%.
            val slots = truth.mapIndexed { i, c ->
                if (i in unknown) SlotDist.likely(c, 0.3 + rnd.nextDouble() * 0.2) else SlotDist.known(c)
            }
            val r = engine.rank(TrackingFormat.IMPB_22, slots)
            assertEquals(1e8, r.totalValid, 1.0)
            assertEquals(truth, r.best!!.number)
        }
    }

    @Test fun historyPriorBreaksTies() {
        val known = "9234690275075500211709"
        val stats = HistoryStats.from(listOf(known, known))
        val p = manual("9234?9027507?500211709")
        val r = engine.rank(p.format, p.slots, HistoryPrior(stats))
        assertEquals(known, r.best!!.number)
        assertTrue(r.best!!.confidence > 0.3)
    }

    @Test fun historySuggestionReachesLowRankedMid() {
        // The evidence prefers 3 at position 8, but the user has confirmed this Mailer ID many times.
        val truth = "9234690275075500211709"
        val stats = HistoryStats.from(List(5) { truth })
        val slots = truth.mapIndexed { i, c ->
            when (i) {
                8 -> SlotDist(SlotDist.DIGITS, DoubleArray(10) { when (it) { 3 -> 0.6; 7 -> 0.3; else -> 0.1 / 8 } })
                21 -> SlotDist.uniform()
                else -> SlotDist.known(c)
            }
        }
        val plain = engine.rank(TrackingFormat.IMPB_22, slots)
        assertTrue(plain.best!!.number[8] == '3')
        val withHistory = engine.rank(TrackingFormat.IMPB_22, slots, HistoryPrior(stats))
        assertEquals(truth, withHistory.best!!.number)
    }

    @Test fun structurePriorPrefersServiceCodeFromLabelText() {
        val hints = com.labelrescue.core.usps.ServiceHints.serviceHints("PRIORITY MAIL")
        val p = manual("92?5?1234567890123456?")
        val r = engine.rank(p.format, p.slots, StructurePrior(hints))
        assertEquals("055", r.best!!.number.substring(2, 5))
    }

    @Test fun s10Manual() {
        val p = manual("RA47312482?US")
        val r = engine.rank(p.format, p.slots)
        assertEquals("RA473124829US", r.candidates.single().number)
        val p2 = manual("RA4731248??US")
        assertEquals(10, engine.rank(p2.format, p2.slots).candidates.size)
    }

    @Test fun routed30() {
        val p = manual("42012345 9234?9027507?500211709")
        assertEquals(TrackingFormat.ROUTED_30, p.format)
        val r = engine.rank(p.format, p.slots)
        assertEquals(10, r.candidates.size)
        assertTrue(r.candidates.any { it.tracking == "9234690275075500211709" })
    }

    @Test fun badLengthIsAnError() {
        assertTrue(ManualEntry.parse("1234?") is ManualEntry.Result.Error)
        assertTrue(ManualEntry.parse("") is ManualEntry.Result.Error)
    }

    companion object {
        fun randomTracking(rnd: Random): String {
            val payload = "92" + (1..19).map { '0' + rnd.nextInt(10) }.joinToString("")
            return payload + CheckDigits.mod10(payload)
        }
    }
}
