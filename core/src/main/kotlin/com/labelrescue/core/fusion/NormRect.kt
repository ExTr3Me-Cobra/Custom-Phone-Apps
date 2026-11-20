package com.labelrescue.core.fusion

import com.labelrescue.core.image.IntRect
import kotlin.math.roundToInt

/** Optional user crops, as fractions (0..1) of the frame. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toPixels(w: Int, h: Int) = IntRect((left * w).toInt(), (top * h).toInt(), (right * w).roundToInt(), (bottom * h).roundToInt())
}
