package com.ironlog.app

import android.content.Context
import org.json.JSONArray

/**
 * Strength standards for people who lift: one-rep max in lb (or reps for bodyweight moves) for
 * Beginner / Novice / Intermediate / Advanced / Elite = stronger than 5 / 20 / 50 / 80 / 95% of lifters,
 * every 10 lb of body weight. Built from published research and open data by tools/standards/build_standards.js
 * (sources listed there and in How ranks work) and kept in assets/standards.json.
 */
object StandardsData {
    class Std(
        val id: String, val lift: String,
        val male: List<Pair<Double, DoubleArray>>?, val female: List<Pair<Double, DoubleArray>>?,
        val reps: Boolean, val perDumbbell: Boolean, val note: String?,
        /** Multiplies the levels (1 for every current standard). */
        val factor: Double = 1.0,
        /** Why men's levels are only an estimate (null = published research on this exercise). */
        val approx: String? = null,
        /** Men's levels are a rough estimate from AI-generated databases (purple ★): only ranked in "All on + AI" mode. */
        val ai: Boolean = false,
        /** One table for everyone of a sex: body weight and age aren't used (grip dynamometer). */
        val flat: Boolean = false,
        /** One leg of a two-leg body-weight move: ranked on the two-leg table after converting your load. */
        val oneLeg: Boolean = false,
        /** The standard's full name (equipment and style), e.g. "Barbell Wrist Curl". */
        val liftName: String = lift,
        /** [approx], [ai] and [note] for women, which can differ from men's (e.g. research on one sex only). */
        val approxF: String? = approx,
        val aiF: Boolean = ai,
        val noteF: String? = note,
        /** V2: sets over this many reps count as this many for the estimated max (machine, cable and isolation lifts). */
        val repCap: Int = 20,
        /** Ranked on the longest hold in seconds (planks, hangs, wall sits). */
        val timed: Boolean = false,
    ) {
        fun approxFor(male: Boolean) = if (male) approx else approxF
        fun aiFor(male: Boolean) = if (male) ai else aiF
        fun noteFor(male: Boolean) = if (male) note else noteF
    }

    @Volatile private var normal: List<Std> = emptyList()
    /** Developer: the same standards with Gemini's numbers heavily favored (standards_gemini.json). */
    @Volatile private var gemini: List<Std> = emptyList()

    /** Developer: the V2 trial build (standards_v2.json). */
    @Volatile private var v2: List<Std> = emptyList()
    // PERSONAL-USE (removable): standards from non-commercial sources, kept entirely apart (tools/personal/README.md).
    @Volatile private var personal: List<Std> = emptyList()
    val personalCount get() = personal.size
    // PERSONAL-USE (removable): the app's standards from before the October 2026 purge (tools/personal/build_purged.py).
    @Volatile private var purged: List<Std> = emptyList()
    val purgedCount get() = purged.size

    /** The standards in use: normally standards.json (V1); the V2 or Gemini-favored set while that developer setting is on. */
    val all: List<Std> get() = Store.profile.value.let { p ->
        when {
            p.devPersonal -> personal // PERSONAL-USE (removable)
            p.devPurged -> purged // PERSONAL-USE (removable)
            p.devV2 && v2.isNotEmpty() -> v2
            p.devGemini && gemini.isNotEmpty() -> gemini
            else -> normal
        }
    }

    /** Reads the tables once; call before anything uses [Standards]. */
    fun init(c: Context) {
        if (normal.isNotEmpty()) return
        normal = load(c, "standards.json")
        gemini = load(c, "standards_gemini.json")
        v2 = load(c, "standards_v2.json")
        personal = load(c, "personal/standards_personal.json") // PERSONAL-USE (removable)
        purged = load(c, "personal/standards_purged.json") // PERSONAL-USE (removable)
    }

    private fun load(c: Context, file: String): List<Std> =
        runCatching {
            val a = JSONArray(c.assets.open(file).bufferedReader().readText())
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                fun rows(k: String) = o.optJSONArray(k)?.let { r ->
                    List(r.length()) { j ->
                        val row = r.getJSONArray(j)
                        row.getDouble(0) to DoubleArray(5) { row.getDouble(it + 1) }
                    }
                }
                fun str(k: String) = if (!o.has(k) || o.isNull(k)) null else o.getString(k)
                val approx = str("a"); val ai = o.optBoolean("ai", false); val note = str("note")
                Std(o.getString("i"), o.getString("lift"), rows("m"), rows("f"), o.getBoolean("r"), o.getBoolean("d"),
                    note, o.optDouble("k", 1.0), approx, ai, o.optBoolean("u", false), o.optBoolean("ul", false), str("ln") ?: o.getString("lift"),
                    if (o.has("af")) str("af") else approx, o.optBoolean("aif", ai), if (o.has("nf")) str("nf") else note, o.optInt("rc", 20), o.optBoolean("t", false))
            }
        }.getOrDefault(emptyList())
}
