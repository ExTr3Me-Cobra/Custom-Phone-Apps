package com.labelrescue.core.usps

/**
 * Reads printed label text (service banner, ZIP, "USPS TRACKING #") for context.
 *
 * The service-name → service-type-code table is a soft hint built from commonly seen numbers.
 * It only nudges the ranking among check-digit-valid candidates; it never removes one.
 * Confirmed history (learned per user) is a much stronger prior.
 */
object ServiceHints {

    data class Hint(val service: String, val serviceTypeCodes: Set<String>, val stcPrefixes: Set<String> = emptySet())

    private val TABLE = listOf(
        Regex("PRIORITY\\s*MAIL\\s*EXPRESS|EXPRESS\\s*MAIL") to
            Hint("Priority Mail Express", setOf("701", "702", "703", "721", "722", "723", "724", "725"), setOf("7")),
        Regex("PRIORITY\\s*MAIL|USPS\\s*PRIORITY") to
            Hint("Priority Mail", setOf("055", "056", "057", "058", "059", "361", "364", "365", "405", "406", "407", "408")),
        Regex("GROUND\\s*ADVANTAGE") to
            Hint("Ground Advantage", setOf("001", "002", "003", "004", "006", "007", "008", "021", "023", "300", "301", "346", "612", "748")),
        Regex("FIRST[-\\s]*CLASS") to
            Hint("First-Class", setOf("001", "002", "003", "021", "023", "071", "072", "073")),
        Regex("CERTIFIED") to
            Hint("Certified Mail", setOf("071", "072", "073", "077", "078", "117", "118", "119")),
        Regex("PARCEL\\s*SELECT") to Hint("Parcel Select", setOf("612", "748", "750", "754")),
        Regex("MEDIA\\s*MAIL") to Hint("Media Mail", setOf("751", "753", "764")),
        Regex("LIBRARY\\s*MAIL") to Hint("Library Mail", setOf("752", "765")),
    )

    /** Phrases matched loosely (OCR drops and confuses letters on damaged labels). */
    private val FUZZY = listOf(
        "PRIORITYMAILEXPRESS" to "Priority Mail Express",
        "GROUNDADVANTAGE" to "Ground Advantage",
        "PRIORITYMAIL" to "Priority Mail",
        "FIRSTCLASS" to "First-Class",
        "PARCELSELECT" to "Parcel Select",
        "MEDIAMAIL" to "Media Mail",
        "CERTIFIEDMAIL" to "Certified Mail",
        "LIBRARYMAIL" to "Library Mail",
    )

    /** The big service letter in the corner box of USPS labels. */
    private val LETTERS = mapOf("G" to "Ground Advantage", "P" to "Priority Mail", "E" to "Priority Mail Express", "F" to "First-Class")

    private fun byName(name: String) = TABLE.first { it.second.service == name }.second

    /**
     * The mail class the label shows: exact phrases, phrases with a few OCR errors
     * ("SPS GROUN) ADVANTAGE"), and the large service letter (G, P, E, F) on its own line.
     */
    fun serviceHints(labelText: String): List<Hint> {
        val t = labelText.uppercase()
        val found = LinkedHashSet<Hint>()
        TABLE.filter { it.first.containsMatchIn(t) }.forEach { found += it.second }
        val squeezed = t.filter { it in 'A'..'Z' }
        for ((phrase, name) in FUZZY) {
            if (found.any { it.service == name }) continue
            if (approxContains(squeezed, phrase, maxOf(1, phrase.length / 6))) found += byName(name)
        }
        if (found.isEmpty()) {
            for (line in t.lines()) LETTERS[line.trim()]?.let { found += byName(it) }
        }
        val list = found.toList()
        // "Priority Mail Express" also matches "Priority Mail"; keep only the most specific.
        return if (list.any { it.service == "Priority Mail Express" }) list.filter { it.service != "Priority Mail" } else list
    }

    /** How the service was recognized, for diagnostics. */
    fun explain(labelText: String): String = serviceHints(labelText).joinToString { it.service }.ifEmpty { "none found" }

    /** True if [p] occurs in [s] with at most [k] edits (approximate substring match). */
    fun approxContains(s: String, p: String, k: Int): Boolean {
        if (p.isEmpty()) return true
        var prev = IntArray(p.length + 1) { it }
        for (c in s) {
            val cur = IntArray(p.length + 1)
            cur[0] = 0
            for (j in 1..p.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (p[j - 1] == c) 0 else 1)
            }
            if (cur[p.length] <= k) return true
            prev = cur
        }
        return prev[p.length] <= k
    }

    /** ZIP codes printed on the label (5 digits, optional +4). The last one is usually the destination. */
    fun zips(labelText: String): List<String> =
        Regex("(?<![0-9])([0-9]{5})(?:[-\\s]([0-9]{4}))?(?![0-9])").findAll(labelText)
            .map { m -> m.groupValues[1] + m.groupValues[2] }
            .toList()
}
