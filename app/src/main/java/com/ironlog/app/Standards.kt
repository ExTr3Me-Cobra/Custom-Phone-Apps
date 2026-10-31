package com.ironlog.app

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/**
 * Where a lift puts you among people who lift, from the app's strength standards (see [StandardsData]):
 * adjusted for your body weight (between and beyond the table's rows), sex and age.
 * Height isn't used: body weight already accounts for most of the difference in size.
 */
object Standards {
    private var tableOf: List<StandardsData.Std>? = null
    private var tableMap: Map<String, StandardsData.Std> = emptyMap()
    /** By exercise id, rebuilt when the set in use changes (the Gemini developer setting). */
    private val table: Map<String, StandardsData.Std>
        get() {
            val a = StandardsData.all
            if (a !== tableOf) { tableMap = a.associateBy { it.id }; tableOf = a }
            return tableMap
        }

    fun covers(id: String) = table.containsKey(id)
    fun isReps(id: String) = table[id]?.reps == true
    fun isTimed(id: String) = table[id]?.timed == true
    /** A ranked number as you'd read it: "1:45" for a hold, "12 reps", or a weight. */
    fun format(id: String, v: Double): String = when {
        isTimed(id) -> Units.clock(Math.round(v) * 1000L)
        isReps(id) -> "${v.toInt()} reps"
        else -> Units.weightU(Math.round(v).toDouble())
    }
    /** Ranked against everyone of your sex (no body weight or age), by your single best number. */
    fun isFlat(id: String) = table[id]?.flat == true
    fun info(id: String) = table[id]

    /** How well the data matches you for one exercise. */
    class Fit(val rows: List<Pair<Double, DoubleArray>>, val problems: List<String>, val compared: String) {
        val exact get() = problems.isEmpty()
    }

    /** The rows to use for you, and anything that makes your percentile less exact. Null without a standard or body weight. */
    fun fit(id: String, p: Profile): Fit? {
        val s = table[id] ?: return null
        if (s.flat) {
            val rows = (if (p.male) s.male else s.female) ?: return null
            return Fit(rows, emptyList(), "Compared with ${if (p.male) "men" else "women"} aged 20–39 – everyone, not just people who lift. " +
                "Body weight and age aren't used. Source: international grip-strength norms from 2.4 million adults in 69 countries " +
                "(Tomkinson et al. 2025), hand dynamometer. Your number is your best squeeze with either hand, as in the norms (each person's strongest reading)." + (s.note?.let { " $it" } ?: ""))
        }
        val bw0 = p.bodyweight ?: return null
        // Body fat: compared as if you weighed a little more or less (see BodyFat).
        val bw = BodyFat.compareWeight(p, bw0, s.reps)
        val rows = (if (p.male) s.male else s.female) ?: return null
        val problems = mutableListOf<String>()
        val lo = rows.first().first; val hi = rows.last().first
        if (bw < lo || bw > hi) problems += "Your body weight (${Units.weightU(bw)}) is outside the body weights the standard covers " +
            "(${Units.weightU(lo)}–${Units.weightU(hi)}), so it's stretched beyond the data."
        else {
            // Inside the range: the two rows either side must be close enough to blend reliably.
            val below = rows.last { it.first <= bw }.first; val above = rows.first { it.first >= bw }.first
            if (above - below > 50) problems += "The nearest rows (${Units.weightU(below)} and ${Units.weightU(above)}) " +
                "are far apart, so your exact body weight is blended across a wide gap."
        }
        when {
            p.age == null -> problems += "Your age isn't set, so no age adjustment is made."
            p.age < AGE_SURE.first || p.age > AGE_SURE.last -> problems += "The adjustment for age ${p.age} is less certain: few lifters that age are in the data it comes from."
        }
        s.approxFor(p.male)?.let { problems += it }
        val sex = if (p.male) "men who lift" else "women who lift"
        val near = rows.map { it.first }.let { ws ->
            val below = ws.lastOrNull { it <= bw }; val above = ws.firstOrNull { it >= bw }
            when {
                below == null || above == null -> "nearest row ${Units.weightU(if (below == null) ws.first() else ws.last())}, extended"
                below == above -> "the ${Units.weightU(below)} row"
                else -> "blended from the ${Units.weightU(below)} and ${Units.weightU(above)} rows"
            }
        }
        val f = ageFactor(p.age, p.male)
        val age = when {
            p.age == null -> "age not set (no adjustment)"
            f >= 0.995 -> "age ${p.age} (no adjustment needed)"
            else -> "age ${p.age} (standards × ${"%.2f".format(f)})"
        }
        val weightText = if (bw == bw0) "your body weight of ${Units.weightU(bw)}"
            else "a body weight of ${Units.weightU(bw)} (your ${Units.weightU(bw0)}, adjusted for body fat ${BodyFat.label(p)})"
        val compared = "Compared with $sex at $weightText ($near), $age." + (s.noteFor(p.male)?.let { " $it" } ?: "")
        return Fit(rows, problems, compared)
    }

    /** In order: each mode ranks everything the one before it does, and more. */
    enum class Mode { OFF, ACCURATE, ALL, AI }
    fun mode(p: Profile) = when (p.rankMode) { "off" -> Mode.OFF; "all" -> Mode.ALL; "ai" -> Mode.AI; else -> Mode.ACCURATE }
    fun isAi(id: String, p: Profile) = table[id]?.aiFor(p.male) == true

    /** Does this exercise get a rank for you in your current mode? */
    fun covers(id: String, p: Profile): Boolean {
        val m = mode(p)
        if (m == Mode.OFF) return false
        if (isAi(id, p) && m != Mode.AI) return false
        val f = fit(id, p) ?: return false
        return m >= Mode.ALL || f.exact
    }

    enum class Star { NONE, EXACT, ESTIMATE, AI }

    /**
     * The ★ after an exercise's name: gold when it's ranked from data that fits you closely,
     * silver-blue when it's only ranked as an estimate (All on mode), purple for an AI estimate (All on + AI),
     * none when it isn't ranked.
     * Before your body weight is set, the star shows what the data is: gold for published research, blue for an estimate.
     */
    fun star(id: String, p: Profile): Star {
        val m = mode(p)
        if (m == Mode.OFF || !covers(id)) return Star.NONE
        if (isAi(id, p)) return if (m == Mode.AI) Star.AI else Star.NONE
        if (p.bodyweight == null && table[id]?.flat != true) return when {
            table[id]?.approxFor(p.male) == null -> Star.EXACT
            m >= Mode.ALL -> Star.ESTIMATE
            else -> Star.NONE
        }
        val f = fit(id, p) ?: return Star.NONE
        return when {
            f.exact -> Star.EXACT
            m >= Mode.ALL -> Star.ESTIMATE
            else -> Star.NONE
        }
    }

    private val PCT = doubleArrayOf(5.0, 20.0, 50.0, 80.0, 95.0)

    /**
     * Age adjustment: strength at each age as a share of the 25–34 peak, bodyweight held equal, averaged over
     * squat, bench and deadlift for raw lifters in OpenPowerlifting (public domain); tools/standards/inputs.
     * Applied to every lift. Past the last age it keeps falling at the same rate (never below 0.3).
     */
    private val AGE_MEN = listOf(13 to 0.657, 15 to 0.742, 17 to 0.862, 19 to 0.935, 22 to 0.988, 27 to 1.0, 32 to 1.0, 37 to 0.978,
        42 to 0.951, 47 to 0.92, 52 to 0.878, 57 to 0.843, 62 to 0.785, 67 to 0.731, 72 to 0.666, 77 to 0.613, 82 to 0.556)
    private val AGE_WOMEN = listOf(13 to 0.701, 15 to 0.772, 17 to 0.871, 19 to 0.94, 22 to 1.0, 27 to 1.0, 32 to 0.992, 37 to 0.977,
        42 to 0.946, 47 to 0.915, 52 to 0.87, 57 to 0.824, 62 to 0.763, 67 to 0.712, 72 to 0.64, 77 to 0.607, 82 to 0.576)
    /** Ages with plenty of lifters behind the adjustment; outside it a rank is an estimate. */
    private val AGE_SURE = 14..75

    fun ageFactor(age: Int?, male: Boolean): Double {
        if (age == null) return 1.0
        val t = if (male) AGE_MEN else AGE_WOMEN
        if (age <= t.first().first) return t.first().second
        if (age >= t.last().first) {
            val (a0, f0) = t[t.size - 2]; val (a1, f1) = t.last()
            return (f1 + (f1 - f0) / (a1 - a0) * (age - a1)).coerceAtLeast(0.3)
        }
        val i = t.indexOfLast { it.first <= age }
        val (a0, f0) = t[i]; val (a1, f1) = t[i + 1]
        return f0 + (f1 - f0) * (age - a0) / (a1 - a0).toDouble()
    }

    /** The five level values for a body weight: log-log interpolation between rows, extended past the ends. */
    private fun levelsAt(rows: List<Pair<Double, DoubleArray>>, bw: Double): DoubleArray = DoubleArray(5) { k ->
        fun v(i: Int) = maxOf(rows[i].second[k], 0.5) // "< 1 rep" cells count as half a rep
        val i = when {
            bw <= rows.first().first -> 0
            bw >= rows.last().first -> rows.size - 2
            else -> rows.indexOfLast { it.first <= bw }.coerceAtMost(rows.size - 2)
        }
        val x0 = ln(rows[i].first); val x1 = ln(rows[i + 1].first)
        val slope = ((ln(v(i + 1)) - ln(v(i))) / (x1 - x0)).coerceIn(-2.0, 1.5)
        exp(ln(v(i)) + slope * (ln(bw) - x0))
    }

    /**
     * Percentile among lifters (0–99.9) for [value] (est. 1-rep max in lb, or reps).
     * Null if there's no standard for you or your body weight isn't set.
     */
    fun percentile(id: String, value: Double, p: Profile): Double? {
        if (value <= 0) return null
        val lv = levels(id, p) ?: return null
        return lifterPercentile(lv, value)
    }

    /** Where [value] sits among people who lift, from the five levels. */
    private fun lifterPercentile(lv: DoubleArray, value: Double): Double =
        when {
            value <= lv[0] -> PCT[0] * value / lv[0]
            value >= lv[4] -> 95 + 4.9 * (1 - exp(-(value - lv[4]) / (lv[4] - lv[3]).coerceAtLeast(1.0)))
            else -> {
                val i = (0 until 4).first { value < lv[it + 1] }
                PCT[i] + (PCT[i + 1] - PCT[i]) * (value - lv[i]) / (lv[i + 1] - lv[i])
            }
        }.coerceIn(0.0, 99.9)

    fun groupLabel(p: Profile) = "people who lift"

    /**
     * The numbers you're compared against: est. 1-rep max (lb) or reps at the 5th, 20th, 50th, 80th and
     * 95th percentile (Beginner … Elite) for your sex, body weight and age. Null without data or body weight.
     */
    fun levels(id: String, p: Profile): DoubleArray? {
        val rows = fit(id, p)?.rows ?: return null
        if (isFlat(id)) return rows.first().second.copyOf()
        val bw = BodyFat.compareWeight(p, p.bodyweight ?: return null, isReps(id) || isTimed(id))
        val f = ageFactor(p.age, p.male) * (table[id]?.factor ?: 1.0)
        return levelsAt(rows, bw).map { it * f }.toDoubleArray()
    }

    val LEVEL_NAMES = listOf("Beginner", "Novice", "Intermediate", "Advanced", "Elite")
    val LEVEL_PCT get() = PCT

    /** The number you'd need for a percentile (the reverse of [percentile]). */
    fun valueFor(id: String, pct: Double, p: Profile): Double? {
        val lv = levels(id, p) ?: return null
        return when {
            pct <= PCT[0] -> lv[0] * pct / PCT[0]
            pct >= 95 -> {
                val x = ((pct - 95) / 4.9).coerceAtMost(0.999)
                lv[4] - ln(1 - x) * (lv[4] - lv[3]).coerceAtLeast(1.0)
            }
            else -> {
                val i = (0 until 4).first { pct < PCT[it + 1] }
                lv[i] + (lv[i + 1] - lv[i]) * (pct - PCT[i]) / (PCT[i + 1] - PCT[i])
            }
        }
    }

    /**
     * A 1 Rep Max test's result: the heaviest completed single; if there's no single, the best
     * estimate from the other sets. Max-reps exercises: the most reps.
     */
    fun testValue(id: String, sets: List<DoneSet>): Double {
        val done = sets.filter { it.done && !it.warmup }
        if (isFlat(id)) return gripValue(sets)
        if (isOneLeg(id)) return done.maxOfOrNull { oneLegValue(it) } ?: 0.0
        if (isTimed(id)) return (done.maxOfOrNull { it.seconds ?: 0 } ?: 0).toDouble()
        if (isReps(id)) return (done.maxOfOrNull { it.reps ?: 0 } ?: 0).toDouble()
        val k = stackFactor(id)
        val single = done.filter { it.reps == 1 }.maxOfOrNull { rankWeight(it.weight) * k } ?: 0.0
        return if (single > 0) single else done.maxOfOrNull { Stats.e1rm(rankWeight(it.weight) * k, capReps(id, it.reps)) } ?: 0.0
    }

    fun isOneLeg(id: String) = table[id]?.oneLeg == true

    /** Dynamometer squeezes take turns: right, left, right, left… (warm-ups don't count). True = right hand, null = warm-up. */
    fun hands(sets: List<DoneSet>): List<Boolean?> {
        var n = 0
        return sets.map { if (it.warmup) null else (n++ % 2 == 0) }
    }

    /** One set's own number (for crowns and finding a set): a grip squeeze is just its reading, whichever hand. */
    fun oneSet(id: String, s: DoneSet, test: Boolean): Double = when {
        !s.done || s.warmup -> 0.0
        isFlat(id) -> s.weight ?: 0.0
        test -> testValue(id, listOf(s))
        else -> bestValue(id, listOf(s))
    }

    /** Grip: your best squeeze with either hand, as the norms use each person's strongest reading. */
    fun gripValue(sets: List<DoneSet>): Double =
        sets.filter { it.done && !it.warmup }.maxOfOrNull { it.weight ?: 0.0 } ?: 0.0

    /**
     * One-leg set -> the two-leg set it matches, then scored exactly like the two-leg version.
     * On one leg the calf carries body weight + vest. On two legs each calf carries half of (body weight + load),
     * so the same work per calf is a two-leg load of body weight + 2 × vest, for the same reps.
     */
    fun twoLegLoad(vest: Double, bw: Double) = bw + 2 * vest.coerceAtLeast(0.0)
    private fun oneLegValue(s: DoneSet): Double {
        val bw = Store.profile.value.bodyweight ?: return 0.0
        return Stats.e1rm(twoLegLoad(s.weight ?: 0.0, bw), s.reps)
    }

    /** A set logged at 0 lb (or with no weight) counts as 1 lb for ranking, so it still gets a rank. */
    private fun rankWeight(w: Double?) = if (w == null || w <= 0) 1.0 else w

    /**
     * Cable moves: the standards are for a 1:1 pulley and the usual number of stacks. Your logged stack weight × your
     * stacks ÷ the usual stacks (both-arm moves), ÷ your pulley ratio (2:1 = you move half the stack).
     */
    private fun stackFactor(id: String): Double {
        val e = Store.exercise(id) ?: return 1.0
        if (e.equipment != "Cable") return 1.0
        val p = Store.profile.value
        val stacks = if (e.hasStackChoice()) p.stacksFor(e).toDouble() / e.defaultStacks() else 1.0
        return stacks / p.pulleyFor(e)
    }

    /** The rankable number in a set of sets: best est. 1-rep max, or most reps. */
    fun bestValue(id: String, sets: List<DoneSet>): Double {
        val done = sets.filter { it.done && !it.warmup } // warm-ups never count
        if (isFlat(id)) return gripValue(sets)
        if (isOneLeg(id)) return done.maxOfOrNull { oneLegValue(it) } ?: 0.0
        if (isTimed(id)) return (done.maxOfOrNull { it.seconds ?: 0 } ?: 0).toDouble()
        if (isReps(id)) return (done.maxOfOrNull { it.reps ?: 0 } ?: 0).toDouble()
        val k = stackFactor(id)
        return done.maxOfOrNull { Stats.e1rm(rankWeight(it.weight) * k, capReps(id, it.reps)) } ?: 0.0
    }

    /** Reps counted toward a ranked estimated max: the standard's cap (12 on V2's machine and isolation lifts). */
    private fun capReps(id: String, reps: Int?) = reps?.let { minOf(it, table[id]?.repCap ?: 20) }

    fun who(p: Profile) = (if (p.male) "men" else "women") +
        (p.bodyweight?.let { " around ${Units.weightU(it)}" } ?: "") + (p.age?.let { ", age $it" } ?: "")
}

/** The 20 ranks: Wood 1 (everyone) … Diamond 3 (top 15%), Champion (top 10%), Legend (top 5%). */
enum class Tier(val label: String, val badge: String, val color: Long) {
    WOOD_1("Wood 1", "wood_1", 0xFFD9B48A), WOOD_2("Wood 2", "wood_2", 0xFFC1925F), WOOD_3("Wood 3", "wood_3", 0xFFA06A3A),
    BRONZE_1("Bronze 1", "bronze_1", 0xFFF3A774), BRONZE_2("Bronze 2", "bronze_2", 0xFFE0803F), BRONZE_3("Bronze 3", "bronze_3", 0xFFC0601F),
    SILVER_1("Silver 1", "silver_1", 0xFFDCE3EC), SILVER_2("Silver 2", "silver_2", 0xFFB6C2D1), SILVER_3("Silver 3", "silver_3", 0xFF8E9DB2),
    GOLD_1("Gold 1", "gold_1", 0xFFFFE27D), GOLD_2("Gold 2", "gold_2", 0xFFF7C743), GOLD_3("Gold 3", "gold_3", 0xFFDDA21A),
    PLATINUM_1("Platinum 1", "platinum_1", 0xFF97F2EA), PLATINUM_2("Platinum 2", "platinum_2", 0xFF52D8D0), PLATINUM_3("Platinum 3", "platinum_3", 0xFF1FB4AE),
    DIAMOND_1("Diamond 1", "diamond_1", 0xFF94C8FF), DIAMOND_2("Diamond 2", "diamond_2", 0xFF529DFF), DIAMOND_3("Diamond 3", "diamond_3", 0xFF2371E0),
    CHAMPION("Champion", "champion", 0xFFFF6FB5), LEGEND("Legend", "legend", 0xFFA64DFF);

    /** "Top 45%": the share of lifters at this rank or above. */
    val topPct get() = 100 - ordinal * 5

    companion object {
        fun of(percentile: Double): Tier = entries[floor(percentile / 5).toInt().coerceIn(0, entries.size - 1)]
    }
}

/** "top 47%" for a percentile (stronger than 53% of lifters). */
fun topLabel(percentile: Double) = "Top ${topOf(percentile)}%"

private fun topOf(percentile: Double) = (100 - percentile).toInt().coerceIn(1, 100)

/** The "stronger than" %, always 100 minus the Top % so the two add up (rounds up when it's between two whole numbers). */
fun betterThan(percentile: Double) = 100 - topOf(percentile)

/** Your ranks from your logged workouts. */
object Ranks {
    /** [testedAt]: when the 1 Rep Max test this rank rests on was done (null = from workouts only). */
    class Ranked(val exercise: Exercise, val value: Double, val percentile: Double, val testedAt: Long? = null) {
        val tier get() = Tier.of(percentile)
    }

    /** A best is protected this long: a weaker session within 24 hours of it doesn't lower your rank. */
    const val HOLD_MS = 24L * 60 * 60 * 1000

    /**
     * Each ranked exercise: the best of your sessions in the 24 hours up to the last time you did it.
     * So a weaker session can only lower a rank once your better one is more than 24 hours older,
     * and skipping an exercise changes nothing.
     */
    fun all(workouts: List<Workout>, p: Profile): Map<String, Ranked> {
        class S(val start: Long, val value: Double, val test: Boolean)
        val sessions = HashMap<String, MutableList<S>>()
        for (w in workouts) {
            if (w.end == null) continue
            for (id in w.items.map { it.exerciseId }.distinct()) {
                if (!Standards.covers(id, p)) continue
                val sets = w.items.filter { it.exerciseId == id }.flatMap { it.sets }
                val v = if (w.test) Standards.testValue(id, sets) else Standards.bestValue(id, sets)
                if (v > 0) sessions.getOrPut(id) { mutableListOf() } += S(w.start, v, w.test)
            }
        }
        val out = HashMap<String, Ranked>()
        for ((id, list) in sessions) {
            val ex = Store.exercise(id) ?: continue
            // 1 Rep Max tests: the best test within 24 hours of your latest one sets the rank, so only a weaker test
            // 24+ hours after a better one lowers it. Workouts since then can only raise it (never lower it).
            val tests = list.filter { it.test }
            val test = tests.maxByOrNull { it.start }
            val testValue = test?.let { t -> tests.filter { it.start >= t.start - HOLD_MS }.maxOf { it.value } }
            val after = list.filter { !it.test && (test == null || it.start > test.start) }
            val fromWorkouts = if (test != null) after.maxOfOrNull { it.value }
                else after.maxOfOrNull { it.start }?.let { latest -> after.filter { it.start >= latest - HOLD_MS }.maxOf { it.value } }
            val v = maxOf(testValue ?: 0.0, fromWorkouts ?: 0.0)
            if (v <= 0) continue
            val pct = Standards.percentile(id, v, p) ?: continue
            out[id] = Ranked(ex, v, pct, test?.start)
        }
        return out
    }

    /** The finished workout and set your rank's number came from (the latest one, if several match). */
    fun source(id: String, value: Double, workouts: List<Workout>): Pair<Workout, DoneSet>? {
        for (w in workouts.filter { it.end != null }.sortedByDescending { it.start }) {
            val sets = w.items.filter { it.exerciseId == id }.flatMap { it.sets }
            // Grip: the workout whose best squeeze matches; point at that squeeze.
            if (Standards.isFlat(id)) {
                if (kotlin.math.abs(Standards.gripValue(sets) - value) < 0.01) return sets.filter { it.done && !it.warmup }.maxByOrNull { it.weight ?: 0.0 }?.let { w to it }
                continue
            }
            for (s in sets) {
                if (!s.done || s.warmup) continue
                val v = if (w.test) Standards.testValue(id, listOf(s)) else Standards.bestValue(id, listOf(s))
                if (kotlin.math.abs(v - value) < 0.01) return w to s
            }
        }
        return null
    }

    /** After finishing a workout: shows a rank-down for each rank (and the overall rank) that went down. */
    fun onFinished(before: Map<String, Ranked>, after: Map<String, Ranked>) {
        for ((id, b) in before) {
            val a = after[id] ?: continue
            if (a.tier.ordinal < b.tier.ordinal)
                celebrations += Celebration(a.exercise.name, "Rank down", a.tier, a.percentile, down = true, from = b.tier)
        }
        val ob = overallOf(before) ?: return
        val oa = overallOf(after) ?: return
        if (Tier.of(oa).ordinal < Tier.of(ob).ordinal)
            celebrations += Celebration("Overall rank", "Rank down", Tier.of(oa), oa, down = true, from = Tier.of(ob))
    }

    /** Overall: the average percentile of your ranked exercises. */
    fun overall(ranks: Collection<Double>): Double? = if (ranks.isEmpty()) null else ranks.average()

    /** Overall body rank: the average of your muscle groups' ranks (each group = its two chosen/top exercises). */
    fun overallOf(ranks: Map<String, Ranked>, p: Profile = Store.profile.value): Double? =
        overall(groupRanks(ranks, p.picksNow).values.map { it.first })

    /** The main muscles an exercise ranks: its primary group's muscles that work at 75% or more. */
    fun rankedMuscles(e: Exercise): List<String> =
        e.muscles.filter { (m, v) -> v >= 0.75f && Muscles.group(m) in e.primary && Muscles.group(m) !in NOT_ON_HOME }.keys.toList()

    /** Each main muscle -> your best ranked exercise for it. */
    fun muscles(ranks: Map<String, Ranked>): Map<String, Ranked> {
        val out = HashMap<String, Ranked>()
        for (r in ranks.values) for (m in rankedMuscles(r.exercise)) {
            if ((out[m]?.percentile ?: -1.0) < r.percentile) out[m] = r
        }
        return out
    }

    /** Each muscle group -> its rank (average of your top two ranked exercises for it) and those exercises. */
    fun groupRanks(ranks: Map<String, Ranked>, picks: Map<String, List<String>> = emptyMap()): Map<String, Pair<Double, List<Ranked>>> {
        return groupCandidates(ranks).mapValues { (g, l) ->
            // Your own choice of exercises for this group (if they're still ranked), otherwise your top two.
            val chosen = picks[g].orEmpty().mapNotNull { id -> l.firstOrNull { it.exercise.id == id } }.take(2)
            val top = chosen.ifEmpty { l.sortedByDescending { it.percentile }.take(2) }
            top.map { it.percentile }.average() to top
        }
    }

    /** The hands on the heatmap: the finger flexors that do the gripping. */
    const val HANDS = "forearms (finger flexors)"
    const val GRIP = "grip"

    /** The muscle groups an exercise ranks: with the Grip rank on, the dynamometer ranks Grip (and only Grip). */
    fun groupsOf(e: Exercise, p: Profile = Store.profile.value): List<String> =
        if (p.gripRank && Standards.isFlat(e.id)) listOf(GRIP) else e.primary.filter { it !in NOT_ON_HOME }

    /** Muscle groups left off the Home ranks and body map (their exercises still have their own rank). */
    val NOT_ON_HOME = setOf("abductors", "adductors")

    /** Each muscle group -> every ranked exercise where it's the main muscle, best first. */
    fun groupCandidates(ranks: Map<String, Ranked>): Map<String, List<Ranked>> {
        val by = HashMap<String, MutableList<Ranked>>()
        for (r in ranks.values) for (g in groupsOf(r.exercise)) by.getOrPut(g) { mutableListOf() } += r
        return by.mapValues { (_, l) -> l.sortedByDescending { it.percentile } }
    }

    /** Each muscle group -> your best ranked exercise where it's the main muscle. */
    fun groups(ranks: Map<String, Ranked>): Map<String, Ranked> {
        val out = HashMap<String, Ranked>()
        for (r in ranks.values) for (g in groupsOf(r.exercise)) {
            if ((out[g]?.percentile ?: -1.0) < r.percentile) out[g] = r
        }
        return out
    }

    // ---- Rank-up celebrations during a workout ----

    class Celebration(
        val title: String, val subtitle: String, val tier: Tier, val percentile: Double,
        val down: Boolean = false, val from: Tier? = null, val note: String? = null,
    )

    val celebrations = androidx.compose.runtime.mutableStateListOf<Celebration>()

    /** 1 Rep Max mode: rank-downs wait until you finish the test (a later set may win it back). */
    var holdDowns = false
    private val heldDowns = mutableListOf<Celebration>()

    /** Finishing a 1 Rep Max test: show the rank-downs it held back. */
    fun releaseDowns() { celebrations += heldDowns; heldDowns.clear(); holdDowns = false }
    /** Leaving a test without finishing: forget them. */
    fun dropDowns() { heldDowns.clear(); holdDowns = false }
    private var forWorkout: String? = null
    private val shownTier = HashMap<String, Int>()
    private var shownOverall = -1

    /** Your ranks as if this workout ended now with only its ticked sets. */
    private fun live(w: Workout, p: Profile): Map<String, Ranked> {
        val now = w.copy(
            end = System.currentTimeMillis(),
            items = w.items.map { it.copy(sets = it.sets.filter { s -> s.done }) }.filter { it.sets.isNotEmpty() },
        )
        return all(Store.workouts.value.filter { it.id != w.id } + now, p)
    }

    /** Starts tracking a workout: what's on show is your rank before it. */
    private fun track(w: Workout, p: Profile) {
        if (forWorkout == w.id) return
        forWorkout = w.id
        val before = all(Store.workouts.value.filter { it.id != w.id }, p)
        shownTier.clear()
        before.forEach { (id, r) -> shownTier[id] = r.tier.ordinal }
        shownOverall = overallOf(before)?.let { Tier.of(it).ordinal } ?: -1
    }

    /** Call after a set is ticked off: a rank going up or down shows straight away. */
    fun onSetDone(w: Workout, exerciseId: String) {
        val p = Store.profile.value
        track(w, p)
        if (!Standards.covers(exerciseId, p)) return
        val ex = Store.exercise(exerciseId) ?: return
        val after = live(w, p)
        after[exerciseId]?.let { a ->
            val was = shownTier[exerciseId] ?: -1
            val t = a.tier.ordinal
            if (holdDowns) heldDowns.removeAll { it.title == ex.name }
            when {
                t > was -> celebrations += Celebration(ex.name, if (was < 0) "Ranked!" else "Rank up!", a.tier, a.percentile)
                t < was && holdDowns -> heldDowns += Celebration(ex.name, "Rank down", a.tier, a.percentile, down = true, from = Tier.entries[was])
                t < was -> celebrations += Celebration(ex.name, "Rank down", a.tier, a.percentile, down = true, from = Tier.entries[was])
            }
            // A held rank-down keeps the old rank as the one on show, so only a real rise past it pops up.
            if (!(holdDowns && t < was)) shownTier[exerciseId] = t
        }
        val o = overallOf(after) ?: return
        val ot = Tier.of(o).ordinal
        if (holdDowns) heldDowns.removeAll { it.title == "Overall rank" }
        if (shownOverall >= 0 && ot > shownOverall)
            celebrations += Celebration("Overall rank", "Your overall rank went up!", Tier.of(o), o)
        else if (shownOverall >= 0 && ot < shownOverall) {
            val c = Celebration("Overall rank", "Rank down", Tier.of(o), o, down = true, from = Tier.entries[shownOverall])
            if (holdDowns) { heldDowns += c; return } else celebrations += c
        }
        shownOverall = ot
    }

    /**
     * Call after a set is un-ticked: whatever that set changed is quietly put back (ticking it again
     * shows the rank change again).
     */
    fun onSetUndone(c: android.content.Context, w: Workout, exerciseId: String) {
        if (forWorkout != w.id) return
        val p = Store.profile.value
        if (holdDowns) {
            // 1 Rep Max mode: the rank on show never went down, so just re-check what's held back.
            Store.exercise(exerciseId)?.let { ex -> heldDowns.removeAll { it.title == ex.name } }
            heldDowns.removeAll { it.title == "Overall rank" }
            onSetDone(w, exerciseId)
            return
        }
        val after = live(w, p)
        val t = after[exerciseId]?.tier?.ordinal ?: -1
        val was = shownTier[exerciseId] ?: -1
        if (t != was) {
            Store.exercise(exerciseId)?.let { ex -> celebrations.removeAll { it.title == ex.name } }
            if (t >= 0) shownTier[exerciseId] = t else shownTier.remove(exerciseId)
        }
        val ot = overallOf(after)?.let { Tier.of(it).ordinal } ?: -1
        if (ot != shownOverall) {
            celebrations.removeAll { it.title == "Overall rank" }
            shownOverall = ot
        }
    }
}
