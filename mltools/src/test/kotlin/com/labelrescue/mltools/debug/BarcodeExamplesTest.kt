package com.labelrescue.mltools.debug

import com.labelrescue.core.barcode.Code128Partial
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.usps.ImpbParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The app's exported damaged-barcode examples (BC_DIR: barcode_examples/ with barcodes.csv): per
 * crop, whether ZXing reads it, and where the true number ranks from the barcode alone (no OCR).
 */
class BarcodeExamplesTest {
    @Test fun examples() {
        val dir = System.getenv("BC_DIR")?.let { File(it) }
        assumeTrue(dir != null && File(dir, "barcodes.csv").exists())
        val out = StringBuilder(); var n = 0; var zx = 0; var top1 = 0; var top10 = 0
        for (row in File(dir!!, "barcodes.csv").readLines().drop(1).filter { it.isNotBlank() }) {
            val p = row.split(','); val f = File(dir, p[0]); val truth = p[1]
            if (!f.exists()) continue
            val bi = ImageIO.read(f) ?: continue
            val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
            val img = GrayImage.fromArgb(bi.width, bi.height, px)
            n++
            val full = ZxingDecoder().decode(img).any { r -> ImpbParser.extractFromPayload(r.text).any { it.tracking == truth } }
            if (full) zx++
            val o = RecoveryPipeline(listOf(ZxingDecoder()), null).run(listOf(img), RecoveryPipeline.Options(maxFrames = 1))
            val fm = o.format
            val ranked = if (fm != null && o.fullDecode == null) CandidateEngine().rank(fm, o.slots, o.prior(HistoryStats.EMPTY), o.exactEvidence()) else null
            val rank = if (o.fullDecode?.tracking == truth) 1 else ranked?.candidates?.indexOfFirst { it.tracking == truth }?.takeIf { it >= 0 }?.plus(1)
            if (rank == 1) top1++; if (rank != null && rank <= 10) top10++
            out.append("${p[0]}: ZXing ${if (full) "yes" else "no"}, barcode-only rank ${rank ?: "-"}; best ${ranked?.best?.tracking ?: o.fullDecode?.tracking}\n")
        }
        out.append("\n$n examples: ZXing reads $zx; barcode-only true number first $top1, top 10 $top10\n")
        File("build/barcode-examples.txt").writeText(out.toString()); println(out)
    }
}
