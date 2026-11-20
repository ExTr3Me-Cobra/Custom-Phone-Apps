package com.labelrescue.core.usps

/** USPS check digit algorithms. */
object CheckDigits {

    /**
     * USPS mod-10 weight for a payload digit, counting from the right of the payload
     * (the digit just left of the check digit has weight 3, then 1, 3, 1, ...).
     */
    fun mod10Weight(indexFromRight: Int): Int = if (indexFromRight % 2 == 0) 3 else 1

    /** Check digit for a payload (all digits except the check digit). */
    fun mod10(payload: CharSequence): Int {
        var sum = 0
        val n = payload.length
        for (i in 0 until n) {
            val d = payload[n - 1 - i] - '0'
            require(d in 0..9) { "not a digit: ${payload[n - 1 - i]}" }
            sum += d * mod10Weight(i)
        }
        return (10 - sum % 10) % 10
    }

    /** True if [number]'s last digit is the USPS mod-10 check digit of the rest. */
    fun isValidMod10(number: CharSequence): Boolean {
        if (number.length < 2 || number.any { it !in '0'..'9' }) return false
        return mod10(number.subSequence(0, number.length - 1)) == number[number.length - 1] - '0'
    }

    val S10_WEIGHTS = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)

    /** UPU S10 check digit for the 8 serial digits. */
    fun s10(serial: CharSequence): Int {
        require(serial.length == 8)
        var sum = 0
        for (i in 0 until 8) {
            val d = serial[i] - '0'
            require(d in 0..9)
            sum += d * S10_WEIGHTS[i]
        }
        return s10FromSum(sum)
    }

    fun s10FromSum(sum: Int): Int = when (val c = 11 - sum % 11) {
        10 -> 0
        11 -> 5
        else -> c
    }

    /** "AB123456785US" style: 2 letters, 8 digits, check digit, 2 letters. */
    fun isValidS10(number: CharSequence): Boolean {
        if (number.length != 13) return false
        if (!number[0].isUpperAsciiLetter() || !number[1].isUpperAsciiLetter()) return false
        if (!number[11].isUpperAsciiLetter() || !number[12].isUpperAsciiLetter()) return false
        for (i in 2..10) if (number[i] !in '0'..'9') return false
        return s10(number.subSequence(2, 10)) == number[10] - '0'
    }

    /** Code 128 checksum: (start value + sum(position * value)) mod 103, positions from 1. */
    fun code128(values: IntArray): Int {
        require(values.isNotEmpty())
        var sum = values[0]
        for (i in 1 until values.size) sum += i * values[i]
        return sum % 103
    }

    private fun Char.isUpperAsciiLetter() = this in 'A'..'Z'
}
