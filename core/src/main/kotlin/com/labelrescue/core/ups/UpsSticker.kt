package com.labelrescue.core.ups

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.Variants
import com.labelrescue.core.ocr.TextRecognizer

/**
 * Reads the UPS 1Z number from a photo of the small UPS routing sticker (the one with
 * "P: … S: …", "ULD + D001" and the 1Z number printed as text). It has no barcode, so the text
 * is read in several image variants and the reads vote: the more of them agree on the same valid
 * number, the surer it is.
 */
object UpsSticker {
    data class Result(val number: UpsNumber, val agreeing: Int, val reads: Int)

    fun read(text: TextRecognizer, photo: GrayImage): Result? {
        val variants = Variants.make(photo.limited(2400), null, Variants.Options(maxSide = 2400, rotations = listOf(-3.0, 3.0)))
            .filter { it.name in setOf("gray", "clahe", "threshold", "sharpen", "rot-3", "rot+3") }
        val votes = LinkedHashMap<String, Int>()
        val exact = HashSet<String>()
        var reads = 0
        for (v in variants) {
            val found = UpsNumber.find(text.recognize(v.image).text, "UPS sticker", allowCorrection = true)
            reads++
            // One vote per variant for each number it read.
            for (u in found.distinctBy { it.tracking }) {
                votes.merge(u.tracking, 1, Int::plus)
                if (!u.corrected) exact += u.tracking
            }
        }
        val (best, n) = votes.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key in exact })?.toPair() ?: return null
        val corrected = best !in exact
        val confidence = when {
            n >= 3 && !corrected -> 0.99
            n >= 2 && !corrected -> 0.97
            n >= 2 -> 0.90
            !corrected -> 0.93
            else -> 0.80
        }
        return Result(UpsNumber(best, "UPS sticker ($n of $reads reads agree)", corrected, confidence), n, reads)
    }
}
