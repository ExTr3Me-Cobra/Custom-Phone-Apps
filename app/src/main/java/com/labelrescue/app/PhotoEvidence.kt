package com.labelrescue.app

import android.graphics.Bitmap
import com.labelrescue.core.fusion.RecoveryOutput

/** What the photo pipeline found, plus image crops so the user can check uncertain digits. */
data class PhotoEvidence(
    val output: RecoveryOutput,
    /** Zoomed crop of each tracking position (printed digit, or the barcode symbol holding it). */
    val crops: Map<Int, Bitmap>,
)
