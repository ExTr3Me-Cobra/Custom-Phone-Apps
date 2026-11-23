package com.labelrescue.core.usps

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import com.google.zxing.oned.Code128Writer

/**
 * Scannable USPS barcodes for a recovered number, drawn the way USPS labels carry them
 * (Publication 199): the IMpb as GS1-128 (Code 128, subset C, FNC1 first) and the IMmb as a GS1
 * DataMatrix, both holding "420" + ZIP (when the ZIP is known) and the tracking number.
 */
object BarcodeImages {
    /** ZXing's escape for FNC1 inside Code 128 contents. */
    private const val FNC1 = 'ñ'
    /** The GS1 field separator in DataMatrix contents. */
    private const val GS = '\u001d'

    /** The IMpb (GS1-128): one module per pixel column, quiet zones included. */
    fun impb(tracking: String, zip: String? = null): BitMatrix {
        val contents = (if (zip != null) "${FNC1}420$zip" else "") + "$FNC1$tracking"
        return Code128Writer().encode(contents, BarcodeFormat.CODE_128, 0, 0, mapOf(EncodeHintType.MARGIN to 10))
    }

    /** The IMmb (GS1 DataMatrix, square). */
    fun immb(tracking: String, zip: String? = null): BitMatrix {
        val contents = (if (zip != null) "420$zip$GS" else "") + tracking
        return DataMatrixWriter().encode(
            contents, BarcodeFormat.DATA_MATRIX, 0, 0,
            mapOf(EncodeHintType.GS1_FORMAT to true, EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE, EncodeHintType.MARGIN to 2),
        )
    }
}
