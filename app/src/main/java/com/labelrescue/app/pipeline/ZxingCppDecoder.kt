package com.labelrescue.app.pipeline

import com.labelrescue.core.barcode.BarcodeDecoder
import com.labelrescue.core.barcode.BarcodeKind
import com.labelrescue.core.barcode.BarcodeRead
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import zxingcpp.BarcodeReader

/**
 * zxing-cpp (Apache-2.0): the maintained C++ ZXing, generally more tolerant of blur and damage
 * than the Java port, for Code 128 and DataMatrix. A third full decoder next to ML Kit and ZXing.
 */
class ZxingCppDecoder : BarcodeDecoder {
    private val reader = BarcodeReader().apply {
        options.formats = setOf(BarcodeReader.Format.CODE_128, BarcodeReader.Format.DATA_MATRIX, BarcodeReader.Format.MAXI_CODE)
        options.tryHarder = true
        options.tryRotate = true
        options.tryInvert = true
        options.tryDownscale = true
        options.tryDenoise = true
    }

    @Synchronized
    override fun decode(img: GrayImage): List<BarcodeRead> {
        val bmp = Images.toBitmap(img)
        return try {
            reader.read(bmp, android.graphics.Rect(), 0).mapNotNull { r ->
                val text = r.text ?: return@mapNotNull null
                val kind = when (r.format) {
                    BarcodeReader.Format.CODE_128 -> BarcodeKind.CODE_128
                    BarcodeReader.Format.DATA_MATRIX -> BarcodeKind.DATA_MATRIX
                    else -> BarcodeKind.OTHER
                }
                val p = r.position
                val box = p?.let {
                    val xs = listOf(it.topLeft.x, it.topRight.x, it.bottomLeft.x, it.bottomRight.x)
                    val ys = listOf(it.topLeft.y, it.topRight.y, it.bottomLeft.y, it.bottomRight.y)
                    IntRect(xs.min(), ys.min(), xs.max(), ys.max())
                }
                BarcodeRead(kind, text, "zxing-cpp", box)
            }
        } catch (e: Throwable) {
            emptyList()
        } finally {
            bmp.recycle()
        }
    }
}
