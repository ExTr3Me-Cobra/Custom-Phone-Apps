package com.labelrescue.app.pipeline

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.labelrescue.core.barcode.BarcodeDecoder
import com.labelrescue.core.barcode.BarcodeKind
import com.labelrescue.core.barcode.BarcodeRead
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import java.util.concurrent.TimeUnit

/** ML Kit Barcode Scanning (bundled model): Code 128 and DataMatrix. Call off the main thread. */
class MlKitBarcode : BarcodeDecoder, AutoCloseable {
    private val client get() = shared

    companion object {
        /** One scanner for the app's life, never closed (see [MlKitText]). */
        private val shared by lazy {
            BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX).build())
        }
    }

    override fun decode(img: GrayImage): List<BarcodeRead> {
        val input = InputImage.fromByteArray(Images.toNv21(img), img.width, img.height, 0, InputImage.IMAGE_FORMAT_NV21)
        val result = client.process(input).awaitFully(20_000) ?: return emptyList()
        return result.mapNotNull { b ->
            val text = b.rawValue ?: b.displayValue ?: return@mapNotNull null
            val kind = when (b.format) {
                Barcode.FORMAT_CODE_128 -> BarcodeKind.CODE_128
                Barcode.FORMAT_DATA_MATRIX -> BarcodeKind.DATA_MATRIX
                else -> BarcodeKind.OTHER
            }
            BarcodeRead(kind, text, "ML Kit", b.boundingBox?.let { IntRect(it.left, it.top, it.right, it.bottom) })
        }
    }

    /** Nothing to close: the scanner is shared and lives as long as the app. */
    override fun close() {}
}
