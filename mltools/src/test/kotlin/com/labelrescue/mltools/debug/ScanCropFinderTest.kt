package com.labelrescue.mltools.debug

import com.labelrescue.core.barcode.ScanCropFinder
import com.labelrescue.core.image.GrayImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Writes the crops [ScanCropFinder] finds in each photo of CROP_DIR to CROP_OUT (local data only). */
class ScanCropFinderTest {
    @Test fun crops() {
        val dir = System.getenv("CROP_DIR")?.let { File(it) }; val outDir = System.getenv("CROP_OUT")?.let { File(it) }
        assumeTrue(dir != null && outDir != null)
        outDir!!.mkdirs()
        for (f in dir!!.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("png", "jpg") }.sortedBy { it.name }) {
            val bi = ImageIO.read(f) ?: continue
            val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
            val img = GrayImage.fromArgb(bi.width, bi.height, px)
            val found = ScanCropFinder.find(img)
            println("${f.name}: " + found.joinToString { "${it.kind} ${it.rect.width}x${it.rect.height}" })
            for (c in found) ImageIO.write(bi.getSubimage(c.rect.left, c.rect.top, c.rect.width, c.rect.height), "png", File(outDir, "${f.nameWithoutExtension}-${c.kind}.png"))
        }
    }
}
