package com.ironlog.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Numbers worked out from logged sets. Weights are pounds. */
object Stats {
    /**
     * Estimated 1-rep max from a set: Wathen (1994), 1RM = 100 × w / (48.8 + 53.8 × e^(−0.075 × reps)).
     * In comparisons against tested maxes it is the most accurate (or tied most accurate) on every lift studied
     * (bench, squat, deadlift, arm curl), and unlike Epley or Brzycki it doesn't run away at high reps. Reps over 20 count as 20.
     */
    fun e1rm(w: Double?, r: Int?): Double {
        if (w == null || r == null || w <= 0 || r <= 0) return 0.0
        if (r == 1) return w
        return 100 * w / (48.8 + 53.8 * kotlin.math.exp(-0.075 * minOf(r, 20)))
    }

    enum class Metric(val label: String, val unit: String) {
        E1RM("Est. 1-rep max", "w"),
        MAX_WEIGHT("Heaviest weight", "w"),
        VOLUME("Volume", "w"),
        BEST_REPS("Most reps", "reps"),
        TOTAL_REPS("Total reps", "reps"),
        TIME("Longest time", "s"),
        DISTANCE("Distance", "dist"),
    }

    fun metricsFor(t: ExType): List<Metric> = when (t) {
        ExType.WEIGHT -> listOf(Metric.E1RM, Metric.MAX_WEIGHT, Metric.VOLUME, Metric.BEST_REPS)
        ExType.BODYWEIGHT -> listOf(Metric.BEST_REPS, Metric.TOTAL_REPS)
        ExType.TIMED -> listOf(Metric.TIME)
        ExType.CARDIO -> listOf(Metric.DISTANCE, Metric.TIME)
        ExType.WEIGHT_TIME -> listOf(Metric.MAX_WEIGHT, Metric.TIME)
    }

    /** The value of [m] for one session's sets, or null if there's nothing to show. */
    fun value(m: Metric, sets: List<DoneSet>): Double? {
        val s = sets.filter { it.done }
        if (s.isEmpty()) return null
        val v = when (m) {
            Metric.E1RM -> s.maxOf { e1rm(it.weight, it.reps) }
            Metric.MAX_WEIGHT -> s.maxOf { it.weight ?: 0.0 }
            Metric.VOLUME -> s.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) }
            Metric.BEST_REPS -> s.maxOf { it.reps ?: 0 }.toDouble()
            Metric.TOTAL_REPS -> s.sumOf { it.reps ?: 0 }.toDouble()
            Metric.TIME -> s.maxOf { it.seconds ?: 0 }.toDouble()
            Metric.DISTANCE -> s.sumOf { it.distance ?: 0.0 }
        }
        return v.takeIf { it > 0 }
    }

    class Session(val workout: Workout, val sets: List<DoneSet>)

    /** Every finished workout that included [exId], oldest first. */
    fun sessions(exId: String, workouts: List<Workout>): List<Session> = workouts
        .filter { it.end != null }
        .sortedBy { it.start }
        .mapNotNull { w ->
            val sets = w.items.filter { it.exerciseId == exId }.flatMap { it.sets }.filter { it.done }
            if (sets.isEmpty()) null else Session(w, sets)
        }

    /** The sets from the last time this exercise was done. */
    fun previous(exId: String, workouts: List<Workout>, exclude: String? = null): List<DoneSet> =
        sessions(exId, workouts.filter { it.id != exclude }).lastOrNull()?.sets ?: emptyList()

    /** Things that went better than before, one line each. */
    fun improvements(w: Workout, history: List<Workout>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val before = history.filter { it.id != w.id && it.start < w.start }
        for (item in w.items.distinctBy { it.exerciseId }) {
            val ex = Store.exercise(item.exerciseId) ?: continue
            val sets = w.items.filter { it.exerciseId == item.exerciseId }.flatMap { it.sets }.filter { it.done }
            if (sets.isEmpty()) continue
            val past = sessions(item.exerciseId, before)
            if (past.isEmpty()) {
                out += ex.name to "First time logged – this is your starting point."
                continue
            }
            val last = past.last().sets
            for (m in metricsFor(ex.type)) {
                val now = value(m, sets) ?: continue
                val best = past.mapNotNull { value(m, it.sets) }.maxOrNull()
                val prev = value(m, last)
                when {
                    best != null && now > best + 0.01 -> out += ex.name to "New best ${m.label.lowercase()}: ${Units.metric(m, now)} (was ${Units.metric(m, best)})"
                    prev != null && now > prev + 0.01 -> out += ex.name to "${m.label} up from ${Units.metric(m, prev)} to ${Units.metric(m, now)}"
                }
            }
        }
        return out
    }

    /** One line in the summary's What improved / What got worse: [label] went from [was] to [now]. */
    class Change(val exercise: String, val label: String, val was: String, val now: String, val up: Boolean)

    /**
     * Compared with the last time you did each exercise (1 Rep Max tests never count as "last time"), using only the
     * best single set each time, warm-ups left out: weighted = heaviest weight and best est. 1-rep max, bodyweight =
     * most reps in a set, timed = longest hold. Exercises done for the first time aren't shown.
     * Returns (improved, got worse), and whether anything could be compared at all.
     */
    fun changes(w: Workout, history: List<Workout>): Triple<List<Change>, List<Change>, Boolean> {
        val up = mutableListOf<Change>(); val down = mutableListOf<Change>()
        if (w.test) return Triple(up, down, false)
        var compared = false
        val before = history.filter { it.id != w.id && it.start < w.start && !it.test && it.end != null }
        fun working(o: Workout, id: String) = o.items.filter { it.exerciseId == id }.flatMap { it.sets }.filter { it.done && !it.warmup }
        for (id in w.items.map { it.exerciseId }.distinct()) {
            val ex = Store.exercise(id) ?: continue
            val now = working(w, id)
            if (now.isEmpty()) continue
            val last = before.sortedByDescending { it.start }.firstNotNullOfOrNull { o -> working(o, id).takeIf { it.isNotEmpty() } } ?: continue
            compared = true
            fun cmp(label: String, f: (List<DoneSet>) -> Double, show: (Double) -> String) {
                val a = f(last); val b = f(now)
                if (a <= 0 || b <= 0 || kotlin.math.abs(a - b) < 0.005) return
                val c = Change(ex.name, label, show(a), show(b), b > a)
                if (b > a) up += c else down += c
            }
            val wt: (List<DoneSet>) -> Double = { s -> s.maxOf { it.weight ?: 0.0 } }
            val secs: (List<DoneSet>) -> Double = { s -> s.maxOf { it.seconds ?: 0 }.toDouble() }
            when (ex.type) {
                ExType.WEIGHT -> {
                    cmp("Heaviest weight", wt, Units::weightU)
                    cmp("Est. 1-rep max", { s -> s.maxOf { e1rm(it.weight, it.reps) } }) { Units.weightU(it) }
                }
                ExType.BODYWEIGHT -> cmp("Most reps", { s -> s.maxOf { it.reps ?: 0 }.toDouble() }) { "${it.toInt()} reps" }
                ExType.TIMED -> cmp("Longest hold", secs) { Units.time(it.toInt()) }
                ExType.WEIGHT_TIME -> { cmp("Heaviest weight", wt, Units::weightU); cmp("Longest time", secs) { Units.time(it.toInt()) } }
                ExType.CARDIO -> cmp("Furthest", { s -> s.maxOf { it.distance ?: 0.0 } }) { "${Units.distance(it)} ${Units.dist}" }
            }
        }
        return Triple(up, down, compared)
    }

    /**
     * Each individual muscle's share of a workout, adding up to 100, largest first. Every exercise counts the same,
     * however many sets, reps or pounds: each one's own split (as on its details page) is averaged.
     */
    fun muscleShares(w: Workout): List<Pair<String, Int>> {
        val sum = HashMap<String, Double>()
        val ids = w.items.filter { it.sets.any { s -> s.done } }.map { it.exerciseId }.distinct()
        var n = 0
        for (id in ids) {
            val m = Store.exercise(id)?.muscles ?: continue
            val total = m.values.sumOf { it.toDouble() }
            if (total <= 0) continue
            n++
            m.forEach { (k, v) -> sum[k] = (sum[k] ?: 0.0) + v / total }
        }
        if (n == 0) return emptyList()
        val list = sum.entries.sortedByDescending { it.value }
        val raw = list.map { it.value / n * 100 }
        val shares = raw.map { kotlin.math.floor(it).toInt() }.toIntArray()
        // Largest remainders get the leftover points, so it adds up to exactly 100.
        raw.indices.sortedByDescending { raw[it] - shares[it] }.take(100 - shares.sum()).forEach { shares[it]++ }
        return list.indices.map { list[it].key to shares[it] }.filter { it.second > 0 }
    }

    /** How hard each muscle worked in a workout: 0..1 (most-worked muscle = 1). */
    fun muscleLoad(w: Workout): Map<String, Float> {
        val load = HashMap<String, Float>()
        for (item in w.items) {
            val ex = Store.exercise(item.exerciseId) ?: continue
            val n = item.sets.count { it.done }
            if (n == 0) continue
            // Each muscle counts by how hard it works in this exercise compared with the main muscle.
            ex.muscles.forEach { (m, v) -> load[m] = (load[m] ?: 0f) + n * v }
        }
        val max = load.values.maxOrNull() ?: return emptyMap()
        return load.mapValues { it.value / max }
    }

    fun volume(w: Workout): Double = w.items.filter { Store.exercise(it.exerciseId)?.type != ExType.BODYWEIGHT }.sumOf { i ->
        i.sets.filter { it.done }.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) }
    }
}

/** Showing numbers in the chosen units. Everything is stored in pounds and miles. */
object Units {
    private const val KG = 0.45359237
    private const val KM = 1.609344
    val kg get() = Store.profile.value.kg
    val w get() = if (kg) "kg" else "lbs"
    val dist get() = if (kg) "km" else "mi"

    fun toShown(lb: Double) = if (kg) lb * KG else lb
    fun fromShown(v: Double) = if (kg) v / KG else v
    fun distShown(mi: Double) = if (kg) mi * KM else mi
    fun distFromShown(v: Double) = if (kg) v / KM else v

    fun num(v: Double): String = if (v == Math.floor(v) && v < 1e7) v.toLong().toString() else "%.1f".format(v)
    /** Up to two decimals, so quarter pounds (63.25) and 1.25 plates show exactly; trailing zeros are dropped. */
    fun num2(v: Double): String = if (v == Math.floor(v) && v < 1e7) v.toLong().toString()
        else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
    fun weight(lb: Double?): String = if (lb == null) "" else num2((toShown(lb) * 100).roundToInt() / 100.0)
    fun weightU(lb: Double) = "${weight(lb)} $w"
    fun distance(mi: Double?): String = if (mi == null) "" else "%.2f".format(distShown(mi)).trimEnd('0').trimEnd('.')
    fun time(sec: Int?): String {
        if (sec == null) return ""
        return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    }

    /** "1:30" or "90" -> seconds. */
    fun parseTime(s: String): Int? {
        val p = s.trim().split(":").map { it.trim() }
        if (p.any { it.isEmpty() || it.toIntOrNull() == null }) return null
        return p.fold(0) { acc, x -> acc * 60 + x.toInt() }
    }

    fun metric(m: Stats.Metric, v: Double): String = when (m.unit) {
        "w" -> weightU(v)
        "reps" -> "${v.toInt()} reps"
        "s" -> time(v.toInt())
        else -> "${distance(v)} $dist"
    }

    fun set(t: ExType, s: DoneSet): String = when (t) {
        ExType.WEIGHT -> "${weight(s.weight ?: 0.0)}$w x ${s.reps ?: 0}"
        ExType.BODYWEIGHT -> "${s.reps ?: 0} reps"
        ExType.TIMED -> time(s.seconds ?: 0)
        ExType.CARDIO -> "${distance(s.distance ?: 0.0)} $dist · ${time(s.seconds ?: 0)}"
        ExType.WEIGHT_TIME -> "${weight(s.weight ?: 0.0)}$w · ${time(s.seconds ?: 0)}"
    }

    fun restLabel(sec: Int): String = when {
        sec <= 0 -> "OFF"
        sec % 60 == 0 -> "${sec / 60}min 0s"
        sec > 60 -> "${sec / 60}min ${sec % 60}s"
        else -> "${sec}s"
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    private val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFmt)
    fun shortDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(shortFmt)
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%dh %dmin".format(s / 3600, s / 60 % 60) else "%dmin %ds".format(s / 60, s % 60)
    }
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
