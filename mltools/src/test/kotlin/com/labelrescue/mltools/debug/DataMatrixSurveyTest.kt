package com.labelrescue.mltools.debug

import com.google.zxing.BarcodeFormat
import com.labelrescue.core.barcode.DataMatrixRescue
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.usps.ImpbParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Survey of the DataMatrix codes in a folder of label photos (DM_DIR): per photo, what plain
 * ZXing reads and what [DataMatrixRescue] adds, on the whole photo and on the pipeline's tiles.
 * A file named <tracking>_*.png gives the truth. Writes build/dm-survey.txt. Local data only.
 */
class DataMatrixSurveyTest {
    @Test fun survey() {
        val dir = System.getenv("DM_DIR")?.let { File(it) }
        assumeTrue("set DM_DIR", dir != null && dir.isDirectory)
        val zx = ZxingDecoder(listOf(BarcodeFormat.DATA_MATRIX))
        val out = StringBuilder()
        var photos = 0; var zxN = 0; var rescueN = 0; var added = 0; var wrong = 0
        for (f in dir!!.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }.sortedBy { it.path }) {
            val bi = try { ImageIO.read(f) } catch (e: Exception) { null } ?: continue
            val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
            var img = GrayImage.fromArgb(bi.width, bi.height, px)
            if (System.getenv("DM_LIMIT") != null && maxOf(img.width, img.height) > 2400) img = img.limited(2400)
            photos++
            val truth = f.name.substringBefore('_').takeIf { it.length >= 20 && it.all(Char::isDigit) }
            val w = img.width; val h = img.height; val tw = (w * 0.6).toInt(); val th = (h * 0.6).toInt()
            val areas = listOf(IntRect(0, 0, w, h), IntRect(0, 0, tw, th), IntRect(w - tw, 0, w, th), IntRect(0, h - th, tw, h), IntRect(w - tw, h - th, w, h), IntRect((w - tw) / 2, (h - th) / 2, (w + tw) / 2, (h + th) / 2))
            val zxRead = LinkedHashSet<String>(); val rsRead = LinkedHashSet<String>()
            var detail = ""
            for (a in areas) {
                val c = img.crop(a)
                for (r in zx.decode(c)) ImpbParser.extractFromPayload(r.text).firstOrNull { it.checkValid }?.let { zxRead += it.tracking }
                DataMatrixRescue.decode(c)?.let { r -> ImpbParser.extractFromPayload(r.text).firstOrNull { it.checkValid }?.let { rsRead += it.tracking; detail = " (${r.erasures} erased, ${r.errors} fixed)" } }
            }
            if (zxRead.isNotEmpty()) zxN++
            if (rsRead.isNotEmpty()) rescueN++
            val gained = rsRead - zxRead
            if (gained.isNotEmpty()) added++
            if (truth != null && (rsRead - setOf(truth)).isNotEmpty()) wrong++
            out.append("${f.relativeTo(dir).path}: ZXing ${zxRead.ifEmpty { listOf("-") }}; rescue ${rsRead.ifEmpty { listOf("-") }}$detail${if (gained.isNotEmpty()) "  <== ADDED" else ""}${truth?.let { " truth $it" } ?: ""}\n")
        }
        out.append("\n$photos photos: ZXing read a DataMatrix in $zxN, the rescue in $rescueN, rescue-only $added, wrong vs truth $wrong\n")
        File("build/dm-survey.txt").writeText(out.toString())
        println(out)
    }
}
