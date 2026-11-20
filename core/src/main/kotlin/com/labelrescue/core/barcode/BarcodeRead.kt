package com.labelrescue.core.barcode

import com.labelrescue.core.image.IntRect

enum class BarcodeKind { CODE_128, DATA_MATRIX, OTHER }

/** A full decode from ML Kit or ZXing. [box] is in the coordinates of the image that was decoded. */
data class BarcodeRead(val kind: BarcodeKind, val text: String, val decoder: String, val box: IntRect? = null)

/** A full-barcode decoder (ML Kit adapter on Android, ZXing everywhere). */
fun interface BarcodeDecoder {
    fun decode(img: com.labelrescue.core.image.GrayImage): List<BarcodeRead>
}
