package com.labelrescue.core.candidates

import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat

/**
 * A finite-state view of a format's check digit, so candidates can be searched position by
 * position without listing every combination: the state is the weighted sum so far.
 */
class CheckModel(val format: TrackingFormat) {
    private val s10 = format == TrackingFormat.S10
    private val modulus = if (s10) 11 else 10

    /** States 0 until modulus are partial sums; [accept] means the check digit matched. */
    val accept = modulus
    val stateCount = modulus + 1
    val initial = 0

    /** Next state after placing [c] at [pos], or -1 if this path can no longer be valid. */
    fun step(state: Int, pos: Int, c: Char): Int {
        if (state < 0) return -1
        if (!format.isCheckedPosition(pos)) return state
        val d = c - '0'
        if (d !in 0..9) return -1
        if (pos == format.checkIndex) {
            if (state == accept) return -1
            val ok = if (s10) CheckDigits.s10FromSum(state) == d else (state + d) % 10 == 0
            return if (ok) accept else -1
        }
        if (state == accept) return -1
        val w = if (s10) CheckDigits.S10_WEIGHTS[pos - 2] else CheckDigits.mod10Weight(format.checkIndex - 1 - pos)
        return (state + d * w) % modulus
    }

    fun isAccepting(state: Int) = state == accept
}
