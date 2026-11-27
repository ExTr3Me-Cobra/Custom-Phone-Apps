package com.labelrescue.app.pipeline

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrElement
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.OcrSymbol
import com.labelrescue.core.ocr.TextRecognizer
import java.util.concurrent.TimeUnit

/**
 * ML Kit Text Recognition v2 with the bundled Latin model (no download). Keeps per-symbol
 * confidence and boxes. Must be called off the main thread.
 */
class MlKitText : TextRecognizer, AutoCloseable {
    private val client get() = shared

    override fun recognize(img: GrayImage): OcrPage {
        val input = InputImage.fromByteArray(Images.toNv21(img), img.width, img.height, 0, InputImage.IMAGE_FORMAT_NV21)
        // At most two reads at once: steadier memory and CPU on full-resolution frames.
        gate.acquireUninterruptibly()
        val text = try { client.process(input).awaitFully(30_000) } finally { gate.release() } ?: return OcrPage(emptyList())
        return map(text)
    }

    companion object {
        /**
         * One recognizer for the app's life, never closed: closing it while ML Kit was still
         * working on an image (a stopped scan's leftover work) crashed the app in native code.
         */
        private val shared by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        private val gate = java.util.concurrent.Semaphore(2)
    }

    private fun map(t: Text): OcrPage = OcrPage(t.textBlocks.flatMap { b ->
        b.lines.map { l ->
            OcrLine(
                l.text, l.confidence, l.boundingBox?.toRect(), l.angle,
                l.elements.map { e ->
                    OcrElement(
                        e.text, e.confidence, e.boundingBox?.toRect(),
                        e.symbols.map { s -> OcrSymbol(s.text, s.confidence, s.boundingBox?.toRect()) },
                    )
                },
            )
        }
    })

    private fun android.graphics.Rect.toRect() = IntRect(left, top, right, bottom)

    /** Nothing to close: the recognizer is shared and lives as long as the app. */
    override fun close() {}
}
