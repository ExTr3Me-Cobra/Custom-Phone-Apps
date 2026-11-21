package com.labelrescue.core.usps

import com.labelrescue.core.candidates.SlotDist

/**
 * Does a USPS number from elsewhere (pasted, e.g. copied from ups.com) fit what the damaged label
 * shows? A sanity check, not a vote: it flags a wrong paste or a number of a different format,
 * and digits the label shows clearly as something else.
 */
object LabelFit {
    data class Result(
        val parsed: ParsedNumber,
        /** The label's number was read in this format (null if nothing was read). */
        val sameFormat: Boolean,
        /** Positions (0-based, in the tracking number) the label shows clearly (≥ 90%) as a different digit. */
        val conflicts: List<Int>,
        /** Positions where the label's best reading agrees. */
        val agreeing: Int,
    ) {
        val fits: Boolean get() = sameFormat && conflicts.isEmpty()
    }

    /** The first valid USPS number in [text] (spaces, dashes and other words allowed), or null. */
    fun findUsps(text: String): ParsedNumber? {
        val up = text.uppercase()
        ImpbParser.parse(up.filter { it.isLetterOrDigit() })?.takeIf { it.checkValid }?.let { return it }
        // Grouped digits ("9261 2903 1024 ..."): join runs of digits separated by spaces or dashes.
        for (m in Regex("[0-9][0-9 \\-]{18,50}[0-9]").findAll(up)) {
            val d = m.value.filter { it.isDigit() }
            ImpbParser.parse(d)?.takeIf { it.checkValid }?.let { return it }
            ImpbParser.extractFromPayload(d).firstOrNull { it.checkValid }?.let { return it }
        }
        return ImpbParser.extractFromPayload(up).firstOrNull { it.checkValid }
    }

    /** Compares [number] with the label's evidence [slots] (tracking positions only) read as [format]. */
    fun check(number: ParsedNumber, format: TrackingFormat?, slots: List<SlotDist>): Result {
        val t = number.tracking
        if (format == null || format.trackingFormat != number.format.trackingFormat || slots.size != format.length) {
            return Result(number, false, emptyList(), 0)
        }
        val off = format.routingLength
        val conflicts = ArrayList<Int>()
        var agree = 0
        for (i in t.indices) {
            val s = slots.getOrNull(off + i) ?: continue
            if (s.best == t[i]) agree++
            else if (s.bestP >= 0.9) conflicts += i
        }
        return Result(number, true, conflicts, agree)
    }
}
