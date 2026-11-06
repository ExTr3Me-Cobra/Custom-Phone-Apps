package com.ironlog.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Developer trial: a 1 Rep Max estimate tuned to you, shown next to the standard one on 1 Rep Max tests. It changes
 * nothing else in the app.
 *
 * The standard estimate (Stats.e1rm, the Wathen formula) assumes everyone gets the same number of reps at a given
 * share of their max. People differ a lot there, so this learns two things per exercise from your own history:
 *
 * 1. Endurance (how you handle reps): from sets marked F (to failure) in the 6 weeks before each 1 Rep Max test where
 *    you lifted 1–3 reps. The test is your true max; the F set says how many reps you got at that share of it. The
 *    correction stretches or squeezes the reps part of the formula, so singles barely change and long sets change most.
 * 2. Reserve (reps you usually leave in the tank on sets not marked F): from those sets the same way, once endurance
 *    is known. It's only applied to non-F sets.
 *
 * Evidence is weighted: F sets 4×, your latest F set 2× more, recent tests and sets more than old ones. Sets after
 * earlier hard work on the same muscles count less and get a small allowance for fatigue; an F set that beats the
 * earlier sets of the same exercise that day shows those weren't all-out, so they're ignored and the F set gets less
 * fatigue allowance. Only sets within a week of a test are compared with it (strength grows), fresh 4+ rep sets from tests count most,
 * and each test's say is capped. Everything starts at "no change" and only moves as evidence builds up.
 */
object Personal1Rm {
    class Estimate(
        val standard: Double, val personal: Double,
        val tests: Int, val fSets: Int, val otherSets: Int,
        /** < 1: you get more reps than the formula expects (it over-reads your sets); > 1: fewer. */
        val endurance: Double,
        /** Reps usually left in the tank on sets not marked F (null = not enough to tell). */
        val reserve: Double?,
        /** True when it has a 1 Rep Max test (1–3 reps) and sets near it to learn from, so enhanced can differ from regular. */
        val active: Boolean,
    )

    private const val DAY = 24L * 3600 * 1000
    /** Sets are only compared with a test from within a week of it, so strength gained in between doesn't skew it. */
    private const val WINDOW = 7 * DAY

    // The standard curve: share of your max you can lift for r reps (Wathen), r may be fractional.
    private fun share(r: Double) = (48.8 + 53.8 * exp(-0.075 * r.coerceIn(1.0, 20.0))) / 100
    private fun fromReps(w: Double, r: Double) = if (r <= 1.0) w else w / share(r)
    /** Reps the standard curve gives at [p] of your max (inverse of [share]); null when outside its range. */
    private fun repsAt(p: Double): Double? {
        val x = (100 * p - 48.8) / 53.8
        if (x <= 0) return null
        return (-ln(x) / 0.075).takeIf { it >= 1.0 }
    }

    /** Reps to failure at [w] for someone whose max is [max] (whole reps; 0 if it's more than the max). */
    fun failureReps(w: Double, max: Double, endurance: Double = 1.0): Int? {
        // Small tolerance so a set right at your estimate reads as what you did, not one fewer.
        if (w <= 0 || max <= 0) return null
        if (w > max) return 0
        val tr = repsAt(w / max) ?: return 1
        return kotlin.math.round((tr - 1) / endurance + 1 + 1e-6).toInt().coerceAtLeast(1)
    }

    private class S(val w: Workout, val item: Int, val set: Int, val s: DoneSet)

    /** How worn out the muscles of [id] are going into a set: earlier hard sets that day, by shared muscles. */
    private fun fatigue(w: Workout, itemIndex: Int, setIndex: Int, id: String): Double {
        val target = Store.exercise(id)?.muscles ?: return 0.0
        val total = target.values.sum().takeIf { it > 0f } ?: return 0.0
        val before = mutableListOf<Pair<String, DoneSet>>()
        w.items.forEachIndexed { i, it ->
            if (i < itemIndex) it.sets.forEach { s -> before += it.exerciseId to s }
            else if (i == itemIndex) it.sets.take(setIndex).forEach { s -> before += it.exerciseId to s }
        }
        var score = 0.0
        before.asReversed().forEachIndexed { k, (ex, s) ->
            if (!s.done || s.warmup) return@forEachIndexed
            val m = Store.exercise(ex)?.muscles ?: return@forEachIndexed
            val overlap = target.entries.sumOf { (mu, v) -> minOf(v, m[mu] ?: 0f).toDouble() } / total
            score += overlap * (if (s.failure) 1.0 else 0.6) * Math.pow(0.85, k.toDouble())
        }
        return score
    }

    /** 0 … 0.08: how many more reps a fresh you would have done, as a share of the reps you did. */
    private fun allowance(score: Double) = (0.04 * score).coerceAtMost(0.20)

    private var activeKey: Pair<List<Workout>, Profile>? = null
    private var activeSet: Set<String> = emptySet()

    /** Exercises the enhanced estimate is learning from (red ★). Only those with a 1–3 rep test can be, so only they're checked. */
    fun activeIds(workouts: List<Workout>, p: Profile): Set<String> {
        if (activeKey?.first === workouts && activeKey?.second == p) return activeSet
        val candidates = workouts.filter { it.test && it.end != null }.flatMap { w ->
            w.items.filter { it.sets.any { s -> s.done && !s.warmup && (s.weight ?: 0.0) > 0 && (s.reps ?: 0) in 1..3 } }.map { it.exerciseId }
        }.toSet()
        activeSet = candidates.filter { estimate(it, workouts, p)?.active == true }.toSet()
        activeKey = workouts to p
        return activeSet
    }

    fun estimate(id: String, workouts: List<Workout>, p: Profile, now: Long = System.currentTimeMillis()): Estimate? {
        if (Standards.isReps(id) || Standards.isTimed(id) || Standards.isFlat(id) || Standards.isOneLeg(id)) return null
        val done = workouts.filter { it.end != null }
        // Cable moves: every weight is read as the load you really move (stack × stacks ÷ pulley ratio), as ranks do.
        val k = Standards.stackFactor(id)
        fun setsOf(w: Workout) = w.items.withIndex().filter { it.value.exerciseId == id }
            .flatMap { (i, it) -> it.sets.withIndex().filter { (_, s) -> s.done && !s.warmup && (s.weight ?: 0.0) > 0 && (s.reps ?: 0) > 0 }.map { (j, s) -> S(w, i, j, if (k == 1.0) s else s.copy(weight = s.weight!! * k)) } }

        // Anchors: your 1–3 rep sets in each 1 Rep Max test. A double or triple is turned into a max with the curve
        // being learned (stretch [s]), so the method agrees with itself about your tests.
        val testSets = done.filter { it.test }.map { w -> w.start to setsOf(w).filter { (it.s.reps ?: 0) in 1..3 } }.filter { it.second.isNotEmpty() }
        fun testMax(x: S, s: Double) = fromReps(x.s.weight!!, 1 + (x.s.reps!! - 1) * s)
        fun anchorsFor(s: Double) = testSets.map { (t, xs) -> t to xs.maxOf { testMax(it, s) } }
        val anchors = anchorsFor(1.0)
        val regular = done.filter { !it.test }.flatMap { setsOf(it) }
        // Sets of 4+ reps in a 1 Rep Max test: fresh and all-out, the cleanest evidence there is (treated like F sets).
        val fresh = done.filter { it.test }.flatMap { setsOf(it) }.filter { (it.s.reps ?: 0) >= 4 }.map { S(it.w, it.item, it.set, it.s.copy(failure = true)) }

        // Sets that a later F set of the same exercise that day beat weren't all-out.
        fun beaten(x: S): Boolean = !x.s.failure && regular.any { o ->
            o.w === x.w && o.s.failure && (o.item > x.item || (o.item == x.item && o.set > x.set)) &&
                fromReps(o.s.weight!!, o.s.reps!!.toDouble()) > fromReps(x.s.weight!!, x.s.reps!!.toDouble())
        }
        fun beatsEarlier(x: S): Boolean = x.s.failure && regular.any { o ->
            o.w === x.w && (o.item < x.item || (o.item == x.item && o.set < x.set)) &&
                fromReps(x.s.weight!!, x.s.reps!!.toDouble()) > fromReps(o.s.weight!!, o.s.reps!!.toDouble())
        }
        fun adjustedReps(x: S): Double {
            val a = allowance(fatigue(x.w, x.item, x.set, id)) * (if (beatsEarlier(x)) 0.5 else 1.0)
            return x.s.reps!! * (1 + a)
        }

        val latestF = regular.filter { it.s.failure }.maxByOrNull { it.w.start }
        class Learned(val end2: Double, val reserve: Double?, val f: Int, val n: Int)
        fun learn(s0: Double): Learned {
            class Ev(val x: S, val trueReps: Double, val weight: Double)
            val evidence = mutableListOf<Ev>()
            for ((t, max) in anchorsFor(s0)) {
                val age = exp(-(now - t).toDouble() / (90.0 * DAY))
                val mine = mutableListOf<Ev>()
                for (x in regular + fresh) {
                    if (x.w.start !in (t - WINDOW)..(t + WINDOW)) continue
                    if ((x.s.reps ?: 0) < 3) continue
                    val tr = repsAt(x.s.weight!! / max) ?: continue
                    val near = exp(-kotlin.math.abs(t - x.w.start).toDouble() / (5.0 * DAY))
                    val fatigueTrust = 1 / (1 + 2 * fatigue(x.w, x.item, x.set, id))
                    val w = when {
                        x in fresh -> 8.0
                        x.s.failure -> 4.0 * (if (x === latestF) 2.0 else 1.0)
                        beaten(x) -> 0.0
                        else -> 1.0
                    } * age * near * fatigueTrust
                    if (w > 0) mine += Ev(x, tr, w)
                }
                // One test can only say so much, however many sets sit near it.
                val total = mine.sumOf { it.weight }
                val k = if (total > 12.0) 12.0 / total else 1.0
                mine.forEach { evidence += Ev(it.x, it.trueReps, it.weight * k) }
            }

            // 1. Endurance from F sets: the stretch of the reps scale that makes the formula hit your tested max.
            // Strong pull toward "no change": one test moves it at most about 60% of the way.
            val prior = 8.0
            val fEv = evidence.filter { it.x.s.failure }
            fun stretch(e: Ev, extra: Double) = ((e.trueReps - 1) / (adjustedReps(e.x) + extra - 1)).coerceIn(0.4, 2.5)
            val endurance = if (fEv.isNotEmpty()) {
                ((fEv.sumOf { it.weight * stretch(it, 0.0) } + prior) / (fEv.sumOf { it.weight } + prior)).coerceIn(0.4, 2.5)
            } else 1.0

            // 2. Reserve from the other sets, given that endurance. Without any F sets it can't be told apart from
            //    endurance, so those sets then only nudge endurance (at half weight).
            val nEv = evidence.filter { !it.x.s.failure }
            var reserve: Double? = null
            var end2 = endurance
            if (nEv.isNotEmpty()) {
                if (fEv.isNotEmpty()) {
                    val rs = nEv.map { ((it.trueReps - 1) / endurance + 1 - adjustedReps(it.x)).coerceIn(0.0, 5.0) to it.weight }
                    val sw = rs.sumOf { it.second }
                    val mean = rs.sumOf { it.first * it.second } / sw
                    val sd = sqrt(rs.sumOf { it.second * (it.first - mean) * (it.first - mean) } / sw)
                    // Inconsistent effort -> trust it less (pull toward no adjustment).
                    reserve = (mean * sw / (sw + prior) / (1 + sd * sd)).coerceIn(0.0, 5.0)
                } else {
                    end2 = ((nEv.sumOf { 0.5 * it.weight * stretch(it, 0.0) } + prior) / (nEv.sumOf { 0.5 * it.weight } + prior)).coerceIn(0.4, 2.5)
                }
            }
            return Learned(end2, reserve, fEv.size, nEv.size)
        }
        // Learn, re-read the tests with what was learned, and learn again until it settles.
        var learned = learn(1.0)
        repeat(6) { learned = learn(learned.end2) }
        val end2 = learned.end2
        val reserve = learned.reserve

        fun personalSet(x: S): Double {
            val extra = if (x.s.failure) 0.0 else reserve ?: 0.0
            val re = 1 + (adjustedReps(x) + extra - 1) * end2
            val std = fromReps(x.s.weight!!, x.s.reps!!.toDouble())
            // Wide sanity limits only, so the trial shows what the method really thinks.
            return fromReps(x.s.weight!!, re).coerceIn(std * 0.5, std * 2.0)
        }

        // Current max, picked the same way the rank picks it: the best test within a day of your latest one, raised
        // (never lowered) by workouts since.
        fun current(value: (S) -> Double, testOf: (Workout) -> Double): Double {
            val tests = done.filter { it.test }.map { w -> w.start to testOf(w) }.filter { it.second > 0 }
            val test = tests.maxByOrNull { it.first }
            val testValue = test?.let { t -> tests.filter { it.first >= t.first - DAY }.maxOf { it.second } }
            val after = regular.filter { test == null || it.w.start > test.first }
            val fromWorkouts = if (test != null) after.maxOfOrNull(value)
                else after.maxOfOrNull { it.w.start }?.let { latest -> after.filter { it.w.start >= latest - DAY }.maxOf(value) }
            return maxOf(testValue ?: 0.0, fromWorkouts ?: 0.0)
        }
        val standard = current({ fromReps(it.s.weight!!, it.s.reps!!.toDouble()) }) { w ->
            // A test's result (heaviest single, else best set), from the same load-adjusted sets.
            val xs = setsOf(w); val singles = xs.filter { it.s.reps == 1 }
            if (singles.isNotEmpty()) singles.maxOf { it.s.weight!! } else xs.maxOfOrNull { fromReps(it.s.weight!!, it.s.reps!!.toDouble()) } ?: 0.0
        }
        val personal = current(::personalSet) { w ->
            // Same rule as a test result (heaviest single, else best set), read with your curve.
            val xs = setsOf(w); val singles = xs.filter { it.s.reps == 1 }
            if (singles.isNotEmpty()) singles.maxOf { it.s.weight!! } else xs.maxOfOrNull { testMax(it, end2) } ?: 0.0
        }
        // Shown for every weighted exercise; it only learns (active) with a 1–3 rep test and sets near it.
        return Estimate(standard, personal, anchors.size, learned.f, learned.n, end2, reserve, anchors.isNotEmpty() && learned.f + learned.n > 0)
    }
}

/**
 * Developer trial: "1RM" regular (green) and enhanced (red) for an exercise it has learned from, and under them "F"
 * with the reps each expects you to get to failure at [fWeight] (the weight in your F set).
 */
@Composable
fun DevOneRmBadge(exId: String, fWeight: Double?, modifier: Modifier = Modifier) {
    val p = Store.profile.collectAsState().value
    if (!p.dev1rm || !Standards.covers(exId, p)) return
    val workouts = Store.workouts.collectAsState().value
    val e = remember(workouts, exId, p) { Personal1Rm.estimate(exId, workouts, p) } ?: return
    fun w(v: Double) = if (v > 0) Units.est(v) else "–"
    // The F set's weight as the load you really move (pulley ratio, stacks), like the estimates.
    val fw = fWeight?.let { it * Standards.stackFactor(exId) }
    val fs = fw?.takeIf { e.standard > 0 }?.let { Personal1Rm.failureReps(it, e.standard) }
    val fp = fw?.takeIf { e.personal > 0 }?.let { Personal1Rm.failureReps(it, e.personal, e.endurance) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row {
            Text("1RM ${w(e.standard)}", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("   ", fontSize = 12.sp)
            Text("1RM ${w(e.personal)}", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Row {
            Text("F ${fs ?: "–"}", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("   ", fontSize = 12.sp)
            Text("F ${fp ?: "–"}", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Developer trial: is the enhanced estimate learning from this exercise? (Red ★ after its name and set numbers.) */
@Composable
fun devOneRmActive(exId: String?): Boolean {
    val p = Store.profile.collectAsState().value
    if (!p.dev1rm || exId == null) return false
    val workouts = Store.workouts.collectAsState().value
    return remember(workouts, p) { Personal1Rm.activeIds(workouts, p) }.contains(exId)
}
