package com.labelrescue.mltools.debug

import com.labelrescue.core.barcode.DataMatrixRescue
import com.labelrescue.core.image.GrayImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class InkSquaresTest {
    @Test fun squares() {
        val path = System.getenv("PHOTO"); assumeTrue(path != null)
        val bi = ImageIO.read(File(path!!)); val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        val img = GrayImage.fromArgb(bi.width, bi.height, px)
        val sq = DataMatrixRescue.inkSquares(img)
        val lines = sq.map { b ->
            val m = b.width / 6
            val a = com.labelrescue.core.image.IntRect(b.left - m, b.top - m, b.right + m, b.bottom + m).intersect(com.labelrescue.core.image.IntRect(0, 0, img.width, img.height))
            "$b looks=${DataMatrixRescue.looksLikeDataMatrix(img, b, 0.68)} crop-decode=${DataMatrixRescue.decode(img.crop(a))?.text}"
        }
        val b = com.labelrescue.core.image.IntRect(346, 2690, 609, 2953)
        val m2 = listOf(0.05, 0.1, 0.17, 0.25, 0.3, 0.4, 0.5, 0.6).map { f -> val m = (b.width * f).toInt(); val a = com.labelrescue.core.image.IntRect(b.left - m, b.top - m, b.right + m, b.bottom + m); "$f:${DataMatrixRescue.decode(img.crop(a))?.text != null}" }
        val manual = DataMatrixRescue.decode(img.crop(com.labelrescue.core.image.IntRect(300, 2620, 700, 3000)))?.text
        File("build/ink-squares.txt").writeText(lines.joinToString("\n") + "\nmargins " + m2 + " manual=" + manual + " whole=" + DataMatrixRescue.decode(img)?.text)
    }
}
