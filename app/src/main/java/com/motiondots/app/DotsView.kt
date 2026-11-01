package com.motiondots.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.exp
import kotlin.math.floor

/**
 * The dots. They act like part of the scenery outside: speeding up slides them down (towards
 * the rider), braking slides them up, a left turn slides them right and a right turn left.
 * They follow on a soft spring and settle back to rest when the motion stops.
 */
@SuppressLint("ViewConstructor")
class DotsView(context: Context, private val motion: Motion) : View(context), Choreographer.FrameCallback {
    private val prefs = Prefs(context)
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var x = 0.0; private var vx = 0.0
    private var y = 0.0; private var vy = 0.0
    private var shown = 1.0
    private var stillSince = 0L
    private var lastFrame = 0L
    private var running = false

    // Settings, re-read when they change.
    private var sizePx = 0f; private var spacingPx = 0f; private var insetPx = 0f
    private var gain = 0.0; private var maxShift = 0.0; private var allEdges = false
    private var hideStill = false; private var sign = 1.0; private var alphaFrac = 1f

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> readSettings() }

    /** Caps the opacity (0.8 for a normal overlay). */
    var maxAlpha = 1f
        set(v) { field = v; readSettings() }

    // After every field above: Kotlin runs initialisers in order, so maxAlpha must exist first.
    init { readSettings() }

    private fun readSettings() {
        sizePx = prefs.size * density
        spacingPx = prefs.spacing.coerceAtLeast(prefs.size * 2) * density
        insetPx = prefs.inset * density
        // About 28 dp per m/s² at sensitivity 1: an ordinary pull-away (2 m/s²) moves them ~56 dp.
        gain = 28.0 * density * prefs.sensitivity
        maxShift = 140.0 * density
        allEdges = prefs.layout == 1
        hideStill = prefs.hideWhenStill
        sign = if (prefs.reversed) -1.0 else 1.0
        alphaFrac = minOf(prefs.opacity, maxAlpha).coerceAtLeast(0.1f)
        motion.smoothSeconds = 0.05 + prefs.smoothing * 0.5
        val (_, f, o) = Prefs.COLORS[prefs.color.coerceIn(0, Prefs.COLORS.lastIndex)]
        fill.color = f
        outline.color = o
        outline.strokeWidth = (sizePx * 0.14f).coerceAtLeast(1f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0.016 else ((frameTimeNanos - lastFrame) / 1e9).coerceIn(0.001, 0.1)
        lastFrame = frameTimeNanos
        // Where the dots should be: opposite the vehicle's acceleration.
        val tx = (-motion.right * gain * sign).coerceIn(-maxShift, maxShift)
        val ty = (motion.forward * gain * sign).coerceIn(-maxShift, maxShift)
        // Critically damped spring (about 0.35 s to settle).
        val w = 12.0
        vx += (w * w * (tx - x) - 2 * w * vx) * dt; x += vx * dt
        vy += (w * w * (ty - y) - 2 * w * vy) * dt; y += vy * dt
        // Hide when still: fade out after 20 s with no vehicle-like motion, back in when it returns.
        val now = SystemClock.elapsedRealtime()
        val target = if (!hideStill) 1.0 else {
            if (motion.activity > 0.12) stillSince = now
            if (now - stillSince < 20_000) 1.0 else 0.0
        }
        shown += (target - shown) * (1 - exp(-dt / 0.4))
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        if (shown < 0.01) return
        val a = (alphaFrac * shown * 255).toInt().coerceIn(0, 255)
        fill.alpha = a
        outline.alpha = a
        val r = sizePx / 2
        val w = width.toFloat(); val h = height.toFloat()
        val dx = x.toFloat(); val dy = y.toFloat()
        // Left and right columns move with both directions; rows of dots repeat endlessly.
        val oy = mod(dy, spacingPx)
        var yy = oy - spacingPx
        while (yy < h + spacingPx) {
            dot(canvas, insetPx + r + dx, yy, r)
            dot(canvas, w - insetPx - r + dx, yy, r)
            yy += spacingPx
        }
        if (allEdges) {
            val ox = mod(dx, spacingPx)
            var xx = ox - spacingPx
            while (xx < w + spacingPx) {
                if (xx > insetPx + 3 * r + dx && xx < w - insetPx - 3 * r + dx) {
                    dot(canvas, xx, insetPx + r + dy, r)
                    dot(canvas, xx, h - insetPx - r + dy, r)
                }
                xx += spacingPx
            }
        }
    }

    private fun dot(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, outline)
    }

    private fun mod(v: Float, m: Float) = v - floor(v / m) * m
}
