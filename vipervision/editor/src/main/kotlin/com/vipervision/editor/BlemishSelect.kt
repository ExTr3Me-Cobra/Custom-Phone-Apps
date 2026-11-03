package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.t8rin.neural_tools.bgremover.EdgeSAM
import com.t8rin.neural_tools.bgremover.MobileSAM
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * The Blemish remover's Smart select (EdgeSAM): tapping picks a whole object, and Auto Borders
 * snaps what was painted to the edges of the object under it. Both make masks the size of the
 * photo as shown, which [toSourceMask] turns into a mask on the full-size upright photo.
 */
object BlemishSelect {

    /** Prepares Smart select on [shown] (a second or two); later taps are quick. */
    suspend fun prepare(session: PaintSession, shown: Bitmap) = session.samLock.withLock {
        if (session.sam == null || session.selectionsOf !== shown) {
            session.sam?.close()
            session.sam = EdgeSAM.openSession(shown)
            session.selectionsOf = shown
        }
    }

    /**
     * A tap at [point] (fractions of the shown photo): unselects the selection there, or picks
     * the object there.
     */
    suspend fun tap(session: PaintSession, shown: Bitmap, point: Offset) {
        val hit = session.selections.indexOfLast { valueAt(it, point) > 128 }
        if (hit >= 0) {
            session.selections.removeAt(hit)
            session.history.lastIndexOf(true).takeIf { it >= 0 }?.let { session.history.removeAt(it) }
            return
        }
        prepare(session, shown)
        val mask = segment(session, shown, listOf(MobileSAM.Point(point.x, point.y, positive = true)), null)
        session.addSelection(mask)
    }

    /**
     * Auto Borders: each group of strokes that touch becomes Smart select's outline of the object
     * they cover, kept to near where was painted (so it doesn't run off over a whole wall).
     */
    suspend fun autoBorders(session: PaintSession, shown: Bitmap) {
        val strokes = session.strokes.toList()
        if (strokes.isEmpty() || session.shownWidthPx <= 0f) return
        prepare(session, shown)
        val aspect = shown.width.toFloat() / shown.height
        for (group in groups(strokes, session.shownWidthPx, aspect)) {
            val (points, box) = samPrompt(group, session.shownWidthPx, aspect)
            val outline = segment(session, shown, points, box)
            // Near the painting only: the painting grown by about a fifth of its size.
            val painted = strokeMask(group, session.shownWidthPx, shown)
            val bounds = maskBounds(painted)
            val reach = bounds?.let { max(it.width(), it.height()) * 0.2f } ?: 0f
            val near = with(ObjectEraser) { painted.grownBy(max(reach, 6f)) }
            painted.recycle()
            Canvas(outline).drawBitmap(near, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN) })
            near.recycle()
            if (maskBounds(outline) == null) {
                outline.recycle()
                continue
            }
            session.strokes.removeAll(group)
            session.addSelection(outline)
        }
        // Undo order: what's left of the painting, then the selections.
        session.history.clear()
        repeat(session.strokes.size) { session.history.add(false) }
        repeat(session.selections.size) { session.history.add(true) }
    }

    /** Strokes that touch or overlap, together. */
    private fun groups(strokes: List<ScreenStroke>, shownWidthPx: Float, aspect: Float): List<List<ScreenStroke>> {
        fun box(s: ScreenStroke): RectF {
            val rx = s.radiusPx / shownWidthPx
            val ry = rx * aspect
            return RectF(s.points.minOf { it.x } - rx, s.points.minOf { it.y } - ry, s.points.maxOf { it.x } + rx, s.points.maxOf { it.y } + ry)
        }
        val groups = mutableListOf<Pair<RectF, MutableList<ScreenStroke>>>()
        for (stroke in strokes) {
            val b = box(stroke)
            val touching = groups.filter { RectF.intersects(it.first, b) }
            if (touching.isEmpty()) {
                groups += b to mutableListOf(stroke)
            } else {
                val first = touching.first()
                first.first.union(b)
                first.second += stroke
                touching.drop(1).forEach { other ->
                    first.first.union(other.first)
                    first.second += other.second
                    groups.remove(other)
                }
            }
        }
        return groups.map { it.second }
    }

    /** Smart select's outline for the prompt, white on black at [shown]'s size. */
    private suspend fun segment(session: PaintSession, shown: Bitmap, points: List<MobileSAM.Point>, box: RectF?): Bitmap {
        val white = Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val seg = try {
            session.samLock.withLock { (session.sam ?: error("Smart select isn't ready")).segment(points = points, box = box, outputImage = white) }
        } finally {
            white.recycle()
        }
        val out = Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(seg, 0f, 0f, null)
        }
        seg.recycle()
        return out
    }

    /** The painted strokes as a mask at [shown]'s size. */
    private fun strokeMask(strokes: List<ScreenStroke>, shownWidthPx: Float, shown: Bitmap): Bitmap {
        val long = max(shown.width, shown.height).toFloat()
        val perScreenPx = shown.width / shownWidthPx
        return renderMask(strokes.map { BrushStroke(it.points, it.radiusPx * perScreenPx / long) }, shown.width, shown.height)
    }

    /** The red, half see-through tint of all [selections] (null if none). */
    fun tint(selections: List<Bitmap>): Bitmap? {
        val first = selections.firstOrNull() ?: return null
        val out = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 59f,
                        0f, 0f, 0f, 0f, 48f,
                        0.55f, 0f, 0f, 0f, 0f
                    )
                )
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
        }
        selections.forEach { canvas.drawBitmap(it, 0f, 0f, paint) }
        return out
    }

    /**
     * The [selections] (made on the photo as shown: rotated, flipped, cropped) as one mask on the
     * upright photo before those, at [width] × [height].
     */
    fun toSourceMask(selections: List<Bitmap>, geometry: Geometry, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val first = selections.firstOrNull() ?: return out
        val sw = first.width.toFloat()
        val sh = first.height.toFloat()
        // Three corners of the shown photo and where they are on the upright one.
        val corners = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f))
        val src = floatArrayOf(0f, 0f, sw, 0f, 0f, sh)
        val dst = FloatArray(6)
        corners.forEachIndexed { i, c ->
            val p = geometry.toSource(c)
            dst[i * 2] = p.x * width
            dst[i * 2 + 1] = p.y * height
        }
        val matrix = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 3) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN) }
        selections.forEach { canvas.drawBitmap(it, matrix, paint) }
        return out
    }

    private fun valueAt(mask: Bitmap, p: Offset): Int {
        val x = (p.x * mask.width).toInt().coerceIn(0, mask.width - 1)
        val y = (p.y * mask.height).toInt().coerceIn(0, mask.height - 1)
        return Color.red(mask.getPixel(x, y))
    }

    private fun maskBounds(mask: Bitmap): Rect? {
        val row = IntArray(mask.width)
        var l = mask.width
        var t = mask.height
        var r = -1
        var b = -1
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, mask.width, 0, y, mask.width, 1)
            for (x in row.indices) {
                if (Color.red(row[x]) >= 128) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }
}
