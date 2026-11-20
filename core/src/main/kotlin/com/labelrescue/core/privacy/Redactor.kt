package com.labelrescue.core.privacy

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrLine
import kotlin.math.max
import kotlin.math.min

/**
 * Whites out names and addresses on a label photo before it leaves the app.
 *
 * It works by allow-list, not by trying to recognize names. Every OCR'd text line is blanked
 * unless all of its words are label boilerplate: the tracking number, service names, "USPS
 * TRACKING #", "Mailed from 08081", dates, weights. Barcodes and 2D codes aren't text, so they
 * stay. Fail-safe: if OCR found no lines, or a line to blank has no position, there's no photo.
 * OCR can still miss a faint line entirely, which is why the app shows the result before export.
 */
object Redactor {
    /** Words that may stay visible (upper case). Close misreads (one edit for 5+ letters) count too. */
    private val VOCAB = setOf(
        "USPS", "UNITED", "STATES", "POSTAL", "SERVICE", "TRACKING", "TRACK", "PRIORITY", "MAIL", "EXPRESS",
        "GROUND", "ADVANTAGE", "FIRST", "CLASS", "PACKAGE", "PARCEL", "SELECT", "RETAIL", "MEDIA", "LIBRARY",
        "POSTAGE", "PAID", "POS", "US", "COM", "WWW", "MAILED", "FROM", "CREATED", "SHIP", "SHIPPED", "DATE",
        "LB", "LBS", "OZ", "WEIGHT", "RDC", "ZONE", "COMMERCIAL", "PLUS", "PRICE", "NO", "SURCHARGE",
        "INSURED", "SIGNATURE", "REQUIRED", "CERTIFIED", "RETURN", "RECEIPT", "DELIVERY", "CONFIRMATION",
        "ELECTRONIC", "RATE", "EXPECTED", "DAY", "SCHEDULED", "PM", "AM", "IMPB", "APIS", "API", "LABEL",
        "FLAT", "ENVELOPE", "BOX", "CUBIC", "SOFTPACK", "CLICK", "ECOMMERCE",
    )

    /** True if [line] holds only boilerplate and numbers, so it can stay visible. */
    fun isSafe(line: String): Boolean {
        val words = line.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        for (w in words) {
            val letters = w.count { it in 'A'..'Z' }
            if (letters == 0) continue // numbers: tracking digits, ZIPs after "Mailed from", dates, weights
            if (letters <= 2 && w.length <= 2) continue // "G", "lb", fragments of damaged words
            if (w.any { it in '0'..'9' } && letters <= 2) continue // "11/16/2026"-style junk, "1lb"
            if (w in VOCAB) continue
            if (w.length >= 5 && VOCAB.any { it.length >= 5 && editDistance(it, w) <= 1 }) continue
            if (w.length >= 8 && VOCAB.any { it.length >= 8 && editDistance(it, w) <= 2 }) continue
            if (w.length >= 5 && VOCAB.any { it.length >= 8 && w in it }) continue // "ACKING" of a faded TRACKING
            return false
        }
        return true
    }

    /**
     * Boxes to white out (frame coordinates), from one or more OCR reads of the frame, or null if
     * the photo can't be made safe. [lines] pairs each line with its box already in frame coordinates.
     */
    fun blankBoxes(lines: List<Pair<OcrLine, IntRect?>>, width: Int, height: Int): List<IntRect>? {
        if (lines.isEmpty()) return null
        val out = ArrayList<IntRect>()
        val bounds = IntRect(0, 0, width, height)
        for ((line, box) in lines) {
            if (isSafe(line.text)) continue
            box ?: return null
            val pad = max(4, box.height * 2 / 5)
            out += box.inflate(pad + box.height / 4, pad).intersect(bounds)
        }
        return out.filter { !it.isEmpty }
    }

    /** A copy of [img] with [boxes] filled white. */
    fun apply(img: GrayImage, boxes: List<IntRect>): GrayImage {
        val out = GrayImage(img.width, img.height, img.data.copyOf())
        for (b in boxes) {
            for (y in max(0, b.top) until min(img.height, b.bottom)) {
                val row = y * img.width
                for (x in max(0, b.left) until min(img.width, b.right)) out.data[row + x] = 0xFF.toByte()
            }
        }
        return out
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
