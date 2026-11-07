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
        val value: (Progress) -> Double,
    )

    /** One badge: a family's tier. */
    class Badge(val family: Family, val tier: Int) {
        val id get() = "${family.id}_${tier + 1}"
        val target get() = family.targets[tier]
        val name get() = if (family.targets.size == 1) family.name else "${family.name} ${roman(tier + 1)}"
        val requirement get() = family.requirement.replace("{n}", fmt(target))
    }

    fun roman(n: Int): String = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X").getOrElse(n) { n.toString() }
    fun fmt(v: Double): String = if (v >= 10_000) "%,d".format(v.toLong()) else if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

    private fun t(vararg v: Number) = v.map { it.toDouble() }

    val families: List<Family> = listOf(
        // ---- Showing up ----
        Family("workouts", "Iron Habit", "🏋️", "Showing up", t(1, 10, 25, 50, 100, 250, 500, 1000), "workouts",
            "Finish {n} workouts.", "a sturdy iron dumbbell crossed with a calendar page") { it.workouts.toDouble() },
        Family("streak", "Steady Rhythm", "🔥", "Showing up", t(2, 4, 8, 12, 26, 52), "weeks in a row",
            "Train at least twice a week for {n} weeks in a row.", "a flame shaped like a metronome") { it.bestStreak.toDouble() },
        Family("weeks", "Clocked In", "📅", "Showing up", t(10, 25, 50, 100, 200), "weeks",
            "Train in {n} different weeks.", "a punch-clock card stamped with a barbell") { it.activeWeeks.toDouble() },
        Family("months", "Month Maker", "🗓️", "Showing up", t(1, 3, 6, 12), "months",
            "Have {n} months with 8 or more workouts.", "a crescent moon wrapped in a lifting belt") { it.bigMonths.toDouble() },
        Family("comeback", "Welcome Back", "👋", "Showing up", t(1, 3), "comebacks",
            "Come back to training after 2+ weeks off ({n} times).", "a phoenix rising from a weight plate") { it.comebacks.toDouble() },
        Family("hours", "Time Under Iron", "⏳", "Showing up", t(10, 25, 50, 100, 250, 500), "hours",
            "Spend {n} hours working out.", "an hourglass with a barbell for its middle") { it.hours },
        Family("early", "Early Bird", "🌅", "Showing up", t(1, 10, 50), "workouts",
            "Start {n} workouts before 7 AM.", "a rooster on a kettlebell at sunrise") { it.early.toDouble() },
        Family("night", "Night Owl", "🦉", "Showing up", t(1, 10, 50), "workouts",
            "Start {n} workouts after 9 PM.", "an owl perched on a barbell under a moon") { it.night.toDouble() },
        Family("long", "Marathon Session", "🕰️", "Showing up", t(1, 10), "workouts",
            "Finish {n} workouts that last 90 minutes or more.", "a long winding road leading to a dumbbell") { it.long.toDouble() },
        Family("weekend", "Weekend Warrior", "⚔️", "Showing up", t(10, 50), "workouts",
            "Train on {n} weekend days.", "a shield with crossed swords made of dumbbells") { it.weekend.toDouble() },
        Family("anyday", "Any Day", "🗂️", "Showing up", t(7), "days of the week",
            "Train on every day of the week at least once.", "a seven-segment wheel with a dumbbell hub") { it.weekdays.toDouble() },
        Family("double", "Double Day", "✌️", "Showing up", t(1), "days",
            "Do two workouts on the same day.", "two overlapping suns over a barbell") { it.doubleDays.toDouble() },
        Family("newyear", "Fresh Start", "🎆", "Showing up", t(1), "workouts",
            "Work out on New Year's Day.", "fireworks bursting over a weight plate") { it.newYear.toDouble() },

        // ---- Hard work ----
        Family("volume", "Mountain Mover", "⛰️", "Hard work", t(10, 50, 100, 250, 500, 1000, 2500), "× your body weight",
            "Lift a total of {n} times your body weight (all your sets added up).", "a mountain being lifted on a barbell") { it.volumeXbw },
        Family("reps", "Rep Machine", "⚙️", "Hard work", t(1000, 5000, 10000, 25000, 50000, 100000), "reps",
            "Do {n} reps in total.", "interlocking gears with a dumbbell in the centre") { it.reps.toDouble() },
        Family("sets", "Set Collector", "🧱", "Hard work", t(100, 500, 1000, 2500, 5000), "sets",
            "Finish {n} sets in total.", "a neat stack of bricks shaped like a weight stack") { it.sets.toDouble() },
        Family("heavyday", "Heavy Day", "🐘", "Hard work", t(5, 10, 20), "× your body weight",
            "Lift {n} times your body weight in a single workout.", "an elephant standing on a weight plate") { it.bestDayXbw },
        Family("failure", "To the Edge", "🧗", "Hard work", t(1, 10, 50, 100), "failure sets",
            "Take {n} sets to failure (marked F).", "a climber gripping the edge of a cliff") { it.failure.toDouble() },
        Family("holds", "Hold Steady", "🧘", "Hard work", t(5, 30, 60, 180), "minutes",
            "Hold timed exercises for {n} minutes in total.", "a balanced stone stack on a yoga mat") { it.holdMinutes },
        Family("minute", "One Minute Wonder", "⏱️", "Hard work", t(60, 120, 180), "seconds",
            "Hold a single timed set for {n} seconds.", "a stopwatch held up by a flexed arm") { it.bestHold.toDouble() },
        Family("century", "Century", "💯", "Hard work", t(1), "workouts",
            "Do exactly 100 reps in one workout.", "a laurel wreath around a ring of one hundred small beads") { it.century.toDouble() },
        Family("warmup", "Warmed Up", "♨️", "Hard work", t(10, 100, 500), "warm-up sets",
            "Do {n} warm-up sets (marked W).", "a steaming kettle shaped like a kettlebell") { it.warmups.toDouble() },

        // ---- Getting stronger (you vs. you) ----
        Family("bests", "Record Breaker", "👑", "Getting stronger", t(1, 10, 25, 50, 100, 250), "new bests",
            "Set {n} new personal bests (a set that beats everything you'd done on that exercise before).", "a crown resting on a broken chain") { it.bests.toDouble() },
        Family("bestex", "Many Fronts", "🎯", "Getting stronger", t(5, 15, 30), "exercises",
            "Set a new personal best on {n} different exercises.", "a target with several arrows in the bullseye") { it.bestExercises.toDouble() },
        Family("growth", "Growth Spurt", "🌱", "Getting stronger", t(10, 25, 50, 100), "% stronger",
            "Raise your estimated 1-rep max on any exercise {n}% above your first session of it.", "a sapling growing out of a weight plate") { it.bestGrowth },

        // ---- Ranks (climbing, never a set tier) ----
        Family("ranked", "Ranked", "⭐", "Ranks", t(1, 5, 10, 25), "ranked exercises", "Get a rank on {n} exercises.",
            "a star badge pinned on a ribbon", timeline = false) { it.rankedCount.toDouble() },
        Family("rankups", "On the Rise", "📈", "Ranks", t(1, 5, 10, 25, 50), "tiers climbed",
            "Climb {n} rank tiers in total (counted from each exercise's first rank).", "an arrow shooting up a staircase", timeline = false) { it.tiersClimbed.toDouble() },
        Family("climber", "Climber", "🧗‍♂️", "Ranks", t(3, 6, 9), "tiers",
            "Climb {n} rank tiers on one exercise.", "a flag planted on a mountain peak", timeline = false) { it.bestClimb.toDouble() },
        Family("groups", "Full House", "🏠", "Ranks", t(3, 6, 10), "muscle groups",
            "Get a rank in {n} muscle groups.", "a house built from different coloured muscle shapes", timeline = false) { it.rankedGroups.toDouble() },

        // ---- Well-rounded ----
        Family("explorer", "Explorer", "🧭", "Well-rounded", t(5, 10, 25, 50, 100), "exercises",
            "Do {n} different exercises.", "a compass with a dumbbell needle") { it.exercises.toDouble() },
        Family("totalbody", "Total Body", "🧍", "Well-rounded", t(1, 10), "weeks",
            "Train every main muscle group in one week ({n} weeks).", "a glowing human silhouette with every muscle lit") { it.fullWeeks.toDouble() },
        Family("toolbox", "Toolbox", "🧰", "Well-rounded", t(3, 5, 7), "kinds of equipment",
            "Use {n} different kinds of equipment (barbell, dumbbell, cable, machine…).", "an open toolbox full of gym equipment") { it.equipment.toDouble() },

        // ---- Testing ----
        Family("tests", "Tested", "🧪", "Testing", t(1, 5, 10, 25), "1 Rep Max tests",
            "Do {n} 1 Rep Max tests.", "a glowing test tube with a barbell inside") { it.tests.toDouble() },
        Family("proven", "Proven", "🏅", "Testing", t(1, 5, 10), "retests beaten",
            "Beat your previous 1 Rep Max test on an exercise ({n} times).", "a medal stamped with an upward arrow") { it.testBeats.toDouble() },
        Family("grip", "Grip Check", "✊", "Testing", t(1), "tests",
            "Do a Grip Dynamometer Test.", "a clenched fist squeezing a hand dynamometer") { it.grip.toDouble() },

        // ---- Habits ----
        Family("planner", "Planner", "📝", "Habits", t(1, 5, 10), "routines",
            "Make {n} routines.", "a clipboard with a training plan", timeline = false) { it.routines.toDouble() },
        Family("finisher", "Finisher", "🏁", "Habits", t(1, 10, 50, 100), "routine workouts",
            "Finish {n} workouts started from a routine.", "a chequered finish flag over a barbell") { it.routineWorkouts.toDouble() },
        Family("habit", "Creature of Habit", "🔁", "Habits", t(10, 25, 50), "times",
            "Do the same routine {n} times.", "a looping arrow around a dumbbell") { it.sameRoutine.toDouble() },
        Family("notes", "Note Taker", "🗒️", "Habits", t(5, 25, 100), "workouts",
            "Write notes in {n} workouts.", "a pencil writing in a training journal") { it.notes.toDouble() },
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
        val best = HashMap<String, Double>(); val first = HashMap<String, Double>(); var bestGrowth = 0.0
        val seen = HashSet<String>(); val exercises get() = seen.size
        val gear = HashSet<String>(); val equipment get() = gear.size
        var tests = 0; var testBeats = 0; var grip = 0; val lastTest = HashMap<String, Double>()
        var routineWorkouts = 0; val perRoutine = HashMap<String, Int>(); val sameRoutine get() = perRoutine.values.maxOrNull() ?: 0
        var notes = 0
        // Worked out from your ranks today (not workout by workout).
        var rankedCount = 0; var tiersClimbed = 0; var bestClimb = 0; var rankedGroups = 0; var routines = 0
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
            ex?.primary?.forEach { g -> if (g in mainGroups) p.weekGroups.getOrPut(wk) { HashSet() } += g }
            for (s in sets) {
                if (s.warmup) { p.warmups++; continue }
                p.sets++
                if (s.failure) p.failure++
                val r = s.reps ?: 0
                p.reps += r; dayReps += r
                if (ex?.type != ExType.BODYWEIGHT) { val v = (s.weight ?: 0.0) * r; p.volumeLb += v; dayVolume += v }
                s.seconds?.let { sec -> if (ex?.type == ExType.TIMED || ex?.type == ExType.WEIGHT_TIME) { p.holdSeconds += sec; p.bestHold = maxOf(p.bestHold, sec) } }
            }
            if (Standards.isFlat(id) && w.test) p.grip++
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
                if (ex?.type == ExType.WEIGHT) {
                    val f = p.first.getOrPut(id) { value }
                    if (f > 0) p.bestGrowth = maxOf(p.bestGrowth, (value / f - 1) * 100)
                }
                if (w.test) {
                    p.lastTest[id]?.let { if (value > it + 0.01) p.testBeats++ }
                    p.lastTest[id] = value
                }
            }
        }
        if (dayReps == 100) p.century++
        p.bestDayXbw = maxOf(p.bestDayXbw, dayVolume / p.bodyweight)
        if (wk !in p.fullDone && p.weekGroups[wk]?.containsAll(mainGroups) == true) { p.fullDone += wk; p.fullWeeks++ }
    }

    /** Rank badges: from your ranks as they are now. */
    private fun addRanks(p: Progress, workouts: List<Workout>, profile: Profile) {
        if (Standards.mode(profile) == Standards.Mode.OFF) return
        val ranks = Ranks.all(workouts, profile)
        p.rankedCount = ranks.size
        p.rankedGroups = Ranks.groupRanks(ranks, profile.picksNow).size
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
