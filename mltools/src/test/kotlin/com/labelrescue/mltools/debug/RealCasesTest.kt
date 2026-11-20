package com.labelrescue.mltools.debug

import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.fusion.DiagnosticLog
import com.labelrescue.core.fusion.NormRect
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.ml.DigitModel
import com.labelrescue.core.ml.Models
import com.labelrescue.core.ml.RefNet
import com.labelrescue.core.ocr.PaddleLineRecognizer
import com.labelrescue.mltools.OrtSequenceNet
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Regression on real damaged labels (src/test/resources/real, cropped to barcode and number, with
 * the true number from the user or a reprint). Needs the PaddleOCR ONNX model in build/ppocr
 * (training/convert_ppocr.py); skipped without it. Writes build/real-cases.txt.
 */
class RealCasesTest {
    @Test fun realCases() {
        val onnx = File("../build/ppocr/rec.onnx")
        assumeTrue("PaddleOCR ONNX model not converted", onnx.exists())
        val paddle = PaddleLineRecognizer(OrtSequenceNet(onnx.readBytes()), File("../models/ppocr/dict.txt").readLines())
        val digits = DigitModel(RefNet.load(File("../models/digit_classifier.lrw").readBytes()).runner())
        val dir = File(javaClass.getResource("/real/cases.txt")!!.toURI()).parentFile
        val report = StringBuilder()
        var failures = 0
        for (line in File(dir, "cases.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val (file, truth, box) = line.split("|").map { it.trim() }
            val img = load(File(dir, file))
            val (l, t, r, b) = box.split(",").map { it.trim().toFloat() }
            val log = DiagnosticLog()
            val out = RecoveryPipeline(listOf(ZxingDecoder()), null, models = Models(digits = digits), text2 = paddle, log = log)
                .run(listOf(img), RecoveryPipeline.Options(numberCrop = NormRect(l / img.width, t / img.height, r / img.width, b / img.height), maxFrames = 1))
            val f = out.format
            val ranked = if (f != null) CandidateEngine().rank(f, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence()) else null
            val rank = ranked?.candidates?.indexOfFirst { it.tracking == truth }?.takeIf { it >= 0 }?.plus(1)
            val top = ranked?.candidates?.take(3)?.joinToString { "${it.tracking} ${"%.0f".format(it.confidence * 100)}%" }
            report.append("$file: truth rank ${rank ?: "not listed"} of ${ranked?.candidates?.size ?: 0}; top: $top\n")
            File("build/real-$file.log").writeText(log.text())
            if (rank == null || rank > 10) failures++
        }
        File("build/real-cases.txt").writeText(report.toString())
        println(report)
        assertTrue("true number not in the top 10:\n$report", failures == 0)
    }

    private fun load(f: File): GrayImage {
        val bi = ImageIO.read(f)
        val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return GrayImage.fromArgb(bi.width, bi.height, px)
    }
}
