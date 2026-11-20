package com.labelrescue.core.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect

/** ZXing as the second decoder: Code 128 and DataMatrix, two binarizers, try-harder. */
class ZxingDecoder(private val formats: List<BarcodeFormat> = listOf(BarcodeFormat.CODE_128, BarcodeFormat.DATA_MATRIX, BarcodeFormat.MAXICODE)) : BarcodeDecoder {

    private class GraySource(private val img: GrayImage) : LuminanceSource(img.width, img.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val r = if (row == null || row.size < width) ByteArray(width) else row
            System.arraycopy(img.data, y * width, r, 0, width)
            return r
        }
        override fun getMatrix(): ByteArray = img.data
    }

    private fun hints(pure: Boolean) = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to formats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ASSUME_GS1 to true,
    ) + if (pure) mapOf(DecodeHintType.PURE_BARCODE to true) else emptyMap()

    override fun decode(img: GrayImage): List<BarcodeRead> {
        val out = LinkedHashMap<String, BarcodeRead>()
        val source = GraySource(img)
        for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            val bitmap = BinaryBitmap(binarizer)
            val reader = MultiFormatReader()
            try {
                val results = GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints(false))
                for (r in results) add(out, r)
            } catch (_: Exception) {
            }
            if (out.isEmpty()) {
                try { add(out, reader.decode(bitmap, hints(true))) } catch (_: Exception) {}
            }
            if (out.isNotEmpty()) break
        }
        return out.values.toList()
    }

    private fun add(out: MutableMap<String, BarcodeRead>, r: Result) {
        val kind = when (r.barcodeFormat) {
            BarcodeFormat.CODE_128 -> BarcodeKind.CODE_128
            BarcodeFormat.DATA_MATRIX -> BarcodeKind.DATA_MATRIX
            else -> BarcodeKind.OTHER
        }
        val pts = r.resultPoints?.filterNotNull().orEmpty()
        val box = if (pts.isNotEmpty()) IntRect(
            pts.minOf { it.x }.toInt(), pts.minOf { it.y }.toInt(), pts.maxOf { it.x }.toInt() + 1, pts.maxOf { it.y }.toInt() + 1,
        ) else null
        out.putIfAbsent(r.text, BarcodeRead(kind, r.text, "ZXing", box))
    }
}
