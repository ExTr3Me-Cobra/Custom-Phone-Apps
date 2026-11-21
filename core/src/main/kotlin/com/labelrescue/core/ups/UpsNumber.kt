package com.labelrescue.core.ups

/**
 * UPS "1Z" tracking numbers: 1Z + shipper (6) + service (2) + package (7) + check digit = 18.
 * Check digit: over the 15 characters after "1Z", letters count as (code - 3) mod 10 (A=2 ... I=0,
 * J=1 ...), every second character counts twice, and the check digit tops the sum up to a multiple of 10.
 */
data class UpsNumber(val tracking: String, val source: String, val corrected: Boolean = false) {
    val shipper: String get() = tracking.substring(2, 8)
    val serviceCode: String get() = tracking.substring(8, 10)
    /**
     * How sure the read is, 0..1. A barcode or MaxiCode decode has the symbol's own checks: 0.99.
     * Printed text that passes the check digit as read: 0.93 (the check digit catches about 9 in
     * 10 misreads). Printed text that needed a lookalike correction: 0.80.
     */
    val confidence: Double get() = when {
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

        /** Valid 1Z numbers in decoded barcode text or OCR text (spaces allowed, as printed). */
        fun find(text: String, source: String, allowCorrection: Boolean = false): List<UpsNumber> {
            val out = LinkedHashMap<String, UpsNumber>()
            val up = text.uppercase()
            for (m in PATTERN.findAll(up)) {
                val t = m.value.filter { !it.isWhitespace() }
                if (isValid(t)) { out.putIfAbsent(t, UpsNumber(t, source)); continue }
                if (allowCorrection) corrected(t)?.let { out.putIfAbsent(it, UpsNumber(it, source, corrected = true)) }
            }
            // OCR often reads the "Z" as 2 or the "1" as I/l: try those prefixes too.
            if (allowCorrection) for (m in Regex("[1IL]\\s*[Z2](?:\\s*[A-Z0-9]){16}").findAll(up)) {
                val t = "1Z" + m.value.filter { !it.isWhitespace() }.substring(2)
                // Real 1Z numbers are mostly digits (package number, check digit, service code):
                // this keeps address words read across a line from passing as one.
                if (t.substring(2).count { it in '0'..'9' } < 9) continue
                if (t in out) continue
                if (isValid(t)) out[t] = UpsNumber(t, source, corrected = true)
                else corrected(t)?.let { out.putIfAbsent(it, UpsNumber(it, source, corrected = true)) }
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
