package com.labelrescue.core.fusion

import com.labelrescue.core.barcode.Code128
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrElement
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.OcrSymbol
import com.labelrescue.core.ocr.TextRecognizer
import com.labelrescue.core.synth.Synth
import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic damaged-label benchmark on the JVM: barcode images with random damage, decoded by
 * ZXing + the partial decoder, plus a simulated OCR read with dropped/confused digits.
 */
class PipelineBenchmarkTest {

    private fun randomTracking(rnd: Random): String {
        val payload = "92" + listOf("055", "001", "346", "612").random(rnd) + "9" + (1..15).map { '0' + rnd.nextInt(10) }.joinToString("")
        return payload + CheckDigits.mod10(payload)
    }

    /** Simulated OCR: reads the number with some digits missing and some confused. */
    private class FakeOcr(val truth: String, val rnd: Random, val dropRate: Double, val confuseRate: Double) : TextRecognizer {
        override fun recognize(img: GrayImage): OcrPage {
            val elements = ArrayList<OcrElement>()
            var x = 100; var i = 0
            for (g in TrackingFormat.groups(truth.length)) {
                val syms = ArrayList<OcrSymbol>()
                for (k in 0 until g) {
                    if (rnd.nextDouble() >= dropRate) {
                        val confused = rnd.nextDouble() < confuseRate
                        val c = if (confused) "0123456789"[rnd.nextInt(10)] else truth[i]
                        syms += OcrSymbol(c.toString(), if (confused) 0.4f else 0.93f, IntRect(x, 300, x + 16, 330))
                    }
                    x += 20; i++
                }
                x += 20
                if (syms.isNotEmpty()) elements += OcrElement(syms.joinToString("") { it.text }, 0.9f, IntRect(syms.first().box!!.left, 300, syms.last().box!!.right, 330), syms)
            }
            return OcrPage(listOf(OcrLine("USPS GROUND ADVANTAGE", 0.9f, null, 0f, emptyList()), OcrLine(elements.joinToString(" ") { it.text }, 0.9f, IntRect(100, 300, x, 330), 0f, elements)))
        }
    }

    private fun damaged(truth: String, rnd: Random, level: Int): GrayImage {
        val values = Code128.impbValues(truth, "%05d".format(rnd.nextInt(100000)))
        val module = 2.4 + rnd.nextDouble() * 1.6
        var img = Synth.renderBarcode(values, module, height = 90 + rnd.nextInt(40))
        // Pad into a "label".
        val label = GrayImage.filled(img.width + 120, img.height + 160, Synth.PAPER)
        for (y in 0 until img.height) for (x in 0 until img.width) label[x + 60, y + 40] = img[x, y]
        img = label
        img = Synth.blur(img, 0.6 + rnd.nextDouble() * 0.6 * level)
        val sym = 11 * module
        repeat(level) {
            when (rnd.nextInt(4)) {
                0 -> { // vertical loss of about one symbol
                    val x0 = 60 + ((2 + rnd.nextInt(values.size - 4)) * 11 + 12) * module
                    img = Synth.verticalLoss(img, x0.toInt(), (sym * (0.6 + rnd.nextDouble() * 0.5)).toInt())
                }
                1 -> img = Synth.smudge(img, rnd, 3, (sym * 0.7).toInt())
                2 -> img = Synth.inkDropout(img, rnd, 12, (sym * 0.4).toInt())
                else -> img = Synth.fade(img, 0.4)
            }
        }
        img = Synth.noise(img, 5.0 + 3 * level, rnd)
        if (rnd.nextBoolean()) img = Synth.rotate(img, rnd.nextDouble() * 4 - 2)
        return img
    }

    @Test fun recoveryRate() {
        val rnd = Random(2024)
        val engine = CandidateEngine()
        val cases = 24
        var full = 0; var top1 = 0; var top5 = 0; var barcodeOnlyTop1 = 0
        for (n in 0 until cases) {
            val truth = randomTracking(rnd)
            val level = 1 + n % 3
            val frames = List(2) { damaged(truth, Random(n * 31 + it), level) }
            val pipeline = RecoveryPipeline(listOf(ZxingDecoder()), FakeOcr(truth, Random(n), 0.12, 0.08))
            val out = pipeline.run(frames)
            if (out.fullDecode != null) {
                full++; top1++; top5++
                assertEquals(truth, out.fullDecode!!.tracking)
                barcodeOnlyTop1++
                continue
            }
            val f = out.format ?: continue
            val ranked = engine.rank(f, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence())
            val nums = ranked.candidates.map { it.tracking }
            if (nums.firstOrNull() == truth) top1++
            if (truth in nums.take(5)) top5++
            // Barcode evidence alone (no OCR).
            val barOnly = RecoveryPipeline(listOf(ZxingDecoder()), null).run(frames)
            if (barOnly.fullDecode?.tracking == truth) barcodeOnlyTop1++
            else if (barOnly.format != null) {
                val r = engine.rank(barOnly.format!!, barOnly.slots, barOnly.prior(HistoryStats.EMPTY), barOnly.exactEvidence())
                if (r.best?.tracking == truth) barcodeOnlyTop1++
            }
        }
        println("BENCHMARK synthetic damaged labels: cases=$cases fullDecode=$full top1=$top1 (${100 * top1 / cases}%) top5=$top5 (${100 * top5 / cases}%) barcodeOnlyTop1=$barcodeOnlyTop1")
        assertTrue("top-1 recovery ${top1}/$cases", top1 >= cases * 0.75)
        assertTrue("top-5 recovery ${top5}/$cases", top5 >= cases * 0.9)
        assertTrue("barcode-only recovery ${barcodeOnlyTop1}/$cases", barcodeOnlyTop1 >= cases * 0.75)
    }
}
