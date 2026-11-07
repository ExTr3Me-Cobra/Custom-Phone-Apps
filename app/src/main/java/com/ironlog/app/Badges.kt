package com.ironlog.app

import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import org.json.JSONObject

/**
 * Badges: things anyone can earn by showing up, working hard and beating their own numbers. Nothing compares you
 * with other people or asks for a particular weight: strength badges are about beating your own earlier numbers, and
 * rank badges are about climbing, never about reaching a given tier.
 *
 * Badges come in families ("Iron Habit I…VIII"): the same thing with higher targets. Each family has one picture;
 * the tier shows as the ring's colour and the Roman numeral.
 */
object Badges {
    /** A family of badges: [targets] are the tiers (I, II, III…), [unit] how progress reads ("workouts"). */
    class Family(
        val id: String, val name: String, val emoji: String, val category: String,
        val targets: List<Double>, val unit: String,
        /** "Finish {n} workouts": {n} is the tier's target. */
        val requirement: String,
        /** What the picture shows (for the AI picture prompt). */
        val art: String,
        /** Worked out from your history as it was after each workout (so tiers get the date you earned them). */
        val timeline: Boolean = true,
        /** How a target or value reads (default: the number). */
        val show: ((Double) -> String)? = null,
        val value: (Progress) -> Double,
    )

    /** One badge: a family's tier. */
    class Badge(val family: Family, val tier: Int) {
        val id get() = "${family.id}_${tier + 1}"
        val target get() = family.targets[tier]
        val name get() = if (family.targets.size == 1) family.name else "${family.name} ${roman(tier + 1)}"
        val requirement get() = family.requirement.replace("{n}", family.label(target))
    }

    fun Family.label(v: Double): String = show?.invoke(v) ?: fmt(v)

    fun roman(n: Int): String = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X").getOrElse(n) { n.toString() }
    fun fmt(v: Double): String = if (v >= 10_000) "%,d".format(v.toLong()) else if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

    private fun t(vararg v: Number) = v.map { it.toDouble() }

    val families: List<Family> = listOf(
        // ---- Showing up ----
        Family("workouts", "Iron Habit", "🏋️", "Showing up", t(50, 100, 150, 200, 250, 300), "workouts",
            "Finish {n} workouts.", "a sturdy iron dumbbell crossed with a calendar page") { it.workouts.toDouble() },
        Family("streak", "Steady Rhythm", "🔥", "Showing up", t(13, 26, 39, 52), "weeks in a row",
            "Train at least once a week for {n} weeks in a row. One missed week is forgiven; a second resets the streak.",
            "a flame shaped like a metronome") { it.bestWeekly.toDouble() },
        Family("hours", "Time Under Iron", "⏳", "Showing up", t(50, 100, 150, 200, 250, 300, 350), "hours",
            "Spend {n} hours working out.", "an hourglass with a barbell for its middle") { it.hours },

        // ---- Hard work ----
        Family("volume", "Mountain Mover", "⛰️", "Hard work", t(400000, 800000, 1200000, 1600000, 2000000), "lbs",
            "Lift a total of {n} lbs (all your sets added up: weight × reps).", "a mountain being lifted on a barbell") { it.volumeLb },
        Family("reps", "Rep Machine", "⚙️", "Hard work", t(10000, 20000, 30000, 40000, 50000), "reps",
            "Do {n} reps in total.", "interlocking gears with a dumbbell in the centre") { it.reps.toDouble() },
        Family("ownbody", "Own Body", "🤸", "Hard work", t(2000, 4000, 6000, 8000, 10000), "reps",
            "Do {n} reps of bodyweight exercises.", "a gymnast holding a handstand") { it.bodyReps.toDouble() },
        Family("sets", "Set Collector", "🧱", "Hard work", t(800, 1600, 2400, 3200, 4000), "sets",
            "Finish {n} sets in total.", "a neat stack of bricks shaped like a weight stack") { it.sets.toDouble() },
        Family("failure", "To the Edge", "🧗", "Hard work", t(250, 500, 750, 1000, 1250), "failure sets",
            "Take {n} sets to failure (marked F).", "a climber gripping the edge of a cliff") { it.failure.toDouble() },
        Family("holds", "Hold Steady", "🧘", "Hard work", t(150, 300, 450, 600, 750), "minutes",
            "Hold timed exercises for {n} minutes in total.", "a balanced stone stack on a yoga mat") { it.holdMinutes },
        Family("distance", "Distance", "🏃", "Hard work", t(500, 1000, 1500, 2000, 2500), "miles",
            "Cover {n} miles in cardio.", "a running shoe leaving a long trail") { it.miles },

        // ---- Getting stronger ----
        Family("bests", "Record Breaker", "👑", "Getting stronger", t(100, 200, 300, 400, 500, 600, 700), "new bests",
            "Set {n} new personal bests (a set that beats everything you'd done on that exercise before).", "a crown resting on a broken chain") { it.bests.toDouble() },
        Family("overload", "Overload", "📊", "Getting stronger", t(100, 200, 300, 400, 500, 600), "times",
            "Beat last time's total volume (weight × reps) on an exercise ({n} times).", "a bar chart climbing step by step") { it.overloads.toDouble() },
        Family("bwclub", "Body Weight Club", "⚖️", "Getting stronger", t(1, 1.5, 2, 2.5), "× your body weight",
            "Do a working set with {n} times your body weight on any exercise.", "a balance scale with a lifter on one side and a weight on the other") { it.bestXbw },

        // ---- Muscle focus (the exercise's main muscle group) ----
        Family("legday", "Never Skip Leg Day", "🦵", "Muscle focus", t(40, 80, 120, 160, 200), "leg workouts",
            "Train your legs (quads, hamstrings, glutes or calves) in {n} workouts.", "a powerful pair of legs in a deep squat") { it.legDays.toDouble() },
        Family("core", "Core Strength", "🎽", "Muscle focus", t(40, 80, 120, 160, 200), "core workouts",
            "Train your abs or obliques in {n} workouts.", "a shield shaped like a six-pack") { it.coreDays.toDouble() },
        Family("chest", "Chest Day", "🛡️", "Muscle focus", t(40, 80, 120, 160, 200), "chest workouts",
            "Train your chest in {n} workouts.", "a broad armoured chest plate") { it.chestDays.toDouble() },
        Family("arms", "Arm Day", "💪", "Muscle focus", t(40, 80, 120, 160, 200), "arm workouts",
            "Train your arms or shoulders (biceps, triceps, forearms or shoulders) in {n} workouts.", "a flexed arm with a bulging bicep") { it.armDays.toDouble() },
        Family("back", "Back Day", "🦅", "Muscle focus", t(40, 80, 120, 160, 200), "back workouts",
            "Train your back (lats, middle or lower back, or traps) in {n} workouts.", "a broad back with wing-like lats spreading out") { it.backDays.toDouble() },

        Family("cardio", "Cardio Day", "❤️", "Muscle focus", t(40, 80, 120, 160, 200), "cardio workouts",
            "Do cardio in {n} workouts.", "a glowing heart with a pulse line running through it") { it.cardioDays.toDouble() },

        // ---- Testing ----
        Family("tests", "Tested", "🧪", "Testing", t(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), "1 Rep Max tests",
            "Do {n} 1 Rep Max tests.", "a glowing test tube with a barbell inside") { it.tests.toDouble() },
    )

    val all: List<Badge> = families.flatMap { f -> f.targets.indices.map { Badge(f, it) } }

    /** Everything the badges count, built up workout by workout. */
    class Progress {
        var workouts = 0; var sets = 0; var reps = 0L; var failure = 0; var warmups = 0
        var volumeLb = 0.0; var bodyweight = 170.0
        val volumeXbw get() = volumeLb / bodyweight
        var bestDayXbw = 0.0
        var hours = 0.0; var holdSeconds = 0L; var bestHold = 0
        val holdMinutes get() = holdSeconds / 60.0
        var early = 0; var night = 0; var long = 0; var weekend = 0; var century = 0; var newYear = 0
        val days = HashSet<DayOfWeek>(); val weekdays get() = days.size
        val dates = HashMap<LocalDate, Int>(); val doubleDays get() = dates.count { it.value >= 2 }
        var comebacks = 0; var lastDate: LocalDate? = null
        val weekCounts = HashMap<Long, Int>(); var bestStreak = 0; val activeWeeks get() = weekCounts.size
        val monthCounts = HashMap<Int, Int>(); val bigMonths get() = monthCounts.count { it.value >= 8 }
        val weekGroups = HashMap<Long, MutableSet<String>>(); var fullWeeks = 0; val fullDone = HashSet<Long>()
        var bests = 0; val bestEx = HashSet<String>(); val bestExercises get() = bestEx.size
        val best = HashMap<String, Double>(); val first = HashMap<String, Double>(); val firstAt = HashMap<String, Long>(); var bestGrowth = 0.0
        val seen = HashSet<String>(); val exercises get() = seen.size
        val gear = HashSet<String>(); val equipment get() = gear.size
        var tests = 0; var testBeats = 0; var grip = 0; val lastTest = HashMap<String, Double>()
        var routineWorkouts = 0; val perRoutine = HashMap<String, Int>(); val sameRoutine get() = perRoutine.values.maxOrNull() ?: 0
        var notes = 0
        val perfectMonths get() = monthCounts.count { it.value >= 12 }
        val yearCounts = HashMap<Int, Int>(); val bestYear get() = yearCounts.values.maxOrNull() ?: 0
        var legDays = 0; var coreDays = 0; var bestXbw = 0.0; var overloads = 0; val routineVolume = HashMap<String, Double>()
        var hot = 0; var bestHot = 0; var bodyReps = 0L; var miles = 0.0
        var chestDays = 0; var armDays = 0; var backDays = 0; var cardioDays = 0
        /** Steady Rhythm: weeks in a row with a workout, one missed week forgiven (the spare comes back after a reset). */
        var weekly = 0; var bestWeekly = 0; var spare = 1; var lastWeek: LocalDate? = null
        // Worked out from your ranks today (not workout by workout).
        var rankedCount = 0; var tiersClimbed = 0; var bestClimb = 0; var rankedGroups = 0; var routines = 0
        /** The lowest tier among the main muscle groups (Tier ordinal), or -1 while any is unranked. */
        var lowestGroupTier = -1
    }

    private val zone get() = ZoneId.systemDefault()
    private fun weekKey(d: LocalDate) = d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** The main muscle groups for Total Body (the ones on Home). */
    private val mainGroups get() = Muscles.groups.keys.filter { it !in Ranks.NOT_ON_HOME }.toSet()

    /** One finished workout added to [p]. */
    private fun add(p: Progress, w: Workout) {
        val start = Instant.ofEpochMilli(w.start).atZone(zone)
        val date = start.toLocalDate()
        p.lastDate?.let { if (java.time.temporal.ChronoUnit.DAYS.between(it, date) >= 14) p.comebacks++ }
        p.lastDate = date
        p.workouts++
        p.dates[date] = (p.dates[date] ?: 0) + 1
        p.days += date.dayOfWeek
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) p.weekend++
        if (date.monthValue == 1 && date.dayOfMonth == 1) p.newYear++
        if (start.hour < 7) p.early++
        if (start.hour >= 21) p.night++
        val mins = ((w.end ?: w.start) - w.start) / 60_000.0
        p.hours += mins / 60
        if (mins >= 90) p.long++
        val wk = weekKey(date)
        p.weekCounts[wk] = (p.weekCounts[wk] ?: 0) + 1
        p.yearCounts[date.year] = (p.yearCounts[date.year] ?: 0) + 1
        val bestsBefore = p.bests
        val groupsToday = HashSet<String>()
        val mk = date.year * 100 + date.monthValue
        p.monthCounts[mk] = (p.monthCounts[mk] ?: 0) + 1
        // Weeks in a row with 2+ workouts, ending this week.
        var streak = 0; var d = date
        while ((p.weekCounts[weekKey(d)] ?: 0) >= 2) { streak++; d = d.minusWeeks(1) }
        p.bestStreak = maxOf(p.bestStreak, streak)
        if (w.items.any { it.note.isNotBlank() }) p.notes++
        w.routineId?.let { r -> p.routineWorkouts++; p.perRoutine[r] = (p.perRoutine[r] ?: 0) + 1 }
        if (w.test) p.tests++
        var dayVolume = 0.0; var dayReps = 0
        for (id in w.items.map { it.exerciseId }.distinct()) {
            val ex = Store.exercise(id)
            val sets = w.items.filter { it.exerciseId == id }.flatMap { it.sets }.filter { it.done }
            if (sets.isEmpty()) continue
            p.seen += id
            ex?.equipment?.takeIf { it != "Other" && it != "Foam Roll" }?.let { p.gear += it }
            ex?.primary?.forEach { g -> groupsToday += g; if (g in mainGroups) p.weekGroups.getOrPut(wk) { HashSet() } += g }
            for (s in sets) {
                if (s.warmup) { p.warmups++; continue }
                p.sets++
                if (s.failure) p.failure++
                val r = s.reps ?: 0
                p.reps += r; dayReps += r
                if (ex?.type != ExType.BODYWEIGHT) { val v = (s.weight ?: 0.0) * r; p.volumeLb += v; dayVolume += v } else p.bodyReps += r
                if (ex?.type == ExType.WEIGHT && r > 0) p.bestXbw = maxOf(p.bestXbw, (s.weight ?: 0.0) / p.bodyweight)
                s.distance?.let { p.miles += it }
                s.seconds?.let { sec -> if (ex?.type == ExType.TIMED || ex?.type == ExType.WEIGHT_TIME) { p.holdSeconds += sec; p.bestHold = maxOf(p.bestHold, sec) } }
            }
            if (Standards.isFlat(id) && w.test) p.grip++
            // Overload: this exercise's volume beat the last session of it.
            if (ex?.type == ExType.WEIGHT && !w.test) {
                val vol = sets.filter { !it.warmup }.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) }
                p.routineVolume[id]?.let { if (vol > it + 0.01) p.overloads++ }
                if (vol > 0) p.routineVolume[id] = vol
            }
            // Personal bests: this session's best set against everything before it (not the first time you do it).
            val work = sets.filter { !it.warmup }
            val value = when (ex?.type) {
                ExType.BODYWEIGHT -> work.maxOfOrNull { (it.reps ?: 0).toDouble() } ?: 0.0
                ExType.TIMED -> work.maxOfOrNull { (it.seconds ?: 0).toDouble() } ?: 0.0
                ExType.CARDIO -> work.maxOfOrNull { it.distance ?: 0.0 } ?: 0.0
                else -> work.maxOfOrNull { Stats.e1rm(it.weight, it.reps) } ?: 0.0
            }
            if (value > 0) {
                val before = p.best[id]
                if (before != null && value > before + 0.01) { p.bests++; p.bestEx += id }
                p.best[id] = maxOf(before ?: 0.0, value)
                // Growth: your first month on an exercise is the starting point (first entries are often learning weights).
                if (ex?.type == ExType.WEIGHT) {
                    val since = p.firstAt.getOrPut(id) { w.start }
                    if (w.start - since <= 30L * 86_400_000) p.first[id] = maxOf(p.first[id] ?: 0.0, value)
                    else p.first[id]?.takeIf { it > 0 }?.let { f -> p.bestGrowth = maxOf(p.bestGrowth, (value / f - 1) * 100) }
                }
                if (w.test) {
                    p.lastTest[id]?.let { if (value > it + 0.01) p.testBeats++ }
                    p.lastTest[id] = value
                }
            }
        }
        if (dayReps == 100) p.century++
        if (groupsToday.any { it in setOf("quadriceps", "hamstrings", "glutes", "calves") }) p.legDays++
        if ("abdominals" in groupsToday) p.coreDays++
        if ("chest" in groupsToday) p.chestDays++
        if (w.items.any { i -> Store.exercise(i.exerciseId)?.type == ExType.CARDIO && i.sets.any { it.done } }) p.cardioDays++
        if (groupsToday.any { it in setOf("biceps", "triceps", "forearms", "shoulders") }) p.armDays++
        if (groupsToday.any { it in setOf("lats", "middle back", "lower back", "traps") }) p.backDays++
        // Steady Rhythm: count this week once; weeks missed since the last one use the spare, then reset the streak.
        val monday = date.with(DayOfWeek.MONDAY)
        val lw = p.lastWeek
        if (lw == null) { p.weekly = 1; p.spare = 1 }
        else if (monday != lw) {
            val missed = java.time.temporal.ChronoUnit.WEEKS.between(lw, monday) - 1
            repeat(missed.toInt().coerceAtLeast(0)) { if (p.spare > 0) p.spare-- else { p.weekly = 0; p.spare = 1 } }
            p.weekly++
        }
        p.lastWeek = monday
        p.bestWeekly = maxOf(p.bestWeekly, p.weekly)
        if (p.bests > bestsBefore) { p.hot++; p.bestHot = maxOf(p.bestHot, p.hot) } else p.hot = 0
        p.bestDayXbw = maxOf(p.bestDayXbw, dayVolume / p.bodyweight)
        if (wk !in p.fullDone && (p.weekGroups[wk]?.size ?: 0) >= minOf(10, mainGroups.size)) { p.fullDone += wk; p.fullWeeks++ }
    }

    /** Rank badges: from your ranks as they are now. */
    private fun addRanks(p: Progress, workouts: List<Workout>, profile: Profile) {
        if (Standards.mode(profile) == Standards.Mode.OFF) return
        val ranks = Ranks.all(workouts, profile)
        p.rankedCount = ranks.size
        val groupRanks = Ranks.groupRanks(ranks, profile.picksNow)
        p.rankedGroups = groupRanks.size
        p.lowestGroupTier = if (mainGroups.all { it in groupRanks }) mainGroups.minOf { Tier.of(groupRanks.getValue(it).first).ordinal } else -1
        // Tiers climbed: each exercise's tier now against its tier the first time it was ranked.
        for ((id, r) in ranks) {
            val firstW = workouts.filter { it.end != null && it.items.any { i -> i.exerciseId == id } }.minByOrNull { it.start } ?: continue
            val sets = firstW.items.filter { it.exerciseId == id }.flatMap { it.sets }
            val v = if (firstW.test) Standards.testValue(id, sets) else Standards.bestValue(id, sets)
            val pct = Standards.percentile(id, v, profile) ?: continue
            val climb = (r.tier.ordinal - Tier.of(pct).ordinal).coerceAtLeast(0)
            p.tiersClimbed += climb
            p.bestClimb = maxOf(p.bestClimb, climb)
        }
        p.routines = Store.routines.value.size
    }

    class State(val progress: Progress, val earned: Map<String, Long>)

    private fun file() = Store.readFile(FILE)
    private const val FILE = "badges.json"

    /**
     * Every badge's progress, and when each was earned: history badges get the date of the workout that earned them;
     * rank and routine badges get the day they were first noticed (remembered in badges.json).
     */
    fun compute(workouts: List<Workout>, profile: Profile): State {
        val done = workouts.filter { it.end != null }.sortedBy { it.start }
        val p = Progress().apply { bodyweight = profile.bodyweight?.takeIf { it > 50 } ?: 170.0 }
        val earned = LinkedHashMap<String, Long>()
        val timeline = all.filter { it.family.timeline }
        for (w in done) {
            add(p, w)
            for (b in timeline) if (b.id !in earned && b.family.value(p) >= b.target) earned[b.id] = w.end ?: w.start
        }
        addRanks(p, done, profile)
        val saved = runCatching { JSONObject(file() ?: "{}") }.getOrDefault(JSONObject())
        var changed = false
        for (b in all.filter { !it.family.timeline }) {
            if (b.family.value(p) >= b.target) {
                val at = saved.optLong(b.id, 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { saved.put(b.id, it); changed = true }
                earned[b.id] = at
            }
        }
        if (changed) Store.writeFile(FILE, saved.toString())
        return State(p, earned)
    }

    /** Every family's progress as plain text (Export on the Badges screen), to share and fine-tune the targets. */
    fun report(s: State): String {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val done = s.progress.workouts
        val sb = StringBuilder("Loadout badges: ${s.earned.size} of ${all.size} earned · $done workouts logged\n")
        val days = Store.workouts.value.filter { it.end != null }.minOfOrNull { it.start }
            ?.let { (System.currentTimeMillis() - it) / 86_400_000L }
        days?.let { sb.append("Training for $it days\n") }
        for ((cat, fams) in families.groupBy { it.category }) {
            sb.append("\n== $cat ==\n")
            for (f in fams) {
                val v = f.value(s.progress)
                sb.append("${f.name} [${f.id}]: ${f.label(v)} ${f.unit}\n")
                f.targets.indices.forEach { i ->
                    val b = Badge(f, i)
                    sb.append("   ${if (b.id in s.earned) "✓" else "·"} ${b.name} – ${f.label(b.target)}")
                    s.earned[b.id]?.let { sb.append(" (earned ${d.format(java.util.Date(it))})") }
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** Badges earned by this workout (shown on its Summary). */
    fun earnedBy(w: Workout, s: State): List<Badge> {
        val end = w.end ?: return emptyList()
        return all.filter { b -> s.earned[b.id]?.let { it in (end - 1000)..(end + 10 * 60_000) } == true }
    }

    // ---- Pictures (one per family) ----

    /** The picture prompt for a badge: your edited one if you changed it, else the default. */
    fun prompt(b: Badge): String = runCatching { JSONObject(Store.readFile("badge_prompts.json") ?: "{}").optString(b.id) }.getOrNull()
        ?.ifBlank { null } ?: defaultPrompt(b)

    fun setPrompt(b: Badge, text: String) {
        val o = runCatching { JSONObject(Store.readFile("badge_prompts.json") ?: "{}") }.getOrDefault(JSONObject())
        if (text.isBlank() || text == defaultPrompt(b)) o.remove(b.id) else o.put(b.id, text)
        Store.writeFile("badge_prompts.json", o.toString())
    }

    /** How grand each level looks, from a plain bronze medal up to a radiant legendary emblem. */
    private val LOOKS = listOf(
        "Level 1 of 8: a simple, plain bronze medallion with a thin rim and minimal detail",
        "Level 2 of 8: a polished silver medallion with a raised decorative rim and a little engraved detail",
        "Level 3 of 8: a shining gold medallion framed by a laurel wreath, with finer engraving",
        "Level 4 of 8: a platinum medallion with teal enamel, small inset gems around the rim and an ornate frame",
        "Level 5 of 8: a brilliant blue diamond-crystal emblem with faceted gems and small wings spreading from the sides",
        "Level 6 of 8: a rose-gold champion emblem with pink gems, a crown on top, wings and a soft glow",
        "Level 7 of 8: a legendary deep-purple emblem with violet gems, large ornate wings, a crown and a cosmic, starry aura",
        "Level 8 of 8: the ultimate radiant white-gold emblem, intricately detailed, with a halo of light, majestic wings, a jewelled crown and sparkling rays",
    )

    /** This badge's look: its level spread over the eight looks (a family with fewer levels still ends grand). */
    fun look(b: Badge): String {
        val n = b.family.targets.size
        val i = if (n == 1) 2 else Math.round(b.tier * (LOOKS.size - 1) / (n - 1).toFloat())
        return LOOKS[i]
    }

    fun defaultPrompt(b: Badge) =
        "A single achievement badge icon for a weightlifting app, centred and seen straight on, filling most of the square. " +
            "The emblem shows ${b.family.art}. ${look(b)}; higher levels look clearly more impressive and detailed than lower ones, " +
            "keeping the same central design. Style: premium mobile game achievement, glossy metal and enamel, crisp bevelled edges, " +
            "soft studio lighting, bold simple silhouette that reads clearly at small sizes. Fully transparent background, " +
            "no shadow under it, no text, no letters, no numbers, no watermark."

    /** A badge's own picture (badges/<family>_<level>.png); without one, its family's picture is used. */
    fun pictureFile(id: String) = File(Store.dataDir(), "badges/$id.png")
}
