package com.labelrescue.mltools.debug

import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.fusion.DiagnosticLog
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.ml.DigitModel
import com.labelrescue.core.ml.Models
import com.labelrescue.core.ml.RefNet
import com.labelrescue.core.ocr.PaddleLineRecognizer
import com.labelrescue.mltools.OrtSequenceNet
import com.labelrescue.mltools.PaddlePageRecognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Runs the phone-like pipeline on one photo (PHOTO), writes build/one-photo.log. Local debugging. */
class OnePhotoTest {
    @Test fun one() {
        val path = System.getenv("PHOTO"); assumeTrue(path != null)
        val dict = File("../models/ppocr/dict.txt").readLines()
        val line = PaddleLineRecognizer(OrtSequenceNet(File("../build/ppocr/rec.onnx").readBytes()), dict)
        val page = PaddlePageRecognizer(File("../build/ppocr/det.onnx").readBytes(), PaddleLineRecognizer(OrtSequenceNet(File("../build/ppocr/rec.onnx").readBytes()), dict))
        val digits = DigitModel(RefNet.load(File("../models/digit_classifier.lrw").readBytes()).runner())
        val bi = ImageIO.read(File(path!!)); val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        var img = GrayImage.fromArgb(bi.width, bi.height, px)
        val log = DiagnosticLog()
        val out = RecoveryPipeline(listOf(ZxingDecoder()), page, models = Models(digits = digits), text2 = line, log = log).run(listOf(img, img.limited(1440)))
        val f = out.format
        val r = if (f != null && out.fullDecode == null) CandidateEngine().rank(f, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence()) else null
        System.getenv("TRUTH")?.let { t -> log.line("TRUTH rank " + ((r?.candidates?.indexOfFirst { it.tracking == t } ?: -2) + 1)) }
        log.line("RESULT full=${out.fullDecode?.tracking} by ${out.fullDecodeBy}; top=" + r?.candidates?.take(3)?.joinToString { "${it.tracking} ${(it.confidence * 100).toInt()}%" } + "; ups=" + out.ups.map { it.tracking })
        File("build/one-photo.log").writeText(log.text())
    }
}
