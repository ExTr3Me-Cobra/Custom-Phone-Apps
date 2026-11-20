package com.labelrescue.core.candidates

import kotlin.math.max

/**
 * Probability distribution over the characters that may sit at one position of a number.
 * Digit positions use [DIGITS]; S10 letter positions use [LETTERS].
 */
class SlotDist(val alphabet: String, probs: DoubleArray, val userFixed: Boolean = false) {
    val probs: DoubleArray

    init {
        require(probs.size == alphabet.length)
        val sum = probs.sum()
        this.probs = if (sum > 0) DoubleArray(probs.size) { probs[it] / sum } else DoubleArray(probs.size) { 1.0 / probs.size }
    }

    fun p(c: Char): Double {
        val i = alphabet.indexOf(c)
        return if (i < 0) 0.0 else probs[i]
    }

    val best: Char get() = alphabet[probs.indices.maxBy { probs[it] }]
    val bestP: Double get() = probs.max()

    /** True if one character clearly dominates. */
    val isCertain: Boolean get() = bestP >= CERTAIN

    val isUnknown: Boolean get() = bestP < 1.5 / alphabet.length

    /** Characters ranked by probability (only those with probability > 0). */
    fun ranked(): List<Pair<Char, Double>> =
        probs.indices.filter { probs[it] > 0 }.sortedByDescending { probs[it] }.map { alphabet[it] to probs[it] }

    fun fixedTo(c: Char): SlotDist = known(c, alphabet, userFixed = true)

    override fun toString(): String = ranked().take(3).joinToString(",") { "${it.first}:${"%.2f".format(it.second)}" }

    companion object {
        const val DIGITS = "0123456789"
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val CERTAIN = 0.97

        fun uniform(alphabet: String = DIGITS) = SlotDist(alphabet, DoubleArray(alphabet.length) { 1.0 })

        fun known(c: Char, alphabet: String = DIGITS, userFixed: Boolean = false): SlotDist {
            val a = DoubleArray(alphabet.length)
            val i = alphabet.indexOf(c)
            require(i >= 0) { "$c not in alphabet" }
            a[i] = 1.0
            return SlotDist(alphabet, a, userFixed)
        }

        fun oneOf(chars: Collection<Char>, alphabet: String = DIGITS): SlotDist {
            val a = DoubleArray(alphabet.length)
            for (c in chars) { val i = alphabet.indexOf(c); if (i >= 0) a[i] = 1.0 }
            return SlotDist(alphabet, a)
        }

        /** Mostly [c], with [rest] spread over the others. */
        fun likely(c: Char, pc: Double, alphabet: String = DIGITS): SlotDist {
            val i = alphabet.indexOf(c)
            val other = (1 - pc) / (alphabet.length - 1)
            return SlotDist(alphabet, DoubleArray(alphabet.length) { if (it == i) pc else max(other, 0.0) })
        }
    }
}
