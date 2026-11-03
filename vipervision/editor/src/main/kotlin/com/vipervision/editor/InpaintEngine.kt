package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The full-size work behind Object Erase and Object Move, with LaMa (see [ObjectEraser] for how it fills) and masks
 * from Smart select (EdgeSAM) made at preview size: white on black, any size, enlarged here.
 */
object InpaintEngine {

    /** Erases each selection in turn (each fills best on its own, at the sharpest size). */
    fun erase(image: Bitmap, selections: List<Bitmap>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
        var current = image
        selections.forEachIndexed { i, selection ->
            onProgress(i + 1, selections.size)
            val next = ObjectEraser.eraseMask(current, selection)
            if (current !== image) current.recycle()
            current = next
        }
        return if (current === image) image.copy(Bitmap.Config.ARGB_8888, true) else current
    }

    /**
     * Moves what [selection] covers by [offset] (fractions of the photo's width and height) and
     * resizes it by [scale] around its centre; the spot it left is filled in.
     */
    fun move(image: Bitmap, selection: Bitmap, offset: android.graphics.PointF, scale: Float): Bitmap {
        val w = image.width
        val h = image.height
        val mask = Bitmap.createScaledBitmap(selection, w, h, true)
        try {
            val bounds = maskBounds(mask, 16) ?: return image.copy(Bitmap.Config.ARGB_8888, true)
            // The object on its own: the photo, see-through outside the selection (its soft edge kept).
            val piece = Bitmap.createBitmap(image, bounds.left, bounds.top, bounds.width(), bounds.height()).copy(Bitmap.Config.ARGB_8888, true)
            piece.setHasAlpha(true)
            val pieceMask = Bitmap.createBitmap(mask, bounds.left, bounds.top, bounds.width(), bounds.height())
            Canvas(piece).drawBitmap(pieceMask, 0f, 0f, Paint().apply {
                colorFilter = RED_TO_ALPHA
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            if (pieceMask !== mask) pieceMask.recycle()
            // Where it was: filled in.
            val result = ObjectEraser.eraseMask(image, mask)
            val cx = bounds.exactCenterX() + offset.x * w
            val cy = bounds.exactCenterY() + offset.y * h
            val dw = bounds.width() * scale
            val dh = bounds.height() * scale
            Canvas(result).drawBitmap(
                piece, null, RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
            piece.recycle()
            return result
        } finally {
            mask.recycle()
        }
    }

    /** Bounding box of the mask's pixels brighter than [level], or null. */
    fun maskBounds(mask: Bitmap, level: Int = 128): Rect? {
        val row = IntArray(mask.width)
        var l = mask.width
        var t = mask.height
        var r = -1
        var b = -1
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, mask.width, 0, y, mask.width, 1)
            for (x in row.indices) {
                if (Color.red(row[x]) >= level) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    private val RED_TO_ALPHA = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f
            )
        )
    )
}
