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

/** The sides AI inpaint's Expand can grow the photo on. */
enum class Side(val label: String) { Left("Left"), Right("Right"), Top("Top"), Bottom("Bottom") }

/**
 * The full-size work behind AI inpaint, with LaMa (see [ObjectEraser] for how it fills) and masks
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

    /**
     * The photo made bigger by [amount] (of its width or height) on each of [sides], the new edges
     * filled in. Each edge is filled in pieces along its length, each with the photo next to it
     * (and the pieces already filled) as context, so LaMa works near full size instead of on the
     * whole, much bigger picture shrunk down.
     */
    fun expand(image: Bitmap, sides: Set<Side>, amount: Float, onProgress: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
        // A 200 MP photo made 50% bigger each way wouldn't fit in memory: keep the result to 48 MP.
        val grow = 1f + amount * ((if (Side.Left in sides) 1 else 0) + (if (Side.Right in sides) 1 else 0))
        val growY = 1f + amount * ((if (Side.Top in sides) 1 else 0) + (if (Side.Bottom in sides) 1 else 0))
        val fit = sqrt(MAX_RESULT_PIXELS / (image.width * grow * image.height * growY)).coerceAtMost(1f)
        val source = if (fit < 1f) {
            Bitmap.createScaledBitmap(image, (image.width * fit).roundToInt(), (image.height * fit).roundToInt(), true)
        } else image
        val w = source.width
        val h = source.height
        val left = if (Side.Left in sides) (w * amount).roundToInt() else 0
        val right = if (Side.Right in sides) (w * amount).roundToInt() else 0
        val top = if (Side.Top in sides) (h * amount).roundToInt() else 0
        val bottom = if (Side.Bottom in sides) (h * amount).roundToInt() else 0
        val newW = w + left + right
        val newH = h + top + bottom
        val canvas = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply {
            drawColor(Color.BLACK)
            drawBitmap(source, left.toFloat(), top.toFloat(), null)
        }
        if (source !== image) source.recycle()
        // White where nothing is known yet.
        val unknown = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        Canvas(unknown).apply {
            drawColor(Color.WHITE)
            drawRect(Rect(left, top, left + w, top + h), Paint().apply { color = Color.BLACK })
        }
        // Left and right first (beside the photo), then top and bottom across the full width,
        // which takes in the corners.
        val holes = listOfNotNull(
            Rect(0, top, left, top + h).takeIf { left > 0 },
            Rect(left + w, top, newW, top + h).takeIf { right > 0 },
            Rect(0, 0, newW, top).takeIf { top > 0 },
            Rect(0, top + h, newW, newH).takeIf { bottom > 0 }
        )
        val pieces = holes.flatMap { chunks(it) }
        try {
            pieces.forEachIndexed { i, (piece, along) ->
                onProgress(i + 1, pieces.size)
                fillPiece(canvas, unknown, piece, along)
            }
        } finally {
            unknown.recycle()
        }
        return canvas
    }

    /** [hole] cut into pieces along its long side, each about as long as the hole is deep. */
    private fun chunks(hole: Rect): List<Pair<Rect, Boolean>> {
        val horizontal = hole.width() >= hole.height()
        val depth = if (horizontal) hole.height() else hole.width()
        val length = if (horizontal) hole.width() else hole.height()
        val step = max(384, depth * 3 / 2)
        val count = ((length + step - 1) / step).coerceAtLeast(1)
        val each = (length + count - 1) / count
        return (0 until count).map { i ->
            val a = i * each
            val b = min(length, a + each)
            val r = if (horizontal) Rect(hole.left + a, hole.top, hole.left + b, hole.bottom)
            else Rect(hole.left, hole.top + a, hole.right, hole.top + b)
            r to horizontal
        }
    }

    /** Fills [piece] of [canvas] with what's around it; everything still unknown nearby counts as a hole. */
    private fun fillPiece(canvas: Bitmap, unknown: Bitmap, piece: Rect, horizontal: Boolean) {
        val depth = if (horizontal) piece.height() else piece.width()
        val context = max(256, depth)
        val crop = Rect(
            (piece.left - context).coerceAtLeast(0),
            (piece.top - context).coerceAtLeast(0),
            (piece.right + context).coerceAtMost(canvas.width),
            (piece.bottom + context).coerceAtMost(canvas.height)
        )
        val image = Bitmap.createBitmap(canvas, crop.left, crop.top, crop.width(), crop.height())
        val mask = Bitmap.createBitmap(unknown, crop.left, crop.top, crop.width(), crop.height())
        val filled = try {
            ObjectEraser.fillOnly(image, mask)
        } finally {
            if (image !== canvas) image.recycle()
            if (mask !== unknown) mask.recycle()
        }
        // Only this piece is kept (the rest of the crop came back slightly softened).
        val inCrop = Rect(piece.left - crop.left, piece.top - crop.top, piece.right - crop.left, piece.bottom - crop.top)
        Canvas(canvas).drawBitmap(filled, inCrop, piece, null)
        filled.recycle()
        Canvas(unknown).drawRect(piece, Paint().apply { color = Color.BLACK })
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

    /** Biggest picture Expand makes, in pixels. */
    private const val MAX_RESULT_PIXELS = 48_000_000f

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
