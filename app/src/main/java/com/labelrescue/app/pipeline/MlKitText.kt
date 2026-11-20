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
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(img: GrayImage): OcrPage {
        val input = InputImage.fromByteArray(Images.toNv21(img), img.width, img.height, 0, InputImage.IMAGE_FORMAT_NV21)
        val text = try { Tasks.await(client.process(input), 30, TimeUnit.SECONDS) } catch (e: Exception) { return OcrPage(emptyList()) }
        return map(text)
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

    override fun close() = client.close()
}
