package com.ironlog.app

import org.json.JSONArray
import org.json.JSONObject

/** How an exercise is logged. */
enum class ExType(val label: String) {
    WEIGHT("Weight & reps"),
    BODYWEIGHT("Bodyweight (reps only)"),
    TIMED("Timed hold"),
    CARDIO("Cardio (distance & time)"),
    WEIGHT_TIME("Weight & time"),
}

data class Exercise(
    val id: String,
    val name: String,
    val equipment: String,
    val type: ExType,
    val primary: List<String>,
    val secondary: List<String>,
    val category: String = "strength",
    val instructions: List<String> = emptyList(),
    val custom: Boolean = false,
    /** Kept for old workouts but not offered in the list. */
    val hidden: Boolean = false,
    /** How hard each secondary muscle works compared with the primary (0..1). */
    val secondaryWeights: List<Float> = emptyList(),
    /** A built-in exercise you've changed. */
    val edited: Boolean = false,
    /** Individual muscles (e.g. "biceps (long head)") -> how hard each works, main = 1. */
    val detail: Map<String, Float> = emptyMap(),
    /** Studies the percentages are based on (keys of [Muscles.refs]). */
    val refs: List<String> = emptyList(),
) {
    /** Individual muscles worked, 0..1. Older saved exercises only have groups, so those are split the usual way. */
    val muscles: Map<String, Float> by lazy {
        Muscles.migrate(detail).ifEmpty { Muscles.expand(secondary.associateWith { secondaryWeight(it) } + primary.associateWith { 1f }) }
    }

    /** Secondary muscle -> weight (0.3 when not known). */
    fun secondaryWeight(m: String): Float {
        val i = secondary.indexOf(m)
        return secondaryWeights.getOrNull(i) ?: 0.3f
    }

    val mainMuscle: String get() = primary.firstOrNull()?.let(::titleCase) ?: "Other"

    fun toJson(): JSONObject = JSONObject().put("i", id).put("n", name).put("e", equipment).put("t", type.name)
        .put("p", JSONArray(primary)).put("s", JSONArray(secondary)).put("c", category).put("h", JSONArray(instructions))
        .put("w", JSONArray(secondary.map { secondaryWeight(it).toDouble() })).put("x", if (hidden) 1 else 0)
        .put("m", JSONObject().apply { detail.forEach { (k, v) -> put(k, v.toDouble()) } }).put("r", JSONArray(refs))

    companion object {
        fun fromJson(o: JSONObject, custom: Boolean): Exercise {
            // Grip used to be its own group; it's now the forearms' finger flexors. Old saved exercises are moved over.
            fun g(m: String) = if (m == "grip") "forearms" else m
            val primary = o.optJSONArray("p").strings().map(::g).distinct()
            val sec = o.optJSONArray("s").strings()
            val w = o.optJSONArray("w")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } } ?: emptyList()
            val merged = LinkedHashMap<String, Float>()
            sec.forEachIndexed { i, m -> val k = g(m); if (k !in primary) merged[k] = maxOf(merged[k] ?: 0f, w.getOrNull(i) ?: 0.3f) }
            return Exercise(
                id = o.getString("i"), name = o.getString("n"), equipment = o.optString("e", "Other"),
                type = runCatching { ExType.valueOf(o.getString("t")) }.getOrDefault(ExType.WEIGHT),
                primary = primary, secondary = merged.keys.toList(),
                category = o.optString("c", "strength"), instructions = o.optJSONArray("h").strings(), custom = custom,
                hidden = o.optInt("x", 0) == 1,
                secondaryWeights = if (w.isEmpty()) emptyList() else merged.values.toList(),
                detail = o.optJSONObject("m")?.let { m ->
                    m.keys().asSequence().associate { k -> (if (k == "grip (finger flexors)") "forearms (finger flexors)" else k) to m.getDouble(k).toFloat() }
                } ?: emptyMap(),
                refs = o.optJSONArray("r").strings(),
            )
        }
    }
}

fun titleCase(s: String) = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

/** A planned set in a routine. Weight is in pounds. */
data class PlanSet(
    val reps: Int? = null,
    val weight: Double? = null,
    val seconds: Int? = null,
    val distance: Double? = null,
    val failure: Boolean = false,
    /** A warm-up set: never counts toward ranks. */
    val warmup: Boolean = false,
)

data class RoutineItem(val exerciseId: String, val sets: List<PlanSet>, val restSec: Int = 90, val note: String = "")

data class Routine(val id: String, val name: String, val items: List<RoutineItem>)

/** A set in a workout. Weight in pounds (added weight for bodyweight exercises), distance in miles. */
data class DoneSet(
    val weight: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distance: Double? = null,
    val failure: Boolean = false,
    val done: Boolean = false,
    /** A warm-up set: never counts toward ranks. */
    val warmup: Boolean = false,
)

data class WorkoutItem(val exerciseId: String, val sets: List<DoneSet>, val restSec: Int = 90, val note: String = "")

data class Workout(
    val id: String,
    val routineId: String?,
    val name: String,
    /** When the timer started (0 = not started yet). */
    val start: Long,
    val end: Long? = null,
    val items: List<WorkoutItem>,
    /** Body weight at the time (for rankings). */
    val bodyweight: Double? = null,
    /** A 1 Rep Max test: its result sets your rank for that exercise. */
    val test: Boolean = false,
) {
    val doneSets get() = items.sumOf { i -> i.sets.count { it.done } }
}

data class Profile(
    val bodyweight: Double? = null,
    val heightIn: Int? = null,
    val age: Int? = null,
    val male: Boolean = true,
    val kg: Boolean = false,
    val apiKey: String = "",
    val imageModel: String = "gpt-image-1",
    val imageQuality: String = "medium",
    val defaultRest: Int = 90,
    /** Rest-over sound: "" = phone's default alarm sound, "none" = silent, else a sound's address. */
    val restSound: String = "",
    val restVibrate: Boolean = true,
    /** Ranks: "off", "accurate" (gold: research data that fits you), "all" (blue estimates too) or "ai" (also purple rough estimates from AI-generated databases). */
    val rankMode: String = "accurate",
    /** Developer settings unlocked (tap the build number 10 times). */
    val devUnlocked: Boolean = false,
    /** Developer: show the AI picture tools and picture packs. */
    val devAi: Boolean = false,
    /** Home stats cover "today", "week" (the last 7 days) or "all" time. */
    val homeRange: String = "week",
    /** Finished the first-run welcome (biometrics + intro). */
    val onboarded: Boolean = false,
    /** Where your data is kept: "" = not chosen yet, "local" = this phone only, "google" = also backed up to your Google account. */
    val cloud: String = "",
    /** Kept so saved profiles still load; not used (there are no general-population standards). */
    val compareAll: Boolean = false,
    /** Home: the two exercises you picked to stand for a muscle group (default: your top two). */
    val groupPicks: Map<String, List<String>> = emptyMap(),
    /** Exercise list order: "az", "muscle" or "mine" (in routines, then logged, then the rest). */
    val exerciseSort: String = "az",
    /** Home: a separate Grip rank (hands on the heatmap) from the Grip Dynamometer Test; off = grip is part of forearms. */
    val gripRank: Boolean = true,
    /** Panel order you set by holding and dragging: "detail.ranked", "detail.unranked" (exercise pages) and "rank" (rank screen). */
    val layouts: Map<String, List<String>> = emptyMap(),
    /** Extra animations (slides, press effects, 1 Rep Max effects, set rings). Off = plain screen changes. Rank pop-ups stay. */
    val extraAnims: Boolean = true,
    /** Developer: on 1 Rep Max tests, show the standard and the personal 1 Rep Max estimate above the next set. */
    val dev1rm: Boolean = false,
    /** Developer: rank with standards that heavily favor Gemini's database. */
    val devGemini: Boolean = false,
    /** Developer: rank with the V2 trial standards. */
    val devV2: Boolean = false,
    /** PERSONAL-USE (removable): rank with the non-commercial personal-use standards only. */
    val devPersonal: Boolean = false,
    /** PERSONAL-USE (removable): rank with the pre-purge standards only. */
    val devPurged: Boolean = false,
    /** Developer: show Research mode (a standing list of 1 Rep Max tests for improving the standards) under 1 Rep Max mode. */
    val devResearch: Boolean = false,
    /** Body fat picture you chose, 1 (leanest) to 5; null = not set (no adjustment). See [BodyFat]. */
    val bodyFat: Int? = null,
    /** Opening the app asks for the phone's fingerprint, face or passcode. */
    val appLock: Boolean = false,
    /** App accent colour (ARGB); the light blue by default. */
    val accent: Int = 0xFF2D8CFF.toInt(),
    /** Both-arm cable moves: how many stacks you use (1 or 2), by exercise id; missing = the exercise's usual number. */
    val stacks: Map<String, Int> = emptyMap(),
    /** Cable moves: the pulley ratio you use (2 = 2:1, the load you feel is half the stack), by exercise id; missing = 1:1. */
    val pulleys: Map<String, Int> = emptyMap(),
) {
    fun stacksFor(e: Exercise) = stacks[e.id]?.coerceIn(1, 2) ?: e.defaultStacks()
    fun withStacks(id: String, n: Int) = copy(stacks = stacks + (id to n))
    fun pulleyFor(e: Exercise) = pulleys[e.id]?.coerceIn(1, 4) ?: 1
    fun withPulley(id: String, n: Int) = copy(pulleys = if (n == 1) pulleys - id else pulleys + (id to n))

    /** Picks are remembered separately for each ranking mode (Accurate only / All on × Lifters / Everyone). */
    val pickMode get() = "$rankMode/${if (compareAll) "everyone" else "lifters"}|"

    /** Your muscle-group picks for the mode you're in now. */
    val picksNow: Map<String, List<String>>
        get() = groupPicks.filterKeys { it.startsWith(pickMode) }.mapKeys { it.key.removePrefix(pickMode) }

    fun withPicks(group: String, picks: List<String>): Profile {
        val all = groupPicks.toMutableMap()
        if (picks.isEmpty()) all.remove(pickMode + group) else all[pickMode + group] = picks
        return copy(groupPicks = all)
    }
}

// ---------- JSON ----------

private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
private fun JSONObject.putOpt2(k: String, v: Any?): JSONObject = if (v == null) this else put(k, v)

object Json {
    fun planSet(s: PlanSet) = JSONObject().putOpt2("r", s.reps).putOpt2("w", s.weight).putOpt2("t", s.seconds)
        .putOpt2("d", s.distance).put("f", s.failure).apply { if (s.warmup) put("wu", true) }
    fun planSet(o: JSONObject) = PlanSet(o.optIntOrNull("r"), o.optDoubleOrNull("w"), o.optIntOrNull("t"), o.optDoubleOrNull("d"), o.optBoolean("f"), o.optBoolean("wu"))

    fun routine(r: Routine) = JSONObject().put("id", r.id).put("name", r.name).put("items", JSONArray(r.items.map { i ->
        JSONObject().put("ex", i.exerciseId).put("rest", i.restSec).put("note", i.note).put("sets", JSONArray(i.sets.map(::planSet)))
    }))
    fun routine(o: JSONObject) = Routine(o.getString("id"), o.getString("name"), o.getJSONArray("items").objs().map { i ->
        RoutineItem(i.getString("ex"), i.getJSONArray("sets").objs().map(::planSet), i.optInt("rest", 90), i.optString("note"))
    })

    fun doneSet(s: DoneSet) = JSONObject().putOpt2("w", s.weight).putOpt2("r", s.reps).putOpt2("t", s.seconds)
        .putOpt2("d", s.distance).put("f", s.failure).put("x", s.done).apply { if (s.warmup) put("wu", true) }
    fun doneSet(o: JSONObject) = DoneSet(o.optDoubleOrNull("w"), o.optIntOrNull("r"), o.optIntOrNull("t"), o.optDoubleOrNull("d"), o.optBoolean("f"), o.optBoolean("x"), o.optBoolean("wu"))

    fun workout(w: Workout) = JSONObject().put("id", w.id).putOpt2("routine", w.routineId).put("name", w.name)
        .put("start", w.start).putOpt2("end", w.end).putOpt2("bw", w.bodyweight).apply { if (w.test) put("test", true) }
        .put("items", JSONArray(w.items.map { i ->
            JSONObject().put("ex", i.exerciseId).put("rest", i.restSec).put("note", i.note).put("sets", JSONArray(i.sets.map(::doneSet)))
        }))
    fun workout(o: JSONObject) = Workout(
        o.getString("id"), if (o.has("routine")) o.getString("routine") else null, o.getString("name"),
        time(o, "start")!!, time(o, "end"),
        o.getJSONArray("items").objs().map { i ->
            WorkoutItem(i.getString("ex"), i.getJSONArray("sets").objs().map(::doneSet), i.optInt("rest", 90), i.optString("note"))
        },
        o.optDoubleOrNull("bw"),
        o.optBoolean("test"),
    )

    /** A time: milliseconds, or (in imports) a local date-time like "2026-10-12T20:12" read in the phone's time zone. */
    private fun time(o: JSONObject, k: String): Long? {
        if (!o.has(k) || o.isNull(k)) return null
        val v = o.get(k)
        return if (v is Number) v.toLong()
        else java.time.LocalDateTime.parse(v.toString()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun profile(p: Profile) = JSONObject().putOpt2("bw", p.bodyweight).putOpt2("h", p.heightIn).putOpt2("age", p.age)
        .put("male", p.male).put("kg", p.kg).put("model", p.imageModel) // the API key is kept apart (never backed up)
        .put("quality", p.imageQuality).put("rest", p.defaultRest)
        .put("restSound", p.restSound).put("restVibrate", p.restVibrate).put("rankMode", p.rankMode)
        .put("dev", p.devUnlocked).put("devAi", p.devAi).put("range", p.homeRange).put("onb", p.onboarded).put("bk", p.cloud).put("cmp", p.compareAll).put("sort", p.exerciseSort).put("grip", p.gripRank)
        .put("gp", JSONObject(p.groupPicks.mapValues { JSONArray(it.value) } as Map<*, *>))
        .put("xa", p.extraAnims).put("d1rm", p.dev1rm).put("dgem", p.devGemini).put("dv2", p.devV2).put("dper", p.devPersonal).put("dpg", p.devPurged).put("dres", p.devResearch).putOpt2("bf", p.bodyFat).put("lock", p.appLock).put("acc", p.accent)
        .put("lay", JSONObject(p.layouts.mapValues { JSONArray(it.value) } as Map<*, *>))
        .put("stk", JSONObject(p.stacks as Map<*, *>))
        .put("pul", JSONObject(p.pulleys as Map<*, *>))
    fun profile(o: JSONObject) = Profile(
        o.optDoubleOrNull("bw"), o.optIntOrNull("h"), o.optIntOrNull("age"), o.optBoolean("male", true),
        o.optBoolean("kg"), o.optString("key"), o.optString("model", "gpt-image-1").ifBlank { "gpt-image-1" },
        o.optString("quality", "medium"), o.optInt("rest", 90),
        o.optString("restSound", ""), o.optBoolean("restVibrate", true),
        o.optString("rankMode", "accurate").ifBlank { "accurate" },
        o.optBoolean("dev"), o.optBoolean("devAi"), o.optString("range", if (o.optBoolean("homeAll")) "all" else "week"), o.optBoolean("onb"), o.optString("bk"), false, // "Everyone" mode was removed
        groupPicks = o.optJSONObject("gp")?.let { g -> g.keys().asSequence().associateWith { k -> g.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } } } ?: emptyMap(),
        exerciseSort = o.optString("sort", "az").ifBlank { "az" },
        gripRank = o.optBoolean("grip", true),
        extraAnims = o.optBoolean("xa", true),
        dev1rm = o.optBoolean("d1rm"),
        devGemini = o.optBoolean("dgem"),
        devV2 = o.optBoolean("dv2"),
        devPersonal = o.optBoolean("dper"),
        devPurged = o.optBoolean("dpg"),
        devResearch = o.optBoolean("dres"),
        bodyFat = o.optIntOrNull("bf")?.takeIf { it in 1..5 },
        appLock = o.optBoolean("lock"),
        accent = o.optInt("acc", 0xFF2D8CFF.toInt()),
        layouts = o.optJSONObject("lay")?.let { g -> g.keys().asSequence().associateWith { k -> g.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } } } ?: emptyMap(),
        stacks = o.optJSONObject("stk")?.let { g -> g.keys().asSequence().associateWith { k -> g.getInt(k) } } ?: emptyMap(),
        pulleys = o.optJSONObject("pul")?.let { g -> g.keys().asSequence().associateWith { k -> g.getInt(k) } } ?: emptyMap(),
    )

    fun JSONArray.objs(): List<JSONObject> = List(length()) { getJSONObject(it) }
}

private val ONE_SIDE = Regex("one[- ]arm|single[- ]arm|one[- ]hand|single[- ]hand|alternat|concentration|one[- ]leg|single[- ]leg")
/** Cable moves usually done on two pulleys, a handle in each hand on its own stack. */
private val TWO_STACK = Regex("crossover|cable fl|cross over|iron cross|overhead cable curl|rear delt fly")

/** A both-arm cable move you can do on one stack or with a handle on each of two stacks (not pulldowns, rows, crunches). */
fun Exercise.hasStackChoice(): Boolean {
    val n = name.lowercase()
    return equipment == "Cable" && !ONE_SIDE.containsMatchIn(n) && !Regex("pulldown|row|crunch|pull through|shrug").containsMatchIn(n)
}

/** How many stacks the exercise's standard assumes. */
fun Exercise.defaultStacks(): Int = if (TWO_STACK.containsMatchIn(name.lowercase())) 2 else 1

/** What to put in the weight box for this exercise: one dumbbell / one arm, or the total. */
fun Exercise.weightHint(): String {
    val n = name.lowercase()
    val oneSide = ONE_SIDE.containsMatchIn(n)
    val held2Hands = Regex("pullover|goblet|halo|pirate ship|plie dumbbell squat|seated triceps press|single dumbbell|standing dumbbell triceps extension|" +
        "two-arm dumbbell triceps extension|kettlebell overhead triceps|vertical swing|kettlebell swing|plate raise|plate twist|plate curl").containsMatchIn(n)
    // Leg exercises talk about legs, everything else about arms.
    val pulleyNote = " If the pulley isn't 1:1 (e.g. 2:1), tap Pulley to pick its ratio."
    val legs = primary.firstOrNull() in setOf("quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors", "shins", "hip flexors")
    val limb = if (legs) "leg" else "arm"
    val limbs = if (legs) "both legs together" else "both arms together"
    return when {
        type == ExType.BODYWEIGHT -> "No weight to log – reps only (you're lifting your body weight)."
        type == ExType.TIMED -> "No weight to log – just the time."
        type == ExType.CARDIO -> "No weight to log – distance and time."
        type == ExType.WEIGHT_TIME -> when {
            Regex("sled|drag").containsMatchIn(n) -> "Log the weight loaded on the sled, plus how long you went."
            Regex("farmer|suitcase|fat grip|bottoms-up|pinch carry").containsMatchIn(n) ->
                "Log the weight in one hand (one dumbbell, kettlebell or handle), plus how long you held or carried it."
            else -> "Log the total weight (bar or frame plus plates), plus how long you held or carried it."
        }
        n.contains("weighted vest") -> "Log only the vest's weight – not your body weight."
        n.startsWith("weighted") && (n.contains("pull") || n.contains("dip") || n.contains("chin")) ->
            "Log only the extra weight you add (belt, vest or dumbbell) – not your body weight."
        equipment == "Barbell" || equipment == "EZ Bar" -> when {
            n.contains("smith") -> "Log the total plates on both sides (Smith bars vary; add the bar's weight if it's marked)."
            oneSide -> "Log the total on the bar (bar + plates), even though you work one $limb at a time."
            else -> "Log the total weight: the bar plus all plates on both sides (an Olympic bar is 45 lb / 20 kg; EZ bars are often 15–25 lb)."
        }
        equipment == "Dumbbell" -> when {
            held2Hands -> "Log the weight of the one dumbbell you hold with both hands."
            oneSide -> if (legs) "Log the weight of ONE dumbbell (the one you hold), even though you work one leg at a time."
                else "Log the weight of the one dumbbell in your working hand."
            else -> "Log the weight of ONE dumbbell, not both added together (two 30 lb dumbbells → 30)."
        }
        equipment == "Kettlebell" -> when {
            held2Hands -> "Log the weight of the one kettlebell you hold with both hands."
            Regex("two|double").containsMatchIn(n) -> "Log the weight of ONE kettlebell, not both added together."
            else -> "Log the weight of the kettlebell."
        }
        equipment == "Cable" -> (when {
            oneSide -> "Log the stack weight on the one ${if (legs) "ankle strap or handle" else "handle"} you're using (one $limb)."
            hasStackChoice() && defaultStacks() == 2 -> "Log the weight on ONE stack (each hand's stack), not both added together. Both handles on one stack? Pick 1 stack."
            hasStackChoice() -> "Log the stack weight (both hands on one handle or bar – the number the pin is in). A handle on each of two stacks? Log one stack's weight and pick 2 stacks."
            else -> "Log the stack weight (both hands on one handle or bar – the number the pin is in)."
        }) + pulleyNote
        equipment == "Machine" -> when {
            oneSide -> "Log the weight for the one $limb you're working."
            Regex("leverage|leg press|hack squat|t-bar|sled|plate").containsMatchIn(n) ->
                "Log the total plates loaded on both sides (not counting the machine's own weight), $limbs."
            else -> "Log the stack weight ($limbs – the number the pin is in)."
        }
        equipment == "Bands" -> "Log the band's resistance if it's marked, otherwise leave the weight blank."
        equipment == "Medicine Ball" || equipment == "Exercise Ball" -> "Log the weight of the ball."
        else -> "Log the total weight you move ($limbs)."
    }
}
