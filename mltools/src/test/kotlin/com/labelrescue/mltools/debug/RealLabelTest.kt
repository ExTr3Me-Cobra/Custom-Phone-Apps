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
import com.labelrescue.mltools.OrtSequenceNet
import com.labelrescue.core.ocr.PaddleLineRecognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** End-to-end on a real photo (REAL=dir with label.png). Not part of CI. */
class RealLabelTest {
    private fun load(p: String): GrayImage {
        val bi = ImageIO.read(File(p))
        val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return GrayImage.fromArgb(bi.width, bi.height, px)
    }

    @Test fun real() {
        val dir = System.getenv("REAL") ?: return assumeTrue(false)
        val full = load("$dir/label.png")
        val paddle = PaddleLineRecognizer(OrtSequenceNet(File("../build/ppocr/rec.onnx").readBytes()), File("../models/ppocr/dict.txt").readLines())
        val digits = DigitModel(RefNet.load(File("../models/digit_classifier.lrw").readBytes()).runner())
        val log = DiagnosticLog()
        // The number line (ML Kit would find it on the phone).
        val numberCrop = NormRect(0f, 3030f / full.height, 1320f / full.width, 3190f / full.height)
        val out = RecoveryPipeline(listOf(ZxingDecoder()), null, models = Models(digits = digits), text2 = paddle, log = log)
            .run(listOf(full), RecoveryPipeline.Options(numberCrop = numberCrop, maxFrames = 1))
        val f = out.format
        if (f != null) {
            val r = CandidateEngine().rank(f, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence())
            log.candidates(r, 200)
            // As on the phone, where ML Kit reads the label text.
            val text = "USPS APIs\nG\nMailed from 08081\nSPS GROUND ADVANTAGE\nCreated 11/16/2026\nSICKLERVILLE NJ 08081\nBARRE VT 05641"
            val prior = com.labelrescue.core.candidates.CompositePrior(listOf(
                com.labelrescue.core.candidates.StructurePrior(com.labelrescue.core.usps.ServiceHints.serviceHints(text), listOf("05641"))))
            val r2 = CandidateEngine().rank(f, out.slots, prior, out.exactEvidence())
            log.section("With label text (Ground Advantage)")
            log.candidates(r2, 200)
            File("$dir/training_report.txt").writeText(com.labelrescue.core.fusion.TrainingReport.entry(
                "20261120-144116", "Nov 20, 2026 2:41 PM", "app (desktop test) · models: digits + PaddleOCR",
                out, r2, null))
        }
        File("$dir/diagnostic.log").writeText(log.text())
        println("REALLOG written")
    }
}
