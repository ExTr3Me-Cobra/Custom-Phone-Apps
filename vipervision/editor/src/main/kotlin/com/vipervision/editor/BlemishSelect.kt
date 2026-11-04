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
import com.t8rin.neural_tools.bgremover.Sam2
import com.t8rin.neural_tools.bgremover.MobileSAM
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * The Blemish remover's Smart select (SAM 2.1): tapping picks a whole object, and Auto Borders
 * snaps what was painted to the edges of the object under it. Both make masks the size of the
 * photo as shown, which [toSourceMask] turns into a mask on the full-size upright photo.
 */
object BlemishSelect {

    /** Prepares Smart select on [shown] (a second or two); later taps are quick. */
    suspend fun prepare(session: PaintSession, shown: Bitmap) = session.samLock.withLock {
        if (session.sam == null || session.selectionsOf !== shown) {
            session.sam?.close()
            session.sam = Sam2.openSession(shown)
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
     * Auto Borders: each group of strokes that touch has its edge snapped to the object's real
     * edge, within a thin rim (see [EdgeSnap]).
     */
    suspend fun autoBorders(session: PaintSession, shown: Bitmap) {
        val strokes = session.strokes.toList()
        if (strokes.isEmpty() || session.shownWidthPx <= 0f) return
        prepare(session, shown)
        val aspect = shown.width.toFloat() / shown.height
        for (group in groups(strokes, session.shownWidthPx, aspect)) {
            val (points, box) = samPrompt(group, session.shownWidthPx, aspect)
            val painted = strokeMask(group, session.shownWidthPx, shown)
            val bounds = maskBounds(painted)
            // Smart select's outline only hints where the object carries on past the painting;
            // the edge itself is found by colour in a thin rim (EdgeSnap), and the inside of the
            // painting always stays.
            val hint = runCatching { segment(session, shown, points, box, bounds) }
                .onFailure { ViperLog.error("PAINT", "Smart select hint failed", it) }.getOrNull()
            val radius = group.maxOf { it.radiusPx } * shown.width / session.shownWidthPx
            val outline = try {
                EdgeSnap.snap(shown, painted, hint, radius)
            } finally {
                painted.recycle()
                hint?.recycle()
            }
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

    /**
     * Smart select's outline for the prompt, white on black at [shown]'s size. Two passes: the
     * whole photo finds the object, then a crop around just the object (Smart select always
     * looks at 1024 px and outlines on a 256 grid, so on the crop that grid is far finer).
     */
    private suspend fun segment(session: PaintSession, shown: Bitmap, points: List<MobileSAM.Point>, box: RectF?, extra: Rect? = null): Bitmap {
        val coarse = session.samLock.withLock {
            raw((session.sam ?: error("Smart select isn't ready")), shown, points, box)
        }
        solidify(coarse, EDGE_LEVEL)
        val out = runCatching { zoomed(shown, coarse, points, box, extra) }
            .onFailure { ViperLog.error("PAINT", "Smart select's close-up pass failed", it) }
            .getOrNull()?.also { coarse.recycle() } ?: coarse
        // Smart select is unsure right at the edge (its line sits a few pixels inside, leaving
        // the rim and contact shadow): anything it's a quarter sure of counts, and the outline
        // then reaches a little past the edge.
        val pad = max(EDGE_PAD_MIN, max(shown.width, shown.height) * EDGE_PAD_FRACTION)
        dilate(out, pad.toInt())
        return out
    }

    /** Smart select on [image]: how sure it is per pixel, as white on black at [image]'s size. */
    private fun raw(sam: Sam2.Session, image: Bitmap, points: List<MobileSAM.Point>, box: RectF?): Bitmap {
        val white = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val seg = try {
            sam.segment(points = points, box = box, outputImage = white)
        } finally {
            white.recycle()
        }
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(seg, 0f, 0f, null)
        }
        seg.recycle()
        return out
    }

    /**
     * The close-up pass: Smart select again on a crop around [coarse] (and [extra], e.g. the
     * painting), with the same prompt moved into the crop. Null when the object already fills
     * most of the photo (nothing to gain) or nothing was found.
     */
    private fun zoomed(shown: Bitmap, coarse: Bitmap, points: List<MobileSAM.Point>, box: RectF?, extra: Rect?): Bitmap? {
        val found = maskBounds(coarse) ?: return null
        extra?.let { found.union(it) }
        val long = max(shown.width, shown.height)
        if (max(found.width(), found.height()) > long * 0.7f) return null
        val pad = max(24, (max(found.width(), found.height()) * 0.15f).toInt())
        val crop = Rect(
            (found.left - pad).coerceAtLeast(0), (found.top - pad).coerceAtLeast(0),
            (found.right + pad).coerceAtMost(shown.width), (found.bottom + pad).coerceAtMost(shown.height)
        )
        val piece = Bitmap.createBitmap(shown, crop.left, crop.top, crop.width(), crop.height())
        fun inCrop(x: Float, y: Float) = Offset((x * shown.width - crop.left) / crop.width(), (y * shown.height - crop.top) / crop.height())
        val cropPoints = points.map { p -> inCrop(p.x, p.y).let { MobileSAM.Point(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f), p.positive) } }
        // The box: the one given, or what the first pass found (a box helps it take the whole object).
        val b = box?.let { RectF(it.left * shown.width, it.top * shown.height, it.right * shown.width, it.bottom * shown.height) }
            ?: RectF(maskBounds(coarse)!!)
        val cropBox = RectF(
            ((b.left - crop.left) / crop.width()).coerceIn(0f, 1f), ((b.top - crop.top) / crop.height()).coerceIn(0f, 1f),
            ((b.right - crop.left) / crop.width()).coerceIn(0f, 1f), ((b.bottom - crop.top) / crop.height()).coerceIn(0f, 1f)
        )
        val fine = try {
            Sam2.openSession(piece).use { sam -> raw(sam, piece, cropPoints, cropBox) }
        } finally {
            if (piece !== shown) piece.recycle()
        }
        solidify(fine, EDGE_LEVEL)
        if (maskBounds(fine) == null) {
            fine.recycle()
            return null
        }
        val out = Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(fine, crop.left.toFloat(), crop.top.toFloat(), null)
        }
        fine.recycle()
        return out
    }

    /** Shrinks the white of [mask] by [radius] px. */
    private fun erode(mask: Bitmap, radius: Int) {
        invert(mask)
        dilate(mask, radius)
        invert(mask)
    }

    private fun invert(mask: Bitmap) {
        val row = IntArray(mask.width)
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) row[x] = if (Color.red(row[x]) >= 128) Color.BLACK else Color.WHITE
            mask.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
    }

    /** Grows the white of [mask] by [radius] px (a round brush around every edge pixel). */
    private fun dilate(mask: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val on = BooleanArray(w * h) { Color.red(px[it]) >= 128 }
        // Distance per row, then per column (squared-distance chamfer is overkill here): a
        // pixel is on if any on pixel lies within the circle.
        val offsets = (-radius..radius).map { dy ->
            val half = kotlin.math.sqrt((radius * radius - dy * dy).toFloat()).toInt()
            dy to half
        }
        // Horizontal run length to the nearest on pixel, left and right, per pixel.
        val near = IntArray(w * h) { Int.MAX_VALUE }
        for (y in 0 until h) {
            var last = -1_000_000
            for (x in 0 until w) {
                if (on[y * w + x]) last = x
                near[y * w + x] = x - last
            }
            last = 1_000_000
            for (x in w - 1 downTo 0) {
                if (on[y * w + x]) last = x
                near[y * w + x] = minOf(near[y * w + x], last - x)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (on[y * w + x]) continue
                var hit = false
                for ((dy, half) in offsets) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    if (near[yy * w + x] <= half) { hit = true; break }
                }
                if (hit) px[y * w + x] = Color.WHITE
            }
        }
        mask.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** White where the mask is at least [level], black elsewhere. */
    private fun solidify(mask: Bitmap, level: Int) {
        val row = IntArray(mask.width)
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) row[x] = if (Color.red(row[x]) >= level) Color.WHITE else Color.BLACK
            mask.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
    }

    /** How sure Smart select must be (0-255) for a pixel to count. */
    private const val EDGE_LEVEL = 64

    /** How far past the edge the outline reaches: 0.5% of the photo's long side, at least 3 px. */
    private const val EDGE_PAD_FRACTION = 0.005f
    private const val EDGE_PAD_MIN = 3f

    /** The painted strokes as a mask at [shown]'s size. */
    private fun strokeMask(strokes: List<ScreenStroke>, shownWidthPx: Float, shown: Bitmap): Bitmap {
        val long = max(shown.width, shown.height).toFloat()
        val perScreenPx = shown.width / shownWidthPx
        return renderMask(strokes.map { BrushStroke(it.points, it.radiusPx * perScreenPx / long, it.exclude, it.closed) }, shown.width, shown.height)
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
