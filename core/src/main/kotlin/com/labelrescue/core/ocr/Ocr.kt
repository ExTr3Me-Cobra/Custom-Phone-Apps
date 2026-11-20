package com.labelrescue.core.ocr

import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect

/** OCR output in a recognizer-neutral form (ML Kit maps into this). Confidence is NaN if unknown. */
data class OcrSymbol(val text: String, val confidence: Float, val box: IntRect?)
data class OcrElement(val text: String, val confidence: Float, val box: IntRect?, val symbols: List<OcrSymbol>)
data class OcrLine(val text: String, val confidence: Float, val box: IntRect?, val angle: Float, val elements: List<OcrElement>)
data class OcrPage(val lines: List<OcrLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

fun interface TextRecognizer {
    fun recognize(img: GrayImage): OcrPage
}

/**
 * How OCR confuses characters on labels. A low-confidence read becomes a ranked list:
 * an 8 read at 55% might really be 3, 6, 0 or 9.
 */
object DigitConfusion {
    /** Relative confusion weights between digits (symmetric-ish, from common OCR/print errors). */
    private val CONFUSE: Map<Int, Map<Int, Double>> = mapOf(
        0 to mapOf(6 to 3.0, 8 to 3.0, 9 to 2.0, 3 to 0.5),
        1 to mapOf(7 to 4.0, 4 to 1.0, 2 to 0.5),
        2 to mapOf(7 to 1.5, 3 to 1.0, 1 to 0.5, 8 to 0.5),
        3 to mapOf(8 to 4.0, 5 to 1.5, 9 to 1.0, 2 to 1.0),
        4 to mapOf(1 to 1.0, 9 to 1.0, 7 to 0.5),
        5 to mapOf(6 to 4.0, 3 to 1.5, 8 to 1.0, 9 to 0.5),
        6 to mapOf(5 to 4.0, 0 to 3.0, 8 to 3.0, 9 to 0.5),
        7 to mapOf(1 to 4.0, 2 to 1.5, 4 to 0.5),
        8 to mapOf(3 to 4.0, 0 to 3.0, 6 to 3.0, 9 to 2.0, 5 to 1.0),
        9 to mapOf(0 to 2.0, 8 to 2.0, 4 to 1.0, 3 to 1.0, 7 to 0.5),
    )

    /** Letters and symbols OCR reports in place of digits, with how strongly they suggest the digit. */
    private val LOOKALIKE: Map<Char, Pair<Int, Double>> = mapOf(
        'O' to (0 to 0.9), 'o' to (0 to 0.8), 'D' to (0 to 0.7), 'Q' to (0 to 0.6), 'U' to (0 to 0.3), 'C' to (0 to 0.3),
        'I' to (1 to 0.9), 'l' to (1 to 0.9), 'i' to (1 to 0.7), '|' to (1 to 0.8), '!' to (1 to 0.6), 'L' to (1 to 0.4), 'J' to (1 to 0.3), 'j' to (1 to 0.3), '/' to (1 to 0.3),
        'Z' to (2 to 0.8), 'z' to (2 to 0.8),
        'A' to (4 to 0.5), 'h' to (4 to 0.3),
        'S' to (5 to 0.8), 's' to (5 to 0.8), '$' to (5 to 0.7),
        'G' to (6 to 0.7), 'b' to (6 to 0.7),
        'T' to (7 to 0.6), '?' to (7 to 0.3),
        'B' to (8 to 0.8), '&' to (8 to 0.5),
        'g' to (9 to 0.7), 'q' to (9 to 0.7), 'P' to (9 to 0.2),
    )

    /** Returns the digit a character most likely stands for and a reliability factor, or null. */
    fun digitFor(c: Char): Pair<Int, Double>? =
        if (c in '0'..'9') (c - '0') to 1.0 else LOOKALIKE[c]

    /**
     * Distribution over 0..9 for an OCR'd character with OCR confidence [confidence] (0..1, NaN
     * if unknown). The read digit gets most of the mass; the rest goes to its usual confusions.
     */
    fun distribution(c: Char, confidence: Float): SlotDist? {
        val (d, reliability) = digitFor(c) ?: return null
        val conf = if (confidence.isNaN() || confidence <= 0f) 0.75 else confidence.toDouble().coerceIn(0.0, 1.0)
        val pRead = ((0.35 + 0.6 * conf) * reliability).coerceIn(0.12, 0.97)
        val probs = DoubleArray(10)
        probs[d] = pRead
        val rest = 1 - pRead
        val conf2 = CONFUSE.getValue(d)
        val total = conf2.values.sum()
        for ((k, w) in conf2) probs[k] += rest * 0.75 * w / total
        for (k in 0..9) if (k != d) probs[k] += rest * 0.25 / 9
        return SlotDist(SlotDist.DIGITS, probs)
    }
}
