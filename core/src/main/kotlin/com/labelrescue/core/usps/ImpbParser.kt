package com.labelrescue.core.usps

/** The parts of a USPS number. Fields that don't apply are null. */
data class ParsedNumber(
    val raw: String,
    val format: TrackingFormat,
    /** Destination ZIP from a "420" routing prefix (5 or 9 digits). */
    val zip: String?,
    /** The tracking number without any routing prefix. */
    val tracking: String,
    /** Channel application identifier: 92, 93, 94 or 95 (IMpb). */
    val channel: String?,
    /** 3-digit service type code (IMpb). */
    val serviceType: String?,
    /** Mailer ID: 9 digits if it starts with 9, otherwise 6. */
    val mailerId: String?,
    val serial: String,
    val checkDigit: Char,
    val checkValid: Boolean,
    /** Things that look wrong but don't fail the check digit (e.g. unknown channel). */
    val warnings: List<String>,
) {
    val isImpb: Boolean get() = channel != null
}

object ImpbParser {

    val CHANNELS = setOf("92", "93", "94", "95")

    /** Splits [number] (digits, or S10 letters + digits) into its parts, or null if no format fits. */
    fun parse(number: String): ParsedNumber? {
        val s = normalize(number)
        if (s.length == 13 && s[0].isLetter()) return parseS10(s)
        if (s.any { it !in '0'..'9' }) return null
        return when (s.length) {
            20 -> parseTracking(s, TrackingFormat.IMPB_20, null, s)
            22 -> parseTracking(s, TrackingFormat.IMPB_22, null, s)
            26 -> parseTracking(s, TrackingFormat.IMPB_26, null, s)
            30 -> if (s.startsWith("420")) parseTracking(s, TrackingFormat.ROUTED_30, s.substring(3, 8), s.substring(8)) else null
            34 -> if (!s.startsWith("420")) null else {
                val zip9 = parseTracking(s, TrackingFormat.ROUTED_34_ZIP9, s.substring(3, 12), s.substring(12))
                val zip5 = parseTracking(s, TrackingFormat.ROUTED_34_ZIP5, s.substring(3, 8), s.substring(8))
                listOf(zip9, zip5).maxByOrNull { (if (it.checkValid) 2 else 0) + (if (it.channel in CHANNELS) 1 else 0) }
            }
            else -> null
        }
    }

    /** Uppercases and removes spaces, dashes and similar separators. */
    fun normalize(number: String): String =
        number.uppercase().filter { it.isLetterOrDigit() || it == '?' }

    /** Formats a full string can have, given its length and the start (useful for patterns with "?"). */
    fun formatFor(s: String): TrackingFormat? {
        val hasLetters = s.any { it in 'A'..'Z' }
        val options = TrackingFormat.forLength(s.length, hasLetters)
        if (options.size <= 1) return options.firstOrNull()
        // 34 digits: ZIP+4 + 22, or ZIP5 + 26. Prefer the layout where a channel 92-95 shows up.
        val zip9Channel = s.substring(12, 14)
        val zip5Channel = s.substring(8, 10)
        return when {
            zip9Channel.matchesChannel() && !zip5Channel.matchesChannel() -> TrackingFormat.ROUTED_34_ZIP9
            zip5Channel.matchesChannel() && !zip9Channel.matchesChannel() -> TrackingFormat.ROUTED_34_ZIP5
            else -> TrackingFormat.ROUTED_34_ZIP9
        }
    }

    private fun String.matchesChannel(): Boolean =
        (this[0] == '9' || this[0] == '?') && (this[1] in '2'..'5' || this[1] == '?')

    private fun parseTracking(raw: String, format: TrackingFormat, zip: String?, t: String): ParsedNumber {
        val warnings = mutableListOf<String>()
        val check = t.last()
        val valid = CheckDigits.isValidMod10(t)
        var channel: String? = null
        var stc: String? = null
        var mid: String?
        val serial: String
        if (format.trackingFormat == TrackingFormat.IMPB_20) {
            // Legacy 20-digit: 2-digit service code, 9-digit mailer ID, 8-digit serial, check digit.
            stc = t.substring(0, 2)
            mid = t.substring(2, 11)
            serial = t.substring(11, 19)
        } else {
            channel = t.substring(0, 2)
            if (channel !in CHANNELS) warnings += "Channel $channel is not 92, 93, 94 or 95"
            stc = t.substring(2, 5)
            val midLen = if (t[5] == '9') 9 else 6
            mid = t.substring(5, 5 + midLen)
            serial = t.substring(5 + midLen, t.length - 1)
        }
        if (zip != null && raw.substring(0, 3) != "420") warnings += "Routing prefix is not 420"
        return ParsedNumber(raw, format, zip, t, channel, stc, mid, serial, check, valid, warnings)
    }

    private fun parseS10(s: String): ParsedNumber? {
        if (s.substring(2, 11).any { it !in '0'..'9' }) return null
        val warnings = mutableListOf<String>()
        if (s.substring(11) != "US") warnings += "Country code is ${s.substring(11)}, not US"
        return ParsedNumber(
            s, TrackingFormat.S10, null, s, null, s.substring(0, 2), null,
            s.substring(2, 10), s[10], CheckDigits.isValidS10(s), warnings,
        )
    }

    /**
     * Finds USPS numbers inside decoded barcode text (GS1 strings with FNC1/GS separators,
     * DataMatrix payloads, etc.). Returns tracking numbers whose check digit is valid,
     * along with the ZIP if a 420 prefix was found.
     */
    fun extractFromPayload(payload: String): List<ParsedNumber> {
        val out = LinkedHashMap<String, ParsedNumber>()
        // Split on GS (0x1D), FNC1 markers and anything that isn't a digit or letter.
        val clean = payload.replace("\u001D", " ").replace("]C1", " ").replace("]d2", " ")
        val tokens = clean.split(Regex("[^0-9A-Za-z]+")).filter { it.isNotEmpty() }
        var pendingZip: String? = null
        for (tok in tokens) {
            val t = tok.uppercase()
            if (t.length == 13 && CheckDigits.isValidS10(t)) { parse(t)?.let { out[it.tracking] = it }; continue }
            if (t.any { it !in '0'..'9' }) continue
            if (t.startsWith("420") && (t.length == 8 || t.length == 12)) { pendingZip = t.substring(3); continue }
            val direct = parse(t)
            if (direct != null && direct.checkValid) {
                out[direct.tracking] = if (direct.zip == null && pendingZip != null) direct.copy(zip = pendingZip) else direct
                continue
            }
            // Search any digit run for embedded tracking numbers.
            for (len in intArrayOf(34, 30, 26, 22, 20)) {
                if (t.length < len) continue
                for (start in 0..t.length - len) {
                    val sub = t.substring(start, start + len)
                    val p = parse(sub) ?: continue
                    if (p.checkValid && (p.channel in CHANNELS || len >= 30)) out.putIfAbsent(p.tracking, p)
                }
            }
        }
        return out.values.toList()
    }
}
