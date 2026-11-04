package com.vipervision.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where [bitmap] sits when fitted into a [width] × [height] view with [pad] around it. */
private fun fitRect(bitmap: Bitmap, width: Int, height: Int, pad: Float): RectF {
    val w = width - 2 * pad
    val h = height - 2 * pad
    val scale = min(w / bitmap.width, h / bitmap.height)
    val bw = bitmap.width * scale
    val bh = bitmap.height * scale
    val left = pad + (w - bw) / 2f
    val top = pad + (h - bh) / 2f
    return RectF(left, top, left + bw, top + bh)
}

/**
 * The screenshot toolbar's Crop: the screenshot with a frame to drag by its corners or sides, or
 * move from inside. [cropped] gives the result.
 */
@SuppressLint("ViewConstructor")
class ShotCropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val density = resources.displayMetrics.density
    private val pad = 16 * density
    // Fractions of the screenshot: left, top, right, bottom.
    private var crop = RectF(0f, 0f, 1f, 1f)
    private var image = RectF()
    private var handle = 0
    private var last = 0f to 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 4 * density; strokeCap = Paint.Cap.SQUARE }
    private val grid = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1 * density }

    val isChanged get() = crop.left > 0.001f || crop.top > 0.001f || crop.right < 0.999f || crop.bottom < 0.999f

    fun reset() {
        crop = RectF(0f, 0f, 1f, 1f)
        ratio?.let { fitRatio(it) }
        invalidate()
    }

    /** The frame's shape kept (width / height of the result), or null for any shape. */
    var ratio: Float? = null
        set(value) {
            field = value
            value?.let { fitRatio(it) }
            invalidate()
        }

    /** Height (as a fraction) of a frame [w] wide (as a fraction) in the kept shape [r]. */
    private fun heightFor(w: Float, r: Float) = w * bitmap.width / (r * bitmap.height)

    /** The biggest frame of shape [r] around the current frame's middle. */
    private fun fitRatio(r: Float) {
        val cx = crop.centerX()
        val cy = crop.centerY()
        var w = 1f
        var h = heightFor(w, r)
        if (h > 1f) {
            w /= h
            h = 1f
        }
        val left = (cx - w / 2).coerceIn(0f, 1f - w)
        val top = (cy - h / 2).coerceIn(0f, 1f - h)
        crop = RectF(left, top, left + w, top + h)
    }

    fun cropped(): Bitmap {
        val x = (crop.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (crop.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val w = ((crop.right - crop.left) * bitmap.width).roundToInt().coerceIn(1, bitmap.width - x)
        val h = ((crop.bottom - crop.top) * bitmap.height).roundToInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        image = fitRect(bitmap, width, height, pad)
        canvas.drawBitmap(bitmap, null, image, bitmapPaint)
        val f = frameRect()
        canvas.drawRect(image.left, image.top, image.right, f.top, dim)
        canvas.drawRect(image.left, f.bottom, image.right, image.bottom, dim)
        canvas.drawRect(image.left, f.top, f.left, f.bottom, dim)
        canvas.drawRect(f.right, f.top, image.right, f.bottom, dim)
        if (handle != 0) {
            for (i in 1..2) {
                val x = f.left + f.width() * i / 3f
                val y = f.top + f.height() * i / 3f
                canvas.drawLine(x, f.top, x, f.bottom, grid)
                canvas.drawLine(f.left, y, f.right, y, grid)
            }
        }
        canvas.drawRect(f, frame)
        val arm = min(22 * density, min(f.width(), f.height()) / 3f)
        for ((cx, dx) in listOf(f.left to 1f, f.right to -1f)) for ((cy, dy) in listOf(f.top to 1f, f.bottom to -1f)) {
            canvas.drawLine(cx, cy, cx + dx * arm, cy, corner)
            canvas.drawLine(cx, cy, cx, cy + dy * arm, corner)
        }
    }

    private fun frameRect() = RectF(
        image.left + crop.left * image.width(), image.top + crop.top * image.height(),
        image.left + crop.right * image.width(), image.top + crop.bottom * image.height()
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = pick(event.x, event.y)
                last = event.x to event.y
                invalidate()
                return handle != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (handle == 0 || image.width() <= 0f) return false
                val dx = (event.x - last.first) / image.width()
                val dy = (event.y - last.second) / image.height()
                last = event.x to event.y
                drag(dx, dy)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handle = 0
                invalidate()
            }
        }
        return true
    }

    // Bit flags: which sides a touch moves (all four = the whole frame).
    private fun pick(x: Float, y: Float): Int {
        val f = frameRect()
        val corners = 48 * density
        val sides = 36 * density
        val nearL = abs(x - f.left) <= corners
        val nearR = abs(x - f.right) <= corners
        val nearT = abs(y - f.top) <= corners
        val nearB = abs(y - f.bottom) <= corners
        when {
            nearL && nearT -> return LEFT or TOP
            nearR && nearT -> return RIGHT or TOP
            nearL && nearB -> return LEFT or BOTTOM
            nearR && nearB -> return RIGHT or BOTTOM
        }
        val inX = x in f.left - sides..f.right + sides
        val inY = y in f.top - sides..f.bottom + sides
        return when {
            inY && abs(x - f.left) <= sides -> LEFT
            inY && abs(x - f.right) <= sides -> RIGHT
            inX && abs(y - f.top) <= sides -> TOP
            inX && abs(y - f.bottom) <= sides -> BOTTOM
            f.contains(x, y) -> LEFT or TOP or RIGHT or BOTTOM
            else -> 0
        }
    }

    private fun drag(dx: Float, dy: Float) {
        val c = RectF(crop)
        if (handle == LEFT or TOP or RIGHT or BOTTOM) {
            val mx = dx.coerceIn(-c.left, 1f - c.right)
            val my = dy.coerceIn(-c.top, 1f - c.bottom)
            c.offset(mx, my)
        } else {
            if (handle and LEFT != 0) c.left = (c.left + dx).coerceIn(0f, c.right - MIN)
            if (handle and RIGHT != 0) c.right = (c.right + dx).coerceIn(c.left + MIN, 1f)
            if (handle and TOP != 0) c.top = (c.top + dy).coerceIn(0f, c.bottom - MIN)
            if (handle and BOTTOM != 0) c.bottom = (c.bottom + dy).coerceIn(c.top + MIN, 1f)
            val r = ratio
            if (r != null) {
                // Keep the shape: a corner keeps its opposite corner still; a side keeps the
                // middle of the other direction.
                val horizontal = handle and (LEFT or RIGHT) != 0
                val vertical = handle and (TOP or BOTTOM) != 0
                if (horizontal) {
                    val h = heightFor(c.width(), r)
                    if (vertical) {
                        if (handle and TOP != 0) c.top = c.bottom - h else c.bottom = c.top + h
                    } else {
                        val cy = crop.centerY()
                        c.top = cy - h / 2
                        c.bottom = cy + h / 2
                    }
                } else {
                    val w = c.height() * r * bitmap.height / bitmap.width
                    val cx = crop.centerX()
                    c.left = cx - w / 2
                    c.right = cx + w / 2
                }
                // A frame that would go past the screenshot doesn't move.
                if (c.left < -0.0001f || c.top < -0.0001f || c.right > 1.0001f || c.bottom > 1.0001f || c.width() < MIN || c.height() < MIN) return
            }
        }
        crop = c
    }

    private companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
        const val MIN = 0.05f
    }
}

/**
 * The screenshot toolbar's Draw: draw on the screenshot with a finger in [color] at [brushDp]
 * (on screen, so it looks the same whatever the screenshot's size). [result] flattens it.
 */
@SuppressLint("ViewConstructor")
class ShotDrawView(context: Context, private val bitmap: Bitmap) : View(context) {

    private class Stroke(val path: Path, val color: Int, val width: Float)

    private val density = resources.displayMetrics.density
    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private var image = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var color = Color.RED
    var brushDp = 6f
    var onChange: () -> Unit = {}

    val isChanged get() = strokes.isNotEmpty()

    fun undo() {
        strokes.removeLastOrNull()
        invalidate()
        onChange()
    }

    fun result(): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (stroke in strokes) drawStroke(canvas, stroke)
        return out
    }

    private fun scale() = if (image.width() > 0f) image.width() / bitmap.width else 1f

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        paint.color = stroke.color
        paint.strokeWidth = stroke.width
        canvas.drawPath(stroke.path, paint)
    }

    override fun onDraw(canvas: Canvas) {
        image = fitRect(bitmap, width, height, 0f)
        canvas.drawBitmap(bitmap, null, image, bitmapPaint)
        canvas.save()
        canvas.clipRect(image)
        canvas.translate(image.left, image.top)
        val s = scale()
        canvas.scale(s, s)
        for (stroke in strokes) drawStroke(canvas, stroke)
        current?.let { drawStroke(canvas, it) }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = scale()
        // Screen point to screenshot pixels.
        val x = (event.x - image.left) / s
        val y = (event.y - image.top) / s
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Stroke(Path().apply { moveTo(x, y); lineTo(x + 0.1f, y) }, color, brushDp * density / s)
            }
            MotionEvent.ACTION_MOVE -> current?.path?.lineTo(x, y)
            MotionEvent.ACTION_UP -> {
                current?.let { strokes += it }
                current = null
                onChange()
            }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }
}

/** A colour dot for Draw's palette; [chosen] gets a ring. */
@SuppressLint("ViewConstructor")
class ColorDot(context: Context, val color: Int) : View(context) {
    var chosen = false
        set(value) {
            field = value
            invalidate()
        }
    // (this@ColorDot: inside apply, plain "color" is the Paint's own.)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@ColorDot.color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        this.color = Color.WHITE
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        this.color = 0x66FFFFFF
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        val inset = 5 * resources.displayMetrics.density
        canvas.drawCircle(width / 2f, height / 2f, r - inset, fill)
        canvas.drawCircle(width / 2f, height / 2f, r - inset, edge)
        if (chosen) canvas.drawCircle(width / 2f, height / 2f, r - ring.strokeWidth, ring)
    }
}

/** A brush-size dot for Draw: a filled circle as big as the brush. */
@SuppressLint("ViewConstructor")
class SizeDot(context: Context, val sizeDp: Float) : View(context) {
    var chosen = false
        set(value) {
            field = value
            invalidate()
        }
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(width / 2f, height / 2f, max(2f, sizeDp * density / 2f), fill)
        if (chosen) canvas.drawCircle(width / 2f, height / 2f, min(width, height) / 2f - ring.strokeWidth, ring)
    }
}
