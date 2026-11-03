package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import kotlin.math.max

/**
 * An edit that changes the photo's pixels and runs before rotate/crop/adjustments: an AI tool, or
 * painting out a spot or object. Results are cached per sequence of steps, so undo is instant.
 */
sealed interface PixelStep {
    data class Ai(val tool: AiTool, val modelName: String) : PixelStep

    /**
     * Fills in the painted area: with Image Toolbox's content-aware fill (OpenCV, [generative] =
     * false, the Heal bubble) or its LaMa AI model ([generative] = true, the Object eraser).
     */
    data class Heal(
        val strokes: List<BrushStroke>,
        val generative: Boolean,
        /** Set when tapped selections or Auto Borders are part of it (they aren't in [strokes]). */
        val selectionId: Long = 0L
    ) : PixelStep

    /** The Cut out tool's result (background removed by models, brushes and/or Smart select). */
    data class CutOut(val id: Long = System.nanoTime()) : PixelStep

    /** Advanced crop's result; [alpha] for shaped crops (transparent corners). */
    data class Crop(val alpha: Boolean, val id: Long = System.nanoTime()) : PixelStep

    /**
     * A photo edited in Samsung's editor and brought back. It already has the rotate/crop and
     * adjustments in it, so this step resets those (see EditorActivity.importFromSamsung).
     */
    data class Samsung(val tool: SamsungTool, val id: Long = System.nanoTime()) : PixelStep

    /** The Draw tool's (or Markup layers') result. */
    data class Drawing(val id: Long = System.nanoTime()) : PixelStep

    /** A model from "All AI models"; [alpha] when its result has transparency. */
    data class AiModel(val modelName: String, val alpha: Boolean, val id: Long = System.nanoTime()) : PixelStep
}

/**
 * One finger stroke of the heal / erase brush, in the coordinates of the upright photo before
 * rotate and crop: points as fractions (0..1) of its width and height, [radius] as a fraction of
 * its longer side. That makes the same stroke work on the preview and on the full-size photo.
 */
data class BrushStroke(val points: List<Offset>, val radius: Float)

/**
 * A stroke being painted on screen: points as fractions of the shown photo, radius in screen pixels.
 * [exclude] marks Cut out's "not this" brush.
 */
data class ScreenStroke(val points: List<Offset>, val radiusPx: Float, val exclude: Boolean = false)

/**
 * Maps a point on the shown photo (fractions of the rotated and cropped image) back to the upright
 * photo before rotate and crop. Undoes the crop, then the rotation, then the mirror (the reverse
 * of [transformed], which mirrors, then rotates, then crops).
 */
fun Geometry.toSource(point: Offset): Offset {
    var x = crop.left + point.x * crop.width
    var y = crop.top + point.y * crop.height
    repeat(quarterTurns % 4) {
        // One clockwise turn maps (x, y) to (1 - y, x); this is its inverse.
        val nx = y
        val ny = 1f - x
        x = nx
        y = ny
    }
    if (flipped) x = 1f - x
    return Offset(x, y)
}

/**
 * Converts strokes painted on the shown photo into [BrushStroke]s on the upright source photo.
 * [shownWidthPx] is how wide the photo appears on screen; [sourceWidth] x [sourceHeight] is the
 * upright photo's size (any resolution: only the proportions matter).
 */
fun Geometry.toSourceStrokes(
    strokes: List<ScreenStroke>,
    shownWidthPx: Float,
    sourceWidth: Int,
    sourceHeight: Int
): List<BrushStroke> {
    val (turnedWidth, _) = turnedSize(sourceWidth, sourceHeight)
    // Source pixels per screen pixel, then as a fraction of the source's longer side.
    val sourcePxPerScreenPx = crop.width * turnedWidth / shownWidthPx
    val longSide = max(sourceWidth, sourceHeight).toFloat()
    return strokes.map { stroke ->
        BrushStroke(
            points = stroke.points.map(::toSource),
            radius = stroke.radiusPx * sourcePxPerScreenPx / longSide
        )
    }
}

/** White-on-black mask of the strokes at [width] x [height], as both of Image Toolbox's heal engines expect. */
fun renderMask(strokes: List<BrushStroke>, width: Int, height: Int): Bitmap {
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mask)
    canvas.drawColor(android.graphics.Color.BLACK)
    val longSide = max(width, height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
        val radius = stroke.radius * longSide
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            val p = stroke.points.first()
            canvas.drawCircle(p.x * width, p.y * height, radius, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 2f
            val path = Path()
            stroke.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * width, p.y * height) else path.lineTo(p.x * width, p.y * height)
            }
            canvas.drawPath(path, paint)
        }
    }
    return mask
}
