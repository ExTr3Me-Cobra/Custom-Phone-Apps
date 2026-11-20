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

    fun serviceHints(labelText: String): List<Hint> {
        val t = labelText.uppercase()
        val found = TABLE.filter { it.first.containsMatchIn(t) }.map { it.second }
        // "Priority Mail Express" also matches "Priority Mail"; keep only the most specific.
        return if (found.any { it.service == "Priority Mail Express" }) found.filter { it.service != "Priority Mail" } else found
    }

    /** ZIP codes printed on the label (5 digits, optional +4). The last one is usually the destination. */
    fun zips(labelText: String): List<String> =
        Regex("(?<![0-9])([0-9]{5})(?:[-\\s]([0-9]{4}))?(?![0-9])").findAll(labelText)
            .map { m -> m.groupValues[1] + m.groupValues[2] }
            .toList()
}
