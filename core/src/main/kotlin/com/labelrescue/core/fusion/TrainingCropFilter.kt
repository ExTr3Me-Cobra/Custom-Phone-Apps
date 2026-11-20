package com.labelrescue.core.fusion

import com.labelrescue.core.candidates.SlotDist

/**
 * Decides which digit crops of a confirmed label are safe to keep as training data.
 *
 * A crop is kept only if at least one reader saw the confirmed digit at that position: either
 * OCR engine, the barcode, or the digit model, as its best guess or with real probability. If
 * every reader saw something else, the crop is most likely of the wrong spot (a misaligned read)
 * or the confirmation is wrong, and saving it would teach the model a wrong answer.
 */
object TrainingCropFilter {
    /** Probability a reader must give the confirmed digit to count as agreeing (if not its best guess). */
    const val MIN_P = 0.3

    fun agreeing(perSource: Map<Source, List<SlotDist?>>, position: Int, digit: Char): List<Source> =
        perSource.mapNotNull { (src, slots) ->
            val s = slots.getOrNull(position) ?: return@mapNotNull null
            if (s.best == digit || s.p(digit) >= MIN_P) src else null
        }
}
