package com.labelrescue.mltools.debug

import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.fusion.DiagnosticLog
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.core.image.Filters
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The real damaged labels run the way the phone runs them: a burst of frames (the label's damage
 * fixed, only the photo varying: small tilt and shift, focus blur, noise, glare), whole-frame OCR
 * finding the number itself (no hand-set crop), the quick read on, the digit model on OCR boxes,
 * and no shipper history (User Mode). Whole-frame OCR is PaddleOCR's detector and recognizer
 * standing in for ML Kit (see [PaddlePageRecognizer]).
 *
 * Reports rank 1, the probability the app gives the true number, wrong quick reads, and time.
 * Not a pass/fail gate yet: it's the honest measurement the other changes are judged by.
 * Writes build/phone-like.txt. Needs build/ppocr/rec.onnx and det.onnx (training/convert_ppocr.py).
 */
class PhoneLikeTest {
    @Test fun phoneLike() {
        val rec = File("../build/ppocr/rec.onnx"); val det = File("../build/ppocr/det.onnx")
        assumeTrue("PaddleOCR ONNX models missing", rec.exists() && det.exists())
        val dict = File("../models/ppocr/dict.txt").readLines()
        val line = PaddleLineRecognizer(OrtSequenceNet(rec.readBytes()), dict)
        val page = PaddlePageRecognizer(det.readBytes(), PaddleLineRecognizer(OrtSequenceNet(rec.readBytes()), dict))
        val digits = DigitModel(RefNet.load(File("../models/digit_classifier.lrw").readBytes()).runner())
        val dir = File(javaClass.getResource("/real/cases.txt")!!.toURI()).parentFile
        val only = System.getenv("CASE")
        val frames = System.getenv("FRAMES")?.toInt() ?: 5
        val report = StringBuilder()
        val fusionRows = ArrayList<String>()
        var solved = 0; var counted = 0; var logP = 0.0; var wrongQuick = 0; var quick = 0
        for (row in File(dir, "cases.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val parts = row.split("|").map { it.trim() }
            val (file, truth) = parts
            if (truth.startsWith("1Z") || (only != null && only.split(",").none { it in file })) continue
            val hard = parts.getOrNull(3) == "hard"
            val img = load(File(dir, file))
            val burst = burst(img, frames, Random(file.hashCode()))
            val log = DiagnosticLog()
            val t = System.currentTimeMillis()
            val out = RecoveryPipeline(listOf(ZxingDecoder()), page, models = Models(digits = digits), text2 = line, log = log).run(burst)
            val ms = System.currentTimeMillis() - t
            val f = out.format
            val ranked = if (f != null) CandidateEngine().rank(f, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence()) else null
            val idx = ranked?.candidates?.indexOfFirst { it.tracking == truth } ?: -1
            val p = if (idx >= 0) ranked!!.candidates[idx].confidence else 0.0
            val how = when {
                out.fullDecode != null -> "full decode"
                out.notes.any { "read clearly" in it } -> "quick read"
                else -> "recovered"
            }
            if (how == "quick read") { quick++; if (ranked?.best?.tracking != truth) wrongQuick++ }
            if (!hard) { counted++; if (idx == 0) solved++; logP += ln(p.coerceAtLeast(1e-4)) }
            val top = ranked?.candidates?.take(2)?.joinToString { "${it.tracking} ${"%.0f".format(it.confidence * 100)}%" }
            report.append("$file: ${if (idx == 0) "RANK 1" else if (idx > 0) "rank ${idx + 1}" else "not listed"}, true number ${"%.0f".format(p * 100)}% ($how, ${ms / 1000.0}s); top: $top${if (hard) " (hard)" else ""}\n")
            File("build/phone${System.getenv("TAG") ?: ""}-$file.log").writeText(log.text())
            // Per-position evidence with the truth, for fitting the fusion (training/fit_fusion.py).
            if (f != null && out.fullDecode == null && truth.length == f.length) {
                for (i in truth.indices) for ((src, ds) in out.perSource) {
                    val sd = ds.getOrNull(i) ?: continue
                    if (sd.alphabet != com.labelrescue.core.candidates.SlotDist.DIGITS) continue
                    fusionRows += listOf(file, i.toString(), truth[i].toString(), src.name, if (i in out.deadCrossed) "1" else "0").joinToString(",") + "," + sd.probs.joinToString(",") { "%.5f".format(it) }
                }
            }
        }
        report.append("\nsolved $solved/$counted; mean log P(true) ${"%.2f".format(logP / counted.coerceAtLeast(1))} (geometric mean ${"%.0f".format(exp(logP / counted.coerceAtLeast(1)) * 100)}%); quick reads $quick, wrong $wrongQuick\n")
        File("build/phone-like${System.getenv("TAG") ?: ""}.txt").writeText(report.toString())
        File("build/fusion${System.getenv("TAG") ?: ""}.csv").writeText("file,pos,truth,source,crossed," + (0..9).joinToString(",") { "p$it" } + "\n" + fusionRows.joinToString("\n") + "\n")
        println(report)
    }

    /**
     * [n] photos of the same damaged label: the label is put on a paper margin (the phone frames
     * more than the barcode), then each frame gets its own tilt, scale, shift, blur, noise and
     * sometimes a glare spot. The label's own damage is identical in every frame.
     */
    private fun burst(img: GrayImage, n: Int, rnd: Random): List<GrayImage> {
        val mx = img.width / 8; val my = img.height / 5
        val paper = img.data.map { it.toInt() and 0xFF }.sorted().let { it[(it.size * 0.95).toInt()] }
        val base = GrayImage.filled(img.width + 2 * mx, img.height + 2 * my, paper)
        for (y in 0 until img.height) for (x in 0 until img.width) base[x + mx, y + my] = img[x, y]
        return (0 until n).map { k ->
            val a = Math.toRadians(rnd.nextDouble(-1.5, 1.5)); val s = rnd.nextDouble(0.94, 1.06)
            val dx = rnd.nextDouble(-0.02, 0.02) * base.width; val dy = rnd.nextDouble(-0.03, 0.03) * base.height
            val cx = base.width / 2.0; val cy = base.height / 2.0
            val o = GrayImage.filled(base.width, base.height, paper)
            val ca = cos(a) / s; val sa = sin(a) / s
            for (y in 0 until o.height) for (x in 0 until o.width) {
                val u = x - cx - dx; val v = y - cy - dy
                o[x, y] = base.sample(ca * u + sa * v + cx, -sa * u + ca * v + cy, paper).roundToInt().coerceIn(0, 255)
            }
            var d = o
            // The first frame is usually the sharpest; later ones vary.
            val sigma = if (k == 0) rnd.nextDouble(0.0, 0.5) else rnd.nextDouble(0.3, 1.4)
            if (sigma > 0.2) d = Filters.gaussianBlur(d, sigma)
            if (rnd.nextDouble() < 0.35) {
                val gx = rnd.nextDouble() * d.width; val gy = rnd.nextDouble() * d.height
                val r = rnd.nextDouble(0.08, 0.2) * d.width; val g = rnd.nextDouble(0.25, 0.6)
                val e = d.copy()
                for (y in 0 until e.height) for (x in 0 until e.width) {
                    val w = g * exp(-((x - gx) * (x - gx) + (y - gy) * (y - gy)) / (2 * r * r))
                    if (w > 0.01) e[x, y] = (e[x, y] + (255 - e[x, y]) * w).roundToInt()
                }
                d = e
            }
            val sd = rnd.nextDouble(2.0, 6.0)
            val out = d.copy(); val jr = java.util.Random(rnd.nextLong())
            for (i in out.data.indices) {
                val v = (out.data[i].toInt() and 0xFF) + jr.nextGaussian() * sd
                out.data[i] = v.roundToInt().coerceIn(0, 255).toByte()
            }
            out
        }
    }

    private fun load(f: File): GrayImage {
        val bi = ImageIO.read(f)
        val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return GrayImage.fromArgb(bi.width, bi.height, px)
    }
}
