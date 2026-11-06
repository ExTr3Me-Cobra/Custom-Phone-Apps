package com.clockout.app

import android.content.Context
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One clock ring: its two-letter code (BT, MV, OL, IL, ET…) and when it happened. */
data class Ring(val code: String, val time: LocalDateTime)

/** Reads clock rings out of the text the camera saw. */
object RingReader {
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    // 09-OCT-26 02.41.06 PM  (dots or colons, a little forgiving about spaces)
    // Digits the camera sometimes reads as letters (0→O, 1→I/l/|, 5→S, 8→B) are accepted and fixed.
    // A dash the camera dropped ("06-NOV26") or a missing dot between hours and minutes ("0849.11") is fine too.
    private val DATETIME = Regex(
        """([0-9OoIl|]{1,2})\s*-?\s*([A-Za-z]{3}|[A-Za-z0-9]{3}(?=\s*-))\s*-?\s*([0-9OoIl|]{2,4})\s+([0-9OoIl|]{1,2})(?:\s*[.:,;]\s*|\s+|)([0-9OoIl|SB]{2})(?:\s*[.:,;]\s*|\s+)([0-9OoIl|SB]{2})\s*([AaPp])\s*\.?\s*[MmNn]""",
    )

    // The same without AM/PM (it was lost or read somewhere else): worked out from the hour, see [guessHour].
    private val DATETIME_NO_AMPM = Regex(
        """([0-9OoIl|]{1,2})\s*-?\s*([A-Za-z]{3}|[A-Za-z0-9]{3}(?=\s*-))\s*-?\s*([0-9OoIl|]{2,4})\s+([0-9OoIl|]{1,2})(?:\s*[.:,;]\s*|\s+|)([0-9OoIl|SB]{2})(?:\s*[.:,;]\s*|\s+)([0-9OoIl|SB]{2})(?!\s*[0-9])""",
    )

    /** A 12-hour time with no AM/PM: 7–11 is the morning, 12 and 1–6 the afternoon (a day shift). */
    private fun guessHour(h: Int): Int = when (h) { 12 -> 12; in 1..6 -> h + 12; else -> h % 12 }

    private fun parseNoAmPm(m: MatchResult): LocalDateTime? = runCatching {
        val (d, mon, y, hh, mm, ss) = m.destructured
        val h = num(hh); if (h !in 1..12) return null
        val year = num(y).let { if (it < 100) 2000 + it else it }
        LocalDateTime.of(year, month(mon) ?: return null, num(d), guessHour(h), num(mm), num(ss))
    }.getOrNull()

    private fun num(s: String): Int = s.map {
        when (it) { 'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> it }
    }.joinToString("").toInt()

    // Military + decimal: 09-OCT-26 14.68  (14 hours + 0.68 of an hour). The date is optional.
    private val DECIMAL_DATED = Regex(
        """(\d{1,2})\s*-\s*([A-Za-z0-9]{3})\s*-\s*(\d{2,4})\s+(\d{1,2})\s*[.,]\s*(\d{1,4})(?![\d:.]|\s*[AaPp]\.?\s*[Mm])""",
    )
    private val DECIMAL_ALONE = Regex("""^(\d{1,2})\s*[.,]\s*(\d{1,4})$""")

    // Short form, code then time: "BT:08.87", "OL - 12.33", "IL 2:41:06 PM". Today's date.
    private val SHORT_DECIMAL = Regex("""\b([A-Za-z]{2})\s*[=:\-]?\s*(\d{1,2})\s*[.,]\s*(\d{1,4})(?![\d:.]|\s*[AaPp]\.?\s*[Mm])""")
    private val SHORT_12H = Regex(
        """\b([A-Za-z]{2})\s*[=:\-]?\s*(\d{1,2})\s*[.:]\s*(\d{2})(?:\s*[.:]\s*(\d{2}))?\s*([AaPp])\s*\.?\s*[Mm]\b""",
    )

    /** Rings written as code-then-time on one line, in the chosen style. */
    private fun shortForm(line: String, decimal: Boolean, day: LocalDate): List<Ring> =
        if (decimal) {
            SHORT_DECIMAL.findAll(line).mapNotNull { m ->
                decimalTime(m.groupValues[2], m.groupValues[3])?.let { Ring(m.groupValues[1].uppercase(), day.atTime(it)) }
            }.toList()
        } else {
            SHORT_12H.findAll(line).mapNotNull { m ->
                val h = m.groupValues[2].toInt()
                if (h !in 1..12) return@mapNotNull null
                val min = m.groupValues[3].toInt()
                val sec = m.groupValues[4].ifEmpty { "0" }.toInt()
                if (min > 59 || sec > 59) return@mapNotNull null
                val hour = h % 12 + if (m.groupValues[5].uppercase() == "P") 12 else 0
                Ring(m.groupValues[1].uppercase(), day.atTime(hour, min, sec))
            }.toList()
        }

    // 013 IL (or "012OL" when the space is lost)
    private val CODE = Regex("""\b[0-9OoIl]{3}\s*([A-Z01]{2})\b""")

    /** "0L" -> "OL", "1L" -> "IL": letters in a code misread as digits. */
    private fun code(raw: String) = raw.uppercase().replace('0', 'O').replace('1', 'I')

    // Lines from the details panel underneath ("Code: 013 IL Op Code …", "time: 09-OCT-26 …"),
    // which can belong to other people: ignored.
    private val PANEL = Regex("""(?i)(op\s*code|code\s*:|time\s*:|ime\s*:|employee|oyee)""")

    private class Found(val y: Float, val h: Float, val value: Any)

    /** Month letters that the camera sometimes reads as digits. */
    private fun month(raw: String): Int? {
        val m = raw.uppercase().replace('0', 'O').replace('1', 'I').replace('5', 'S')
        return MONTHS.indexOf(m).takeIf { it >= 0 }?.plus(1)
    }

    private fun parseTime(m: MatchResult): LocalDateTime? = runCatching {
        val (d, mon, y, hh, mm, ss, ap) = m.destructured
        var hour = num(hh) % 12
        if (ap.uppercase() == "P") hour += 12
        val year = num(y).let { if (it < 100) 2000 + it else it }
        LocalDateTime.of(year, month(mon) ?: return null, num(d), hour, num(mm), num(ss))
    }.getOrNull()

    /** Hours with a decimal fraction ("14", "68") as a time of day. */
    fun decimalTime(hours: String, fraction: String): LocalTime? {
        val h = hours.toIntOrNull() ?: return null
        if (h > 24) return null
        val secs = (("0.$fraction").toDouble() * 3600).roundToInt()
        val total = h * 3600 + secs
        if (total >= 24 * 3600) return null
        return LocalTime.ofSecondOfDay(total.toLong())
    }

    private fun parseDecimal(m: MatchResult): LocalDateTime? = runCatching {
        val (d, mon, y, hh, frac) = m.destructured
        val year = y.toInt().let { if (it < 100) 2000 + it else it }
        LocalDate.of(year, month(mon) ?: return null, d.toInt()).atTime(decimalTime(hh, frac) ?: return null)
    }.getOrNull()

    /** Times on one line of text, in the chosen format. Undated decimal times get [day]. */
    private fun timesIn(line: String, decimal: Boolean, day: LocalDate): List<LocalDateTime> {
        if (!decimal) {
            val full = DATETIME.findAll(line).mapNotNull { parseTime(it) }.toList()
            return full.ifEmpty { DATETIME_NO_AMPM.findAll(line).mapNotNull { parseNoAmPm(it) }.toList() }
        }
        val dated = DECIMAL_DATED.findAll(line).mapNotNull { parseDecimal(it) }.toList()
        if (dated.isNotEmpty()) return dated
        val rest = CODE.replace(line, "").trim()
        val m = DECIMAL_ALONE.find(rest) ?: return emptyList()
        return listOfNotNull(decimalTime(m.groupValues[1], m.groupValues[2])?.let { day.atTime(it) })
    }

    fun read(text: Text, decimal: Boolean): List<Ring> {
        val day = LocalDate.now()
        val times = mutableListOf<Found>()
        val codes = mutableListOf<Found>()
        val paired = mutableListOf<Ring>()
        // The details panel sits under the table, and its time lines often have no label of their own
        // (the photo cuts it off), so everything from the panel's first line down is left out.
        val panelTop = text.textBlocks.flatMap { it.lines }.filter { PANEL.containsMatchIn(it.text) }
            .mapNotNull { it.boundingBox?.let { b -> b.top - b.height() * 0.3f } }.minOrNull()
        for ((t, box) in joinedLines(text)) {
            if (PANEL.containsMatchIn(t)) continue
            if (panelTop != null && box.exactCenterY() > panelTop) continue
            val y = box.exactCenterY()
            val h = box.height().toFloat().coerceAtLeast(1f)
            val lineTimes = timesIn(t, decimal, day)
            val lineCodes = CODE.findAll(t).map { code(it.groupValues[1]) }.toList()
            // "BT:08.87" style: code and time together, no row numbers or dates.
            if (lineTimes.isEmpty() && lineCodes.isEmpty()) {
                val short = shortForm(t, decimal, day)
                if (short.isNotEmpty()) {
                    paired += short
                    continue
                }
            }
            // A whole row read as one line: "013 IL 09-OCT-26 02.41.06 PM".
            if (lineTimes.size == 1 && lineCodes.size == 1) {
                paired += Ring(lineCodes[0], lineTimes[0])
                continue
            }
            lineTimes.forEach { times += Found(y, h, it) }
            lineCodes.forEach { codes += Found(y, h, it) }
        }
        // Columns read separately: match each time with the code on the same row.
        val byRow = mutableListOf<Ring>()
        val used = HashSet<Found>()
        var missed = 0
        for (t in times.sortedBy { it.y }) {
            val best = codes.filter { it !in used }.minByOrNull { abs(it.y - t.y) }
            val code = if (best != null && abs(best.y - t.y) < t.h * 1.5f) {
                used += best
                best.value as String
            } else {
                missed++
                "?"
            }
            byRow += Ring(code, t.value as LocalDateTime)
        }
        // One time and one code left over: they belong together.
        val leftCodes = codes.filter { it !in used }
        if (missed == 1 && leftCodes.size == 1) {
            val i = byRow.indexOfFirst { it.code == "?" }
            byRow[i] = byRow[i].copy(code = leftCodes[0].value as String)
            missed = 0
        }
        // Rows didn't line up (a photo at an angle) but there are as many codes as times: pair them top to bottom.
        // Only then: if the camera missed a row, top-to-bottom would shift every code onto the wrong time.
        if (missed > 0 && times.isNotEmpty() && times.size == codes.size) {
            val ts = times.sortedBy { it.y }; val cs = codes.sortedBy { it.y }
            ts.indices.forEach { paired += Ring(cs[it].value as String, ts[it].value as LocalDateTime) }
        } else paired += byRow
        return paired.distinctBy { it.time }.sortedBy { it.time }
    }

    private val AMPM_ONLY = Regex("""^\s*([AaPp])\s*\.?\s*[Mm]\s*\.?\s*$""")

    /**
     * The lines the camera read, with an "AM" or "PM" that landed on a line of its own (the time column wraps)
     * put back on the end of the time just above it.
     */
    private fun joinedLines(text: Text): List<Pair<String, Rect>> {
        val lines = text.textBlocks.flatMap { it.lines }.map { (it.text) to (it.boundingBox ?: Rect()) }.toMutableList()
        val out = mutableListOf<Pair<String, Rect>>()
        val taken = HashSet<Int>()
        for ((i, l) in lines.withIndex()) {
            if (i in taken || AMPM_ONLY.matches(l.first)) continue
            var s = l.first
            // A time without AM/PM: find the AM/PM line just below it, roughly in the same column.
            if (Regex("""\d\s*[.:,]\s*\d{2}\s*$""").containsMatchIn(s)) {
                val b = l.second
                val below = lines.withIndex().filter { (j, o) ->
                    j != i && j !in taken && AMPM_ONLY.matches(o.first) &&
                        o.second.top >= b.top && o.second.top - b.bottom < b.height() * 1.6f &&
                        o.second.left < b.right && o.second.right > b.left - b.height()
                }.minByOrNull { it.value.second.top }
                if (below != null) { taken += below.index; s = "$s ${below.value.first.trim()}" }
            }
            out += s to l.second
        }
        return out
    }
}

/** Where the day stands. */
data class Result(
    /** Time on the clock up to now (or up to the last ring when off the clock). */
    val worked: Duration,
    /** Still to work to reach the target (zero when reached). */
    val remaining: Duration,
    /** When to clock out, if on the clock now. */
    val clockOut: LocalDateTime?,
    val onClock: Boolean,
    /** The day is finished (last ring is an end-of-day code). */
    val finished: Boolean,
)

object Calc {
    fun isOff(code: String, s: Settings) = code.uppercase() in s.offCodes

    fun compute(rings: List<Ring>, s: Settings, now: LocalDateTime): Result? {
        val sorted = rings.filter { it.code.uppercase() !in s.ignoreCodes }.sortedBy { it.time }
        if (sorted.isEmpty()) return null
        var worked = Duration.ZERO
        for (i in 0 until sorted.lastIndex) {
            if (!isOff(sorted[i].code, s)) worked += Duration.between(sorted[i].time, sorted[i + 1].time)
        }
        val last = sorted.last()
        val target = Duration.ofSeconds(s.targetSeconds)
        val leftAtLast = target - worked
        return if (!isOff(last.code, s)) {
            val clockOut = last.time + leftAtLast
            val soFar = worked + Duration.between(last.time, now).coerceAtLeast(Duration.ZERO)
            Result(soFar, (target - soFar).coerceAtLeast(Duration.ZERO), clockOut, onClock = true, finished = false)
        } else {
            Result(worked, leftAtLast.coerceAtLeast(Duration.ZERO), null, onClock = false, finished = last.code.uppercase() == "ET")
        }
    }
}

data class Settings(
    val targetSeconds: Long = 8 * 3600L,
    /** Codes that take you off the clock (lunch out, end of day…). */
    val offCodes: Set<String> = setOf("OL", "ET"),
    val remind: Boolean = true,
    val remindBefore: Int = 5,
    /** The clock shows military time with decimal hours (14.68) instead of 2:40:48 PM. */
    val decimal: Boolean = false,
    /** Codes that make no difference to time on the clock (job moves): left out entirely. */
    val ignoreCodes: Set<String> = setOf("MV"),
)

/** Saved rings and settings. */
object Store {
    private const val FILE = "clock_out"
    private val ringState = MutableStateFlow<List<Ring>>(emptyList())
    private val settingsState = MutableStateFlow(Settings())
    private var loaded = false
    val rings: StateFlow<List<Ring>> get() = ringState
    val settings: StateFlow<Settings> get() = settingsState

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        runCatching {
            val a = JSONArray(p.getString("rings", "[]"))
            ringState.value = List(a.length()) {
                val o = a.getJSONObject(it)
                Ring(o.getString("code"), LocalDateTime.parse(o.getString("time")))
            }
        }
        settingsState.value = Settings(
            targetSeconds = p.getLong("target", 8 * 3600L),
            offCodes = p.getString("off", "OL,ET")!!.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet(),
            remind = p.getBoolean("remind", true),
            remindBefore = p.getInt("remindBefore", 5),
            decimal = p.getBoolean("decimal", false),
            ignoreCodes = p.getString("ignore", "MV")!!.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    /** A new day has started: yesterday's rings no longer count. True if anything was cleared. */
    fun clearOldDays(c: Context): Boolean {
        val today = LocalDate.now()
        val kept = ringState.value.filter { !it.time.toLocalDate().isBefore(today) }
        if (kept.size == ringState.value.size) return false
        setRings(c, kept)
        clearPhotos(c)
        return true
    }

    /** Removes any saved pictures. */
    fun clearPhotos(c: Context) {
        runCatching { java.io.File(c.cacheDir, "photos").listFiles()?.forEach { it.delete() } }
    }

    fun setRings(c: Context, rings: List<Ring>) {
        ringState.value = rings.sortedBy { it.time }
        val a = JSONArray(ringState.value.map { JSONObject().put("code", it.code).put("time", it.time.toString()) })
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("rings", a.toString()).apply()
        Reminder.update(c)
    }

    fun setSettings(c: Context, s: Settings) {
        settingsState.value = s
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("target", s.targetSeconds).putString("off", s.offCodes.joinToString(","))
            .putBoolean("remind", s.remind).putInt("remindBefore", s.remindBefore)
            .putBoolean("decimal", s.decimal).putString("ignore", s.ignoreCodes.joinToString(",")).apply()
        Reminder.update(c)
    }
}

object Fmt {

    private val clock = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
    fun time(t: LocalDateTime): String = t.format(clock)
    fun time(t: LocalTime): String = t.format(clock)
    fun dur(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** Military time with decimal hours: 2:40:48 PM -> "14.68". */
    fun decimal(t: LocalTime): String {
        var h = t.hour
        var hundredths = ((t.minute * 60 + t.second) / 36.0).roundToInt()
        if (hundredths == 100) { h += 1; hundredths = 0 }
        return "%02d.%02d".format(h, hundredths)
    }

    /** A time the way the clock shows it, with the other style alongside in decimal mode. */
    fun show(t: LocalDateTime, s: Settings): String =
        if (s.decimal) "${decimal(t.toLocalTime())}  (${time(t)})" else time(t)

    /** A typed time in either style ("14.68", "2:41:06 PM", "14:41"). */
    fun parseAny(raw: String, s: Settings): LocalTime? {
        if (s.decimal) {
            Regex("""^\s*(\d{1,2})\s*[.,]\s*(\d{1,4})\s*$""").find(raw)?.let {
                return RingReader.decimalTime(it.groupValues[1], it.groupValues[2])
            }
        }
        return parseClock(raw)
    }

    /** "2:41:06 PM", "2.41.06 pm", "14:41:06"… Null if it can't be read. */
    fun parseClock(raw: String): LocalTime? {
        val m = Regex("""^\s*(\d{1,2})\s*[.:]\s*(\d{2})(?:\s*[.:]\s*(\d{2}))?\s*([AaPp])?\.?\s*[Mm]?\.?\s*$""").find(raw) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[4].uppercase()
        if (ap.isNotEmpty()) {
            if (h !in 1..12) return null
            h = h % 12 + if (ap == "P") 12 else 0
        }
        if (h > 23 || min > 59 || sec > 59) return null
        return LocalTime.of(h, min, sec)
    }

    fun epochMillis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun today(): LocalDate = LocalDate.now()
}
