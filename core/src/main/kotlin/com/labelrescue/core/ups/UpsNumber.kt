package com.labelrescue.core.ups

/**
 * UPS "1Z" tracking numbers: 1Z + shipper (6) + service (2) + package (7) + check digit = 18.
 * Check digit: over the 15 characters after "1Z", letters count as (code - 3) mod 10 (A=2 ... I=0,
 * J=1 ...), every second character counts twice, and the check digit tops the sum up to a multiple of 10.
 */
data class UpsNumber(
    val tracking: String,
    val source: String,
    val corrected: Boolean = false,
    /** Set when the confidence was measured (e.g. several sticker reads agreeing). */
    val measuredConfidence: Double? = null,
) {
    val shipper: String get() = tracking.substring(2, 8)
    val serviceCode: String get() = tracking.substring(8, 10)
    /**
     * How sure the read is, 0..1. A barcode or MaxiCode decode has the symbol's own checks: 0.99.
     * Printed text that passes the check digit as read: 0.93 (the check digit catches about 9 in
     * 10 misreads). Printed text that needed a lookalike correction: 0.80.
     */
    val confidence: Double get() = measuredConfidence ?: when {
        source.contains("barcode", ignoreCase = true) || source.contains("MaxiCode") -> 0.99
        corrected -> 0.80
        else -> 0.93
    }

    /** Services UPS hands to USPS for the last mile. */
    val isUspsHandoff: Boolean get() = serviceCode in USPS_HANDOFF_SERVICES
    /** As UPS prints it: 1Z 5R8 939 03 5756 712 7. */
    val grouped: String get() = with(tracking) { "${substring(0, 2)} ${substring(2, 5)} ${substring(5, 8)} ${substring(8, 10)} ${substring(10, 14)} ${substring(14, 17)} ${substring(17)}" }

    companion object {
        /** YW = UPS Ground Saver / SurePost, delivered by USPS (seen on a real Ground Saver label). Other services still get looked up. */
        val USPS_HANDOFF_SERVICES = setOf("YW")

        private val PATTERN = Regex("1\\s*Z(?:\\s*[A-Z0-9]){16}")

        fun charValue(c: Char): Int = if (c in '0'..'9') c - '0' else (c.code - 3) % 10

        fun isValid(t: String): Boolean {
            if (t.length != 18 || !t.startsWith("1Z") || t.any { it !in '0'..'9' && it !in 'A'..'Z' } || t[17] !in '0'..'9') return false
            var sum = 0
            for (i in 0 until 15) sum += charValue(t[i + 2]) * (if (i % 2 == 1) 2 else 1)
            return (10 - sum % 10) % 10 == t[17] - '0'
        }

        /**
         * Valid 1Z numbers in decoded barcode text or OCR text (spaces allowed, as printed).
         *
         * Guards against inventing one from other digits, which a check digit alone can't (1 in
         * 10 random strings pass it): the "1Z" must start a token (not sit inside a longer run of
         * characters), and lines that look like a USPS number (20+ digits, hardly any letters) are
         * skipped. With [allowCorrection], one lookalike swap after the "1Z" is tried, a printed
         * "IZ"/"LZ" counts as "1Z", and so does "12" when the rest of the number has a letter.
         */
        fun find(text: String, source: String, allowCorrection: Boolean = false): List<UpsNumber> {
            val out = LinkedHashMap<String, UpsNumber>()
            for (line in text.uppercase().lines()) {
                val digits = line.count { it in '0'..'9' }
                val letters = line.count { it in 'A'..'Z' }
                if (digits >= 20 && letters <= 2) continue
                val prefix = if (allowCorrection) "[1IL]\\s*[Z2]" else "1\\s*Z"
                for (m in Regex("(?<![A-Z0-9])$prefix(?:\\s*[A-Z0-9]){16}(?![A-Z0-9])").findAll(line)) {
                    val raw = m.value.filter { !it.isWhitespace() }
                    val t = "1Z" + raw.substring(2)
                    // OCR reads the Z of "1Z" as 2 all the time. A "12" only counts when the rest has
                    // a letter (USPS numbers never do), so digits can't turn into a UPS number.
                    if (raw[1] == '2' && t.substring(2).none { it in 'A'..'Z' }) continue
                    val fixed = raw[0] != '1'
                    if (isValid(t)) { out.putIfAbsent(t, UpsNumber(t, source, corrected = fixed)); continue }
                    if (!allowCorrection) continue
                    // Real 1Z numbers are mostly digits (package number, check digit, service code).
                    if (t.substring(2).count { it in '0'..'9' } < 9) continue
                    corrected(t)?.let { out.putIfAbsent(it, UpsNumber(it, source, corrected = true)) }
                }
            }
            return out.values.toList()
        }

        /**
         * One OCR lookalike swap (O/0, I/1, S/5, B/8, Z/2, G/6) that makes the check digit valid,
         * if exactly one such swap does (two would be a guess).
         */
        private fun corrected(t: String): String? {
            val toDigit = mapOf('O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')
            val toLetter = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G')
            // Most likely first: a letter OCR'd where a digit belongs (package digits are numeric).
            // Then a digit OCR'd for a letter, only in the shipper and service part.
            val phases = listOf(
                (2 until 18).mapNotNull { i -> toDigit[t[i]]?.let { i to it } },
                (2 until 10).mapNotNull { i -> toLetter[t[i]]?.let { i to it } },
            )
            for (swaps in phases) {
                val hits = swaps.map { (i, c) -> t.substring(0, i) + c + t.substring(i + 1) }.filter { isValid(it) }.toSet()
                if (hits.size == 1) return hits.first()
                if (hits.size > 1) return null
            }
            return null
        }

        /** UPS MaxiCode payload ("[)>RS01GS96..."): the 1Z number, when the code carries it in full. */
        fun fromMaxiCode(payload: String): List<UpsNumber> = find(payload, "MaxiCode")
    }
}
