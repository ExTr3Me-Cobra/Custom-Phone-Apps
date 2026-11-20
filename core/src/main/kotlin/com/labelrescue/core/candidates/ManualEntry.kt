package com.labelrescue.core.candidates

import com.labelrescue.core.usps.ImpbParser
import com.labelrescue.core.usps.TrackingFormat

/**
 * Manual entry: the user types what they can read.
 *  - digits / letters: known
 *  - `?` (or `_`, `*`): unknown
 *  - `[38]`: one of 3 or 8
 * Spaces and dashes are ignored. Works without any photo.
 */
object ManualEntry {

    data class Parsed(val format: TrackingFormat, val slots: List<SlotDist>, val pattern: String)

    sealed class Result {
        data class Ok(val parsed: Parsed) : Result()
        data class Error(val message: String) : Result()
    }

    fun parse(input: String): Result {
        val tokens = ArrayList<Token>()
        var i = 0
        val s = input.uppercase()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '[' -> {
                    val end = s.indexOf(']', i)
                    if (end < 0) return Result.Error("Missing ] in input")
                    val set = s.substring(i + 1, end).filter { it.isLetterOrDigit() }.toSet()
                    if (set.isEmpty()) return Result.Error("Empty [ ] in input")
                    tokens += Token.OneOf(set)
                    i = end + 1
                    continue
                }
                c == '?' || c == '_' || c == '*' -> tokens += Token.Unknown
                c.isDigit() || c in 'A'..'Z' -> tokens += Token.Known(c)
                c.isWhitespace() || c == '-' || c == '.' -> {}
                else -> return Result.Error("Unexpected character '$c'")
            }
            i++
        }
        if (tokens.isEmpty()) return Result.Error("Type the digits you can read, with ? for unknowns")

        val pattern = tokens.joinToString("") { it.patternChar().toString() }
        val format = ImpbParser.formatFor(pattern)
            ?: return Result.Error(
                "${tokens.size} characters: USPS numbers have 20, 22, 26, 30 or 34 digits, or 13 characters for international (e.g. RA123456785US)",
            )

        val slots = tokens.mapIndexed { idx, t ->
            val alphabet = if (format.isLetterPosition(idx)) SlotDist.LETTERS else SlotDist.DIGITS
            when (t) {
                is Token.Known -> {
                    if (t.c !in alphabet) return Result.Error("Position ${idx + 1} should be a ${if (alphabet == SlotDist.DIGITS) "digit" else "letter"}")
                    SlotDist.known(t.c, alphabet, userFixed = true)
                }
                is Token.OneOf -> {
                    val ok = t.chars.filter { it in alphabet }
                    if (ok.isEmpty()) return Result.Error("Position ${idx + 1}: none of ${t.chars.joinToString("")} fit")
                    SlotDist.oneOf(ok, alphabet)
                }
                Token.Unknown -> {
                    // International numbers almost always end in "US" here.
                    if (format == TrackingFormat.S10 && idx >= 11) SlotDist.likely(if (idx == 11) 'U' else 'S', 0.9, alphabet)
                    else SlotDist.uniform(alphabet)
                }
            }
        }
        return Result.Ok(Parsed(format, slots, pattern))
    }

    private sealed class Token {
        data class Known(val c: Char) : Token()
        data class OneOf(val chars: Set<Char>) : Token()
        data object Unknown : Token()

        fun patternChar(): Char = when (this) {
            is Known -> c
            is OneOf -> '?'
            Unknown -> '?'
        }
    }
}
