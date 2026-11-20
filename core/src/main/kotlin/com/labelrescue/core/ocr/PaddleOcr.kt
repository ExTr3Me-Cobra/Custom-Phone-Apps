package com.labelrescue.core.ocr

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A CTC sequence model with a fixed input of [PaddleLineRecognizer.HEIGHT] × [PaddleLineRecognizer.WIDTH]
 * × 3 (NHWC, values -1..1) returning per-timestep class probabilities [T][C]. LiteRT on Android;
 * ONNX Runtime in the JVM tools.
 */
fun interface SequenceNet {
    fun run(nhwc: FloatArray): Array<FloatArray>
}

/**
 * PaddleOCR PP-OCRv5 mobile text-line recognizer (Apache-2.0), as a second OCR engine for the
 * printed tracking number. Unlike ML Kit it gives a probability for every character at every
 * position, so a smudged 0 that reads as 1 still carries "0: 20%". Recognizes one text line per
 * image (the app gives it crops of the number line); character x-positions come from the CTC
 * timesteps.
 */
class PaddleLineRecognizer(private val net: SequenceNet, dict: List<String>) : TextRecognizer {
    /** Class index → character (0 = CTC blank, last = space). */
    private val chars: List<String> = listOf("") + dict + " "

    /** Classes worth keeping as per-character alternatives: digits and their lookalikes. */
    private val altClasses: List<Pair<Int, Char>> = chars.withIndex()
        .filter { (_, s) -> s.length == 1 && (s[0].isDigit() || DigitConfusion.digitFor(s[0]) != null) }
        .map { (i, s) -> i to s[0] }

    override fun recognize(img: GrayImage): OcrPage {
        if (img.height < 8 || img.width < 16) return OcrPage(emptyList())
        val h = HEIGHT
        // Keep the aspect ratio; a line wider than the model input is squeezed to fit.
        val w = (img.width * h.toDouble() / img.height).roundToInt().coerceIn(16, WIDTH)
        val small = img.scaled(w, h)
        // Padding is 0 in normalized space (mid-gray), as in PaddleOCR's own preprocessing;
        // white padding makes the recognizer hallucinate letters at the ends.
        val nhwc = FloatArray(h * WIDTH * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = ((small[x, y]) / 255f - 0.5f) / 0.5f
            val o = (y * WIDTH + x) * 3
            nhwc[o] = v; nhwc[o + 1] = v; nhwc[o + 2] = v
        }
        val all = try { net.run(nhwc) } catch (e: Exception) { return OcrPage(emptyList()) }
        // Only the timesteps over the real image (the rest is padding).
        val probs = all.copyOf(minOf(all.size, kotlin.math.ceil(all.size * w.toDouble() / WIDTH).toInt() + 1)).filterNotNull().toTypedArray()
        val T = probs.size
        if (T == 0) return OcrPage(emptyList())
        // Timestep → x in the original image.
        val step = img.width.toDouble() / (all.size * w.toDouble() / WIDTH)

        // Greedy CTC with timestep spans.
        val elements = ArrayList<OcrElement>()
        var syms = ArrayList<OcrSymbol>()
        fun flush() {
            if (syms.isEmpty()) return
            val box = syms.mapNotNull { it.box }.reduce { a, b -> a.union(b) }
            elements += OcrElement(syms.joinToString("") { it.text }, syms.map { it.confidence }.average().toFloat(), box, syms)
            syms = ArrayList()
        }
        var t = 0
        val top = (img.height * 0.12).roundToInt(); val bottom = (img.height * 0.88).roundToInt()
        while (t < T) {
            val k = argmax(probs[t])
            var end = t
            while (end + 1 < T && argmax(probs[end + 1]) == k) end++
            if (k != 0) {
                val c = chars[k]
                if (c == " " || c == "　") flush()
                else if (c.isNotEmpty()) {
                    // The timestep where this character is most certain carries its alternatives.
                    var peak = t
                    for (u in t..end) if (probs[u][k] > probs[peak][k]) peak = u
                    val alts = HashMap<Char, Float>()
                    var sum = 0f
                    for ((ci, ch) in altClasses) { val p = probs[peak][ci]; if (p > 0.002f) { alts[ch] = (alts[ch] ?: 0f) + p; sum += p } }
                    val x0 = (t * step).roundToInt(); val x1 = max(x0 + 1, ((end + 1) * step).roundToInt())
                    val cx = (x0 + x1) / 2
                    val half = max((x1 - x0) / 2, (img.height * 0.22).roundToInt())
                    val box = IntRect(max(0, cx - half), top, min(img.width, cx + half), bottom)
                    syms += OcrSymbol(c, probs[peak][k], box, alts.takeIf { sum > 0.05f }?.mapValues { it.value / sum })
                }
            }
            t = end + 1
        }
        flush()
        if (elements.isEmpty()) return OcrPage(emptyList())
        val text = elements.joinToString(" ") { it.text }
        return OcrPage(listOf(OcrLine(text, elements.map { it.confidence }.average().toFloat(), IntRect(0, 0, img.width, img.height), 0f, elements)))
    }

    private fun argmax(a: FloatArray): Int {
        var b = 0
        for (i in 1 until a.size) if (a[i] > a[b]) b = i
        return b
    }

    companion object {
        const val HEIGHT = 48
        const val WIDTH = 640
    }
}
