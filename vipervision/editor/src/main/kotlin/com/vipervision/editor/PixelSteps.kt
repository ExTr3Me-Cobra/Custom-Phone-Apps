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
data class BrushStroke(
    val points: List<Offset>,
    val radius: Float,
    /** The eraser: takes away what was painted before it. */
    val exclude: Boolean = false,
    /** A finished polygon: its inside is filled too. */
    val closed: Boolean = false
)

/**
 * A stroke being painted on screen: points as fractions of the shown photo, radius in screen pixels.
 * [exclude] marks Cut out's "not this" brush.
 */
data class ScreenStroke(
    val points: List<Offset>,
    val radiusPx: Float,
    val exclude: Boolean = false,
    val closed: Boolean = false,
    /** Made with the Polygon tool: its thickness follows the Polygon slider, even once finished. */
    val polygon: Boolean = false
)

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
            radius = stroke.radiusPx * sourcePxPerScreenPx / longSide,
            exclude = stroke.exclude,
            closed = stroke.closed
        )
    }
}

/**
 * Where a new polygon corner [p] goes: between the two neighbouring corners it's closest to (the
 * side whose detour through [p] is shortest), so the outline doesn't cross itself however the
 * corners are tapped. [sx]/[sy] turn the points' units into the same scale both ways.
 */
fun insertCorner(corners: MutableList<Offset>, p: Offset, sx: Float = 1f, sy: Float = 1f, order: MutableList<Int>? = null) {
    fun put(index: Int) {
        corners.add(index, p)
        // Newest last; later corners moved up by one.
        order?.let { o ->
            for (k in o.indices) if (o[k] >= index) o[k] = o[k] + 1
            o.add(index)
        }
    }
    if (corners.size < 3) {
        put(corners.size)
        return
    }
    fun d(a: Offset, b: Offset) = Offset((a.x - b.x) * sx, (a.y - b.y) * sy).getDistance()
    var best = 0
    var bestCost = Float.MAX_VALUE
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size]
        val cost = d(a, p) + d(p, b) - d(a, b)
        if (cost < bestCost) {
            bestCost = cost
            best = i
        }
    }
    put(best + 1)
}

/** Takes away the corner added last (see [insertCorner]'s order); false if none. */
fun removeNewestCorner(corners: MutableList<Offset>, order: MutableList<Int>): Offset? {
    val index = order.removeLastOrNull() ?: corners.lastIndex.takeIf { it >= 0 } ?: return null
    if (index !in corners.indices) return null
    val p = corners.removeAt(index)
    for (k in order.indices) if (order[k] > index) order[k] = order[k] - 1
    return p
}

/** White-on-black mask of the strokes at [width] x [height], as both of Image Toolbox's heal engines expect. */
fun renderMask(strokes: List<BrushStroke>, width: Int, height: Int): Bitmap {
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mask)
    canvas.drawColor(android.graphics.Color.BLACK)
    drawStrokes(canvas, strokes, width, height)
    return mask
}

/**
 * Draws [strokes] in order on a mask [width] x [height]: painting white, the eraser black (so it
 * takes away what's under it), closed polygons filled.
 */
fun drawStrokes(canvas: Canvas, strokes: List<BrushStroke>, width: Int, height: Int) {
    val longSide = max(width, height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
        paint.color = if (stroke.exclude) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val radius = stroke.radius * longSide
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            val p = stroke.points.first()
            canvas.drawCircle(p.x * width, p.y * height, radius, paint)
        } else {
            val path = Path()
            stroke.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * width, p.y * height) else path.lineTo(p.x * width, p.y * height)
            }
            if (stroke.closed) {
                path.close()
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 2f
            canvas.drawPath(path, paint)
        }
    }
}
