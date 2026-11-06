package com.vipervision.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.awxkee.aire.Aire
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageExposureFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageWhiteBalanceFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One slider in the Adjust tab. Every value runs from -100 to 100, with 0 meaning "unchanged". */
enum class Adjustment(val label: String) {
    Brightness("Brightness"),
    Exposure("Exposure"),
    Contrast("Contrast"),
    Highlights("Highlights"),
    Shadows("Shadows"),
    Saturation("Saturation"),
    Warmth("Warmth"),
    Tint("Tint"),
    Sharpness("Sharpness"),
    Vignette("Vignette")
}

/** The current value of every adjustment. */
data class Adjustments(val values: Map<Adjustment, Int> = emptyMap()) {
    operator fun get(adjustment: Adjustment): Int = values[adjustment] ?: 0

    fun with(adjustment: Adjustment, value: Int): Adjustments =
        copy(values = values + (adjustment to value.coerceIn(-100, 100)))

    val isIdentity: Boolean get() = values.values.all { it == 0 }

    /** A slider value as -1..1. */
    fun t(adjustment: Adjustment): Float = this[adjustment] / 100f
}

/**
 * Applies [adjustments] to [source] using the same image libraries and filters as Image Toolbox:
 * Aire (native code) for brightness and contrast, and one combined GPU pass for saturation,
 * exposure, highlights and shadows, white balance, sharpness and vignette.
 *
 * Each slider's -100..100 is mapped onto a sensible part of the matching Image Toolbox filter's
 * range (noted below), so the ends of the dial are strong but still usable.
 */
fun applyAdjustments(context: Context, source: Bitmap, adjustments: Adjustments): Bitmap {
    if (adjustments.isIdentity) return source
    var bitmap = source

    // Image Toolbox "Brightness": Aire.brightness, bias -1..1 (0 = unchanged). Dial = ±0.6.
    adjustments.t(Adjustment.Brightness).takeIf { it != 0f }?.let {
        bitmap = Aire.brightness(bitmap = bitmap, bias = it * 0.6f)
    }
    // Image Toolbox "Contrast": Aire.contrast, gain 0..2 (1 = unchanged). Dial = 0.2..1.8.
    adjustments.t(Adjustment.Contrast).takeIf { it != 0f }?.let {
        bitmap = Aire.contrast(bitmap = bitmap, gain = 1f + it * 0.8f)
    }

    val gpuFilters = buildList<GPUImageFilter> {
        // Image Toolbox's GPU "Saturation" filter, 0..2 (1 = unchanged); it keeps brightness the
        // same. Dial = 0 (black and white)..2. (Aire's version with tone mapping darkened photos.)
        adjustments.t(Adjustment.Saturation).takeIf { it != 0f }?.let {
            add(GPUImageSaturationFilter(1f + it))
        }
        // Image Toolbox "Exposure": GPUImageExposureFilter, -4..4 EV (0 = unchanged). Dial = ±2 EV.
        adjustments.t(Adjustment.Exposure).takeIf { it != 0f }?.let {
            add(GPUImageExposureFilter(it * 2f))
        }
        // Image Toolbox "Highlights and shadows" (wide range), each 0..2 (1 = unchanged).
        val highlights = adjustments.t(Adjustment.Highlights)
        val shadows = adjustments.t(Adjustment.Shadows)
        if (highlights != 0f || shadows != 0f) {
            add(HighlightShadowWideRangeFilter(shadows = 1f + shadows, highlights = 1f + highlights))
        }
        // Image Toolbox "White balance": GPUImageWhiteBalanceFilter, temperature in K (5000 =
        // unchanged, higher is warmer) and tint -200..200 (0 = unchanged, positive is magenta).
        // Its shader is about 6.7x weaker above 5000 K (0.00006 per K) than below (0.0004 per K),
        // so the warm half of the dial goes further to be as strong as the cool half.
        val warmth = adjustments.t(Adjustment.Warmth)
        val tint = adjustments.t(Adjustment.Tint)
        if (warmth != 0f || tint != 0f) {
            val kelvin = if (warmth > 0f) 5000f + warmth * 13300f else 5000f + warmth * 2000f
            add(GPUImageWhiteBalanceFilter(kelvin, tint * 100f))
        }
        // Image Toolbox "Sharpen": GPUImageSharpenFilter, -1..1 in its slider (negative softens).
        adjustments.t(Adjustment.Sharpness).takeIf { it != 0f }?.let {
            add(GPUImageSharpenFilter(it))
        }
        // Image Toolbox "Vignette": GPUImageVignetteFilter (its default is start 0.3, end 0.75,
        // black). The dial moves the start inward from the corners; negative uses white.
        adjustments.t(Adjustment.Vignette).takeIf { it != 0f }?.let {
            val start = 0.85f - 0.55f * abs(it)
            val color = if (it > 0) floatArrayOf(0f, 0f, 0f) else floatArrayOf(1f, 1f, 1f)
            add(GPUImageVignetteFilter(PointF(0.5f, 0.5f), color, start, start + 0.45f))
        }
    }
    if (gpuFilters.isNotEmpty()) {
        // One GPU filter run at a time, app-wide (a live preview and a full-size save could
        // otherwise run together, and a GPU failure there closes the app).
        bitmap = synchronized(GpuLock) {
            GPUImage(context.applicationContext).run {
                setImage(bitmap)
                setFilter(gpuFilters.singleOrNull() ?: GPUImageFilterGroup(gpuFilters))
                bitmapWithFilterApplied
            }
        }
    }
    return bitmap
}

/** Serializes GPU filter runs (see applyAdjustments). */
private object GpuLock
