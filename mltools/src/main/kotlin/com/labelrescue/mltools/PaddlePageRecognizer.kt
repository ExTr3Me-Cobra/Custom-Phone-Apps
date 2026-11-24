package com.labelrescue.mltools

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrElement
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.OcrSymbol
import com.labelrescue.core.ocr.TextRecognizer
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A desktop stand-in for the phone's whole-page OCR (ML Kit), so tests can run the pipeline the
 * way the phone does: PaddleOCR's text detector finds the lines anywhere in the frame, its line
 * recognizer reads them, and the result has line, word and character boxes in frame coordinates
 * like ML Kit's. Like ML Kit it reports only the read character and its confidence (per-character
 * alternatives are dropped), so the app's own digit model and second reader do the rest.
 *
 * Not ML Kit: its errors differ. It's the closest offline substitute; a test passing here is
 * evidence, not proof, for the phone.
 */
class PaddlePageRecognizer(det: ByteArray, private val rec: TextRecognizer) : TextRecognizer, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(det, OrtSession.SessionOptions().apply { setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) })
    private val inputName = session.inputNames.first()

    override fun recognize(img: GrayImage): OcrPage {
        val boxes = detect(img)
        val lines = ArrayList<OcrLine>()
        for (b in boxes) {
            val page = rec.recognize(img.crop(b))
            for (l in page.lines) {
                fun sh(r: IntRect?) = r?.let { IntRect(it.left + b.left, it.top + b.top, it.right + b.left, it.bottom + b.top) }
                val els = l.elements.map { e ->
                    OcrElement(e.text, e.confidence, sh(e.box), e.symbols.map { s -> OcrSymbol(s.text, s.confidence, sh(s.box)) })
                }
                lines += OcrLine(l.text, l.confidence, b, 0f, els)
            }
        }
        return OcrPage(lines.sortedBy { it.box?.top ?: 0 })
    }

    /** Text line boxes: DBNet probability map, thresholded, connected components, expanded. */
    fun detect(img: GrayImage): List<IntRect> {
        val limit = 960.0
        val s = min(1.0, limit / max(img.width, img.height))
        val w = max(32, ((img.width * s) / 32).roundToInt() * 32); val h = max(32, ((img.height * s) / 32).roundToInt() * 32)
        val small = img.scaled(w, h)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val chw = FloatArray(3 * h * w)
        for (y in 0 until h) for (x in 0 until w) {
            val v = small[x, y] / 255f
            for (c in 0 until 3) chw[c * h * w + y * w + x] = (v - mean[c]) / std[c]
        }
        val prob: Array<FloatArray> = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        val on = BooleanArray(w * h) { prob[it / w][it % w] > 0.3f }
        val seen = BooleanArray(w * h)
        val out = ArrayList<IntRect>()
        val stack = IntArray(w * h)
        for (i in on.indices) {
            if (!on[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var l = w; var r = 0; var t = h; var b = 0; var n = 0; var score = 0.0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y); n++; score += prob[y][x]
                for ((dx, dy) in NB) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (on[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n < 12 || score / n < 0.6 || b - t < 3) continue
            // Unclip: DBNet shrinks text regions; grow by about half the line height each way.
            val bw = r - l + 1; val bh = b - t + 1
            val d = (bw * bh * 1.5 / (2.0 * (bw + bh))).roundToInt()
            val box = IntRect(((l - d) / s * img.width / (w / s)).roundToInt(), ((t - d) / s * img.height / (h / s)).roundToInt(),
                ((r + 1 + d) / s * img.width / (w / s)).roundToInt(), ((b + 1 + d) / s * img.height / (h / s)).roundToInt())
                .intersect(IntRect(0, 0, img.width, img.height))
            if (box.width >= 8 && box.height >= 8) out += box
        }
        return out
    }

    override fun close() { session.close() }

    private companion object { val NB = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1) }
}
