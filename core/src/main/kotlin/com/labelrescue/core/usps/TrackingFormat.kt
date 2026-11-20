package com.labelrescue.core.usps

/**
 * The number shapes Label Rescue knows.
 *
 * Routed forms are what the IMpb barcode carries: "420" + destination ZIP (5 or 9 digits), then the
 * tracking number. The mod-10 check digit covers only the tracking part.
 */
enum class TrackingFormat(val length: Int, val routingLength: Int, val title: String) {
    IMPB_20(20, 0, "20-digit"),
    IMPB_22(22, 0, "22-digit IMpb"),
    IMPB_26(26, 0, "26-digit IMpb"),
    ROUTED_30(30, 8, "30-digit (420 + ZIP + 22)"),
    ROUTED_34_ZIP9(34, 12, "34-digit (420 + ZIP+4 + 22)"),
    ROUTED_34_ZIP5(34, 8, "34-digit (420 + ZIP + 26)"),
    S10(13, 0, "International S10");

    val trackingLength: Int get() = length - routingLength

    /** Index of the check digit within the full string. */
    val checkIndex: Int get() = if (this == S10) 10 else length - 1

    val isRouted: Boolean get() = routingLength > 0

    /** The unrouted format of the tracking part. */
    val trackingFormat: TrackingFormat
        get() = when (this) {
            ROUTED_30, ROUTED_34_ZIP9 -> IMPB_22
            ROUTED_34_ZIP5 -> IMPB_26
            else -> this
        }

    /** True if position [i] holds a letter rather than a digit. */
    fun isLetterPosition(i: Int): Boolean = this == S10 && (i < 2 || i > 10)

    /** True if the check digit algorithm uses position [i]. */
    fun isCheckedPosition(i: Int): Boolean = when (this) {
        S10 -> i in 2..10
        else -> i >= routingLength
    }

    companion object {
        /** Tracking numbers (what USPS tracks and what's printed under the barcode). */
        val TRACKING = listOf(IMPB_22, IMPB_26, IMPB_20, S10)

        fun forLength(length: Int, hasLetters: Boolean = false): List<TrackingFormat> =
            if (hasLetters) listOfNotNull(S10.takeIf { length == 13 })
            else entries.filter { it != S10 && it.length == length }

        /**
         * Groups used when the number is printed: 4-4-4-4-4-2 for 22 digits, fours with the
         * remainder at the end for other IMpb lengths, and 2-3-3-3-2 for S10.
         */
        fun groups(length: Int, s10: Boolean = false): List<Int> {
            if (s10) return listOf(2, 3, 3, 3, 2)
            val g = MutableList(length / 4) { 4 }
            if (length % 4 != 0) g.add(length % 4)
            return g
        }

        fun grouped(number: String, s10: Boolean = number.length == 13 && number.firstOrNull()?.isLetter() == true): String {
            val sb = StringBuilder()
            var i = 0
            for (g in groups(number.length, s10)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(number, i, minOf(number.length, i + g))
                i += g
            }
            return sb.toString()
        }
    }
}
