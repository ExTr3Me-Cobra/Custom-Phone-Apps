package com.glowalerts.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Everything needed to draw one lighting effect. Sizes are in pixels. */
data class LightSpec(
    val effect: LightEffect,
    /** One colour, or two for a gradient. */
    val colors: IntArray,
    val speed: Float,
    val thicknessPx: Float,
    val cornerPx: Float,
    /** 0..1 */
    val brightness: Float,
    /** How long it plays; 0 = forever (the preview in the app), or until [keepGoing] says stop. */
    val durationMs: Long,
    /** For endless lighting: checked every frame once started; returning false ends it. */
    val keepGoing: (() -> Boolean)? = null,
    /** Glow size multiplier, 0 = crisp lines only. */
    val glow: Float = 1f,
    /** Colour everything with a turning rainbow. */
    val rainbow: Boolean = false,
    /** Settings for the cracking effects. */
    val crack: CrackSpec = CrackSpec(),
)

/** How the cracking effects look. Sizes in pixels. */
data class CrackSpec(
    val origin: CrackOrigin = CrackOrigin.TOP,
    val allAtOnce: Boolean = false,
    val lineWidthPx: Float = 6f,
    val detail: Int = 5,
    val flash: Boolean = true,
    val edgeGlow: Boolean = false,
    val jagged: Float = 1f,
    val branchLength: Float = 1f,
    val count: Int = 1,
    val shake: Float = 1f,
    val core: Boolean = true,
    val flicker: Boolean = false,
    val shimmer: Boolean = true,
    val repeats: Int = 1,
    val retract: Boolean = false,
    /** Fixed pattern seed, or null for a new random crack each time. */
    val fixedSeed: Long? = null,
    val flashFull: Boolean = false,
    /** Lightning lands on this side of the screen: [Tilt.BOTTOM], [Tilt.LEFT] or [Tilt.RIGHT]. */
    val landing: Int = Tilt.BOTTOM,
    /** Lightning from the camera: ring around the hole, and the hole's fine-tuning (pixels). */
    val camRing: Boolean = true,
    val camDx: Float = 0f,
    val camDy: Float = 0f,
    val camDr: Float = 0f,
    val camRingPx: Float = 6f,
    val camRingGlow: Float = 1f,
)

/**
 * Draws the edge lighting. Animation time starts at the first frame actually drawn, so when the
 * screen is still fully off the effect starts as soon as it lights up.
 */
@SuppressLint("ViewConstructor")
class EdgeLightView(context: Context, spec: LightSpec, private val onDone: () -> Unit = {}) : View(context) {
    var spec: LightSpec = spec
        set(value) {
            field = value
            buildPath()
            invalidate()
        }

    private var start = 0L
    private var finished = false

    /** The effect has drawn its first frame. */
    var started = false
        private set

    /** Waiting for the first frame to reach the screen before the clock starts. */
    private var waitingSince = 0L

    /** Told when the first frame reaches the screen (true), or it gave up waiting (false). */
    var onShown: ((Boolean) -> Unit)? = null

    /** The first frame has reached the screen and the effect is running. */
    val shown get() = start != 0L

    // The whole edge: a rounded rectangle that starts (and ends) at the top centre.
    private val path = Path()
    private val measure = PathMeasure()
    private var length = 0f

    // The two long sides, both running top to bottom.
    private val left = Path()
    private val right = Path()
    private val leftMeasure = PathMeasure()
    private val rightMeasure = PathMeasure()
    private var sideLength = 0f

    // Front camera (punch hole) position and size, for Eclipse / Spotlight / Echo from the camera.
    private var camX = 0f
    private var camY = 0f
    private var camR = 0f

    private val segment = Path()
    private val ring = Path()
    private val ringRect = RectF()
    private val rotate = Matrix()
    private var crackShader: Shader? = null
    private var crackShaderKey: String? = null
    private val line = strokePaint()
    private val glow = strokePaint()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var color = Color.WHITE
    private var color2 = Color.WHITE
    private val pos = FloatArray(2)

    /** Cycles since the start, not wrapped: for particles moving at their own speeds. */
    private var cycles = 0f

    /** Fixed random sparkles / bubbles / drops, so they don't jump between frames. */
    private val rnd = Random(7).let { r -> FloatArray(240) { r.nextFloat() } }

    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Restarts the effect from the beginning (for a new notification). */
    fun restart() {
        start = 0L
        waitingSince = 0L
        finished = false
        seed = Random.nextLong()
        crackShape = null
        crackRound = -1
        invalidate()
    }

    // Cracking: a new random pattern for every notification.
    private var seed = Random.nextLong()
    private var crackShape: CrackShape? = null
    private val crackPaths = arrayOf(Path(), Path(), Path())
    private val flashPaint = Paint().apply { style = Paint.Style.FILL }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildPath()

    private fun buildPath() {
        path.reset()
        left.reset()
        right.reset()
        if (width == 0 || height == 0) return
        val inset = spec.thicknessPx / 2f
        val r0 = RectF(inset, inset, width - inset, height - inset)
        val r = (spec.cornerPx - inset).coerceIn(0f, min(r0.width(), r0.height()) / 2f)
        val cx = r0.centerX()
        path.moveTo(cx, r0.top)
        path.lineTo(r0.right - r, r0.top)
        path.arcTo(RectF(r0.right - 2 * r, r0.top, r0.right, r0.top + 2 * r), -90f, 90f)
        path.lineTo(r0.right, r0.bottom - r)
        path.arcTo(RectF(r0.right - 2 * r, r0.bottom - 2 * r, r0.right, r0.bottom), 0f, 90f)
        path.lineTo(r0.left + r, r0.bottom)
        path.arcTo(RectF(r0.left, r0.bottom - 2 * r, r0.left + 2 * r, r0.bottom), 90f, 90f)
        path.lineTo(r0.left, r0.top + r)
        path.arcTo(RectF(r0.left, r0.top, r0.left + 2 * r, r0.top + 2 * r), 180f, 90f)
        path.close()
        measure.setPath(path, false)
        length = measure.length

        // Sides: the straight part of the left and right edges.
        val top = r0.top + r * 0.6f
        val bottom = r0.bottom - r * 0.6f
        left.moveTo(r0.left, top)
        left.lineTo(r0.left, bottom)
        right.moveTo(r0.right, top)
        right.lineTo(r0.right, bottom)
        leftMeasure.setPath(left, false)
        rightMeasure.setPath(right, false)
        sideLength = leftMeasure.length

        findCamera()
        crackShape = null
    }

    /** Where the front camera hole is, scaled to this view (the preview in the app is small). */
    private fun findCamera() {
        val scale = width / context.resources.displayMetrics.widthPixels.toFloat()
        val hole = runCatching {
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?.cutout?.boundingRects?.filter { !it.isEmpty }?.minByOrNull { it.width() * it.height() }
        }.getOrNull()
        val density = resources.displayMetrics.density
        if (hole != null) {
            camX = hole.exactCenterX() * scale
            camY = hole.exactCenterY() * scale
            camR = max(hole.width(), hole.height()) / 2f * scale
        } else {
            camX = width / 2f
            camY = 34f * density * scale
            camR = 14f * density * scale
        }
    }

    override fun onDraw(canvas: Canvas) {
        // A drawing error must never take the lighting down: log it and end this effect.
        try {
            drawFrame(canvas)
        } catch (e: Exception) {
            Log.add("Lighting: drawing error (${e.javaClass.simpleName}: ${e.message})")
            finished = true
            post(onDone)
        }
    }

    private fun drawFrame(canvas: Canvas) {
        if (finished || length == 0f) return
        val now = SystemClock.uptimeMillis()
        if (start == 0L) {
            // Screen still fully off: wait, so the whole effect is seen. (It does play on the
            // Always On Display.)
            if (!LightService.screenVisible(context)) {
                postInvalidateDelayed(30)
                return
            }
            started = true
            // Start the clock only once the first frame has actually reached the screen. A window
            // that was hidden can take a moment to show, which used to eat most of a short alert.
            if (waitingSince == 0L) waitingSince = now
            if (now - waitingSince > GIVE_UP_MS) {
                start = now
                onShown?.invoke(false)
            } else {
                // Asked for every frame while waiting, in case Android drops one.
                viewTreeObserver.registerFrameCommitCallback {
                    if (start == 0L) {
                        start = SystemClock.uptimeMillis()
                        onShown?.invoke(true)
                    }
                    invalidate()
                }
            }
        }
        val t = if (start == 0L) 0L else now - start
        val s = spec
        if ((s.durationMs > 0 && t >= s.durationMs) || (s.durationMs == 0L && s.keepGoing?.invoke() == false)) {
            finished = true
            post(onDone)
            return
        }
        // Fade in and out at the ends.
        val fadeIn = min(250f, s.durationMs * 0.2f)
        val fadeOut = min(400f, s.durationMs * 0.3f)
        val env = if (s.durationMs == 0L) 1f else min(1f, min(t / fadeIn, (s.durationMs - t) / fadeOut)).coerceAtLeast(0f)
        val cycle = max(60f, 2200f / s.speed.coerceAtLeast(0.05f))
        val phase = (t % cycle.toLong()) / cycle
        cycles = t / cycle
        // Above 1 (brightness over 100 %) the light is drawn stacked, for an over-bright look.
        val a = (s.brightness * env).coerceAtLeast(0f)
        val w = s.thicknessPx

        applyColors(phase)
        if (s.effect.isCrack) {
            // The in-app preview repeats with a fresh crack every few seconds.
            val loop = (4200f / s.speed.coerceAtLeast(0.05f)).toLong()
            if (s.durationMs == 0L && s.keepGoing == null && t > loop) {
                start = now
                seed = Random.nextLong()
                crackShape = null
                crackRound = -1
            }
            drawCrack(canvas, if (s.durationMs == 0L && s.keepGoing == null) t % loop else t, phase, a, w)
            postInvalidateOnAnimation()
            return
        }
        when (s.effect) {
            // Around the screen
            LightEffect.GLOW -> glowLine(canvas, path, w, a * (0.8f + 0.2f * sin(2 * PI * phase).toFloat()))
            LightEffect.LINE -> stroke(canvas, path, w, a, blur = 0f)
            LightEffect.PULSE -> {
                val p = (0.5f - 0.5f * cos(2 * PI * phase)).toFloat()
                stroke(canvas, path, w * (0.6f + 1.4f * p), a * (0.2f + 0.8f * p) * 0.6f, blur = w * 1.4f)
                stroke(canvas, path, w * (0.4f + 0.6f * p), a * (0.2f + 0.8f * p), blur = 0f)
            }
            LightEffect.HEARTBEAT -> {
                val b = heartbeat(phase)
                stroke(canvas, path, w * (1f + 1.4f * b), a * (0.15f + 0.85f * b) * 0.6f, blur = w * 1.5f)
                stroke(canvas, path, w * (0.5f + 0.5f * b), a * (0.15f + 0.85f * b), blur = 0f)
            }
            LightEffect.COMET -> comet(canvas, phase * length, forward = true, a, w, length * 0.28f)
            LightEffect.TWIN -> {
                comet(canvas, phase * length / 2f, forward = true, a, w, length * 0.2f)
                comet(canvas, length - phase * length / 2f, forward = false, a, w, length * 0.2f)
            }
            LightEffect.WAVE -> wave(canvas, phase, a, w)
            LightEffect.GLITTER -> glitter(canvas, phase, a, w)
            LightEffect.RAINBOW -> glowLine(canvas, path, w, a)
            LightEffect.FLASH -> if (blink(phase)) glowLine(canvas, path, w, a)
            LightEffect.BUBBLES -> bubbles(canvas, phase, a, w)
            LightEffect.ECLIPSE -> eclipse(canvas, phase, a, w)
            LightEffect.SPOTLIGHT -> spotlight(canvas, phase, a, w)

            // Echo family
            LightEffect.ECHO -> echoRings(canvas, phase, a, w, floatArrayOf(0f, 1 / 3f, 2 / 3f))
            LightEffect.ECHO_DOUBLE -> echoRings(canvas, phase, a, w, floatArrayOf(0f, 0.09f, 0.5f, 0.59f))
            LightEffect.ECHO_TOP -> echoTop(canvas, phase, a, w)
            LightEffect.ECHO_CAMERA -> echoCamera(canvas, phase, a, w)
            LightEffect.ECHO_SIDES -> echoSides(canvas, phase, a, w)

            // Sides only
            LightEffect.SIDE_GLOW -> {
                val breathe = 0.8f + 0.2f * sin(2 * PI * phase).toFloat()
                glowLine(canvas, left, w, a * breathe)
                glowLine(canvas, right, w, a * breathe)
            }
            LightEffect.SIDE_PULSE -> {
                val p = (0.5f - 0.5f * cos(2 * PI * phase)).toFloat()
                for (side in arrayOf(left, right)) {
                    stroke(canvas, side, w * (0.6f + 1.6f * p), a * (0.2f + 0.8f * p) * 0.6f, blur = w * 1.5f)
                    stroke(canvas, side, w * (0.4f + 0.6f * p), a * (0.2f + 0.8f * p), blur = 0f)
                }
            }
            LightEffect.SIDE_SWEEP -> {
                val tail = sideLength * 0.45f
                val head = phase * (sideLength + tail)
                sideComet(canvas, leftMeasure, head, tail, a, w)
                sideComet(canvas, rightMeasure, head, tail, a, w)
            }
            LightEffect.SIDE_BOUNCE -> {
                val tri = 1f - abs(2f * phase - 1f)
                val len = sideLength * 0.3f
                val head = len + tri * (sideLength - len)
                sideComet(canvas, leftMeasure, head, len, a, w)
                sideComet(canvas, rightMeasure, sideLength - head + len, len, a, w)
            }
            LightEffect.SIDE_WAVE -> {
                sideWave(canvas, leftMeasure, phase, a, w)
                sideWave(canvas, rightMeasure, phase, a, w)
            }
            LightEffect.SIDE_RAIN -> {
                sideRain(canvas, leftMeasure, left, phase, a, w, 0)
                sideRain(canvas, rightMeasure, right, phase, a, w, 60)
            }
            LightEffect.SIDE_FLASH -> if (blink(phase)) {
                glowLine(canvas, left, w, a)
                glowLine(canvas, right, w, a)
            }
            else -> Unit
        }
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- colours and drawing basics

    private fun applyColors(phase: Float) {
        val s = spec
        val cx = width / 2f
        val cy = height / 2f
        color = s.colors.firstOrNull() ?: Color.WHITE
        color2 = s.colors.getOrNull(1) ?: color
        val shader: Shader? = when {
            s.effect == LightEffect.RAINBOW || s.rainbow -> SweepGradient(cx, cy, RAINBOW, null)
            s.colors.size > 1 -> SweepGradient(cx, cy, intArrayOf(s.colors[0], s.colors[1], s.colors[0]), null)
            else -> null
        }
        if (shader != null) {
            rotate.setRotate(phase * 360f, cx, cy)
            shader.setLocalMatrix(rotate)
        }
        for (p in arrayOf(line, glow, dot)) {
            p.shader = shader
            p.color = if (shader == null) color else Color.WHITE
        }
    }

    private fun stroke(canvas: Canvas, p: Path, width: Float, alpha: Float, blur: Float) {
        // The glow setting widens or narrows every soft layer; at 0 they're left out.
        val glowAmount = ringGlowOverride ?: spec.glow
        if (blur > 0f && glowAmount < 0.05f) return
        val soft = blur * glowAmount
        val paint = if (soft > 0f) glow else line
        paint.strokeWidth = max(1f, width)
        paint.maskFilter = if (soft > 0f) blurFilter(soft) else null
        var rest = alpha
        while (rest > 0.004f) {
            paint.alpha = (min(rest, 1f) * 255).toInt()
            canvas.drawPath(p, paint)
            rest -= 1f
        }
    }

    /** A soft glow with a bright core. */
    private fun glowLine(canvas: Canvas, p: Path, w: Float, alpha: Float) {
        stroke(canvas, p, w * 2.2f, alpha * 0.65f, blur = w * 1.6f)
        stroke(canvas, p, w * 0.65f, alpha, blur = 0f)
    }

    private fun spot(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Float, blur: Float) {
        val soft = blur * spec.glow
        dot.maskFilter = if (soft > 0f) blurFilter(soft) else null
        var rest = alpha
        while (rest > 0.004f) {
            dot.alpha = (min(rest, 1f) * 255).toInt()
            canvas.drawCircle(x, y, max(0.5f, radius), dot)
            rest -= 1f
        }
    }

    private fun blink(phase: Float) = phase < 0.12f || phase in 0.24f..0.36f

    private val blurs = HashMap<Int, BlurMaskFilter>()

    private fun blurFilter(radius: Float): BlurMaskFilter {
        val key = max(1, radius.toInt())
        return blurs.getOrPut(key) { BlurMaskFilter(key.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    /** Fills [segment] with the edge between two distances, wrapping round the start point. */
    private fun segmentPath(from: Float, to: Float) {
        segment.reset()
        var a = from % length
        if (a < 0) a += length
        var b = a + (to - from)
        if (b <= length) {
            measure.getSegment(a, b, segment, true)
        } else {
            measure.getSegment(a, length, segment, true)
            b -= length
            measure.getSegment(0f, b, segment, true)
        }
    }

    private fun withAlpha(c: Int, a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    // -------------------------------------------------------------------------------- cracking

    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    /** When set, the glow amount used instead of the main Glow setting (the camera ring's own). */
    private var ringGlowOverride: Float? = null

    /** The in-app preview is a shrunken screen: scale fine-tuning offsets to match. */
    private fun previewScale() = width / context.resources.displayMetrics.widthPixels.toFloat()

    /** Which repeat the crack is on, so each repeat can get a fresh pattern. */
    private var crackRound = -1

    /**
     * Cracks spreading across the screen from the chosen side (or all at once), with a shudder
     * while they spread and an optional flash where they hit. Can repeat several times per
     * alert and pull back into where it started at the end.
     */
    private fun drawCrack(canvas: Canvas, t0: Long, phase: Float, alpha0: Float, w: Float) {
        val c = spec.crack
        val total = if (spec.durationMs > 0) spec.durationMs else (4200f / spec.speed.coerceAtLeast(0.05f)).toLong()
        val repeats = c.repeats.coerceIn(1, 10)
        val roundMs = max(1L, total / repeats)
        val round = (t0 / roundMs).toInt().coerceAtMost(repeats - 1)
        val t = t0 - round * roundMs
        if (round != crackRound) {
            crackRound = round
            if (round > 0 && c.fixedSeed == null) seed = Random.nextLong()
            crackShape = null
        }
        val lightning = spec.effect == LightEffect.CRACK_LIGHTNING
        // Lightning from the camera uses the fine-tuned camera position.
        val cx = if (lightning) camX + c.camDx * previewScale() else camX
        val cy = if (lightning) camY + c.camDy * previewScale() else camY
        // Lines keep apart: mostly a fixed gap (10 dp, scaled for the small preview), plus a little more for thicker
        // lines so their glows don't touch.
        val dp = context.resources.displayMetrics.density
        Cracks.clearance = CRACK_GAP_DP * dp * previewScale() + c.lineWidthPx * 0.6f
        val shape = crackShape ?: Cracks.build(
            spec.effect, width.toFloat(), height.toFloat(), c.origin, c.detail, c.fixedSeed ?: seed, cx, cy,
            c.jagged, c.branchLength, c.count, if (lightning) c.landing else Tilt.BOTTOM,
        ).also { crackShape = it }

        // Short alerts: make sure the crack finishes spreading in time.
        val spreadMs = min(max(30f, 1100f / spec.speed.coerceAtLeast(0.05f)), roundMs * 0.6f)
        var reveal = if (c.allAtOnce) {
            shape.maxDist
        } else {
            val p = (t / spreadMs).coerceIn(0f, 1f)
            (1f - (1f - p).pow(2.2f)) * shape.maxDist
        }
        // Pull back into the starting point over the last part of each round.
        var alpha = alpha0
        if (c.retract) {
            val back = min(roundMs * 0.35f, spreadMs * 1.2f)
            val left = roundMs - t
            if (left < back) reveal *= (left / back).coerceIn(0f, 1f)
        } else if (repeats > 1) {
            // Fade between rounds so each new crack starts clean.
            alpha *= min(1f, (roundMs - t) / 250f).coerceIn(0f, 1f)
        }
        // Lightning-style flicker while it forms.
        if (c.flicker && t < spreadMs + 400) {
            val f = sin(t * 0.21f) + sin(t * 0.077f + 1.3f)
            if (f > 0.7f) alpha *= 0.15f
        }

        for (path in crackPaths) path.reset()
        val pts = shape.points
        for (i in 0 until shape.count) {
            val d = shape.dist[i]
            if (d >= reveal) continue
            val f = ((reveal - d) / max(0.001f, shape.length[i])).coerceAtMost(1f)
            val x1 = pts[i * 4]
            val y1 = pts[i * 4 + 1]
            val path = crackPaths[shape.level[i].coerceIn(0, 2)]
            path.moveTo(x1, y1)
            path.lineTo(x1 + (pts[i * 4 + 2] - x1) * f, y1 + (pts[i * 4 + 3] - y1) * f)
        }

        // Colours on cracks and lightning: a still gradient outward from where it starts (the spinning one used
        // round the screen edge looked like it swept from points across the lightning).
        if (spec.effect == LightEffect.RAINBOW || spec.rainbow || spec.colors.size > 1) {
            val cols = if (spec.colors.size > 1 && !spec.rainbow) intArrayOf(spec.colors[0], spec.colors[1]) else RAINBOW
            val key = "${shape.originX},${shape.originY},$width,$height,${cols.joinToString()}"
            if (key != crackShaderKey) {
                val r = max(1f, hypot(width.toFloat(), height.toFloat()) * 0.8f)
                crackShader = RadialGradient(shape.originX, shape.originY, r, cols, null, Shader.TileMode.CLAMP)
                crackShaderKey = key
            }
            for (p in arrayOf(line, glow, dot)) { p.shader = crackShader; p.color = Color.WHITE }
        }
        val unit = min(width, height).toFloat()
        // The screen "shudders" while the crack runs.
        val quakeMs = if (c.allAtOnce) 450f else spreadMs + 250f
        canvas.save()
        if (t < quakeMs && c.shake > 0f) {
            val amp = unit * 0.01f * c.shake * (1f - t / quakeMs)
            canvas.translate(sin(t * 0.11f) * amp, cos(t * 0.083f) * amp * 0.6f)
        }
        val shimmer = if (c.shimmer) 0.85f + 0.15f * sin(2 * PI * phase).toFloat() else 1f
        // Lightning from the camera: a glowing ring round the camera hole.
        if (lightning && c.origin == CrackOrigin.CAMERA && c.camRing) {
            val ps = previewScale()
            ring.reset()
            ring.addCircle(cx, cy, max(1f, camR + c.camDr * ps + c.camRingPx * 0.5f), Path.Direction.CW)
            val rw = c.camRingPx
            val savedGlow = ringGlowOverride
            ringGlowOverride = c.camRingGlow
            stroke(canvas, ring, rw * 2.5f, alpha * 0.7f * shimmer, blur = rw * 2f)
            ringGlowOverride = savedGlow
            stroke(canvas, ring, rw, alpha * shimmer, blur = 0f)
        }
        if (c.edgeGlow) glowLine(canvas, path, w, alpha * 0.6f * shimmer)
        val lw = c.lineWidthPx
        val widths = floatArrayOf(1f, 0.6f, 0.35f)
        for (level in 2 downTo 0) {
            val p = crackPaths[level]
            val lwl = lw * widths[level]
            stroke(canvas, p, lwl * 3f, alpha * 0.5f * shimmer, blur = lwl * 2.5f)
            stroke(canvas, p, lwl, alpha * shimmer, blur = 0f)
            if (c.core) {
                // A hot white core down the middle of each crack.
                core.strokeWidth = max(1f, lwl * 0.35f)
                core.alpha = (alpha * 0.7f * shimmer * 255).toInt().coerceIn(0, 255)
                canvas.drawPath(p, core)
            }
        }
        canvas.restore()

        // Flash where (or as) it hits.
        val flashMs = min(400f, roundMs * 0.5f)
        if (c.flash && t < flashMs) {
            val f = 1f - t / flashMs
            if (c.flashFull) {
                flashPaint.shader = null
                flashPaint.color = withAlpha(color, alpha * 0.35f * f)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            } else {
                val r = unit * 0.5f * (0.4f + 0.6f * (1f - f))
                flashPaint.shader = RadialGradient(
                    shape.originX, shape.originY, r,
                    intArrayOf(withAlpha(Color.WHITE, alpha * 0.8f * f), withAlpha(color, alpha * 0.4f * f), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(shape.originX, shape.originY, r, flashPaint)
            }
        }
    }

    // ------------------------------------------------------------------------- around the screen

    /** Two quick beats, then rest: "lub-dub". */
    private fun heartbeat(phase: Float): Float {
        val first = exp(-phase / 0.07f)
        val second = if (phase >= 0.2f) 0.8f * exp(-(phase - 0.2f) / 0.07f) else 0f
        return min(1f, first + second)
    }

    /** A bright head at [head] (distance along the edge) with a fading tail behind it. */
    private fun comet(canvas: Canvas, head: Float, forward: Boolean, alpha: Float, w: Float, tail: Float) {
        val pieces = 16
        for (i in pieces - 1 downTo 0) {
            val a = i / pieces.toFloat()
            val b = (i + 1) / pieces.toFloat()
            val fade = (1f - a).pow(1.6f)
            val d1 = if (forward) head - tail * b else head + tail * a
            val d2 = if (forward) head - tail * a else head + tail * b
            segmentPath(d1, d2)
            stroke(canvas, segment, w * (1.9f - a), alpha * fade * 0.55f, blur = w * 1.3f)
            stroke(canvas, segment, w * (1f - 0.5f * a), alpha * fade, blur = 0f)
        }
    }

    /** Bands of light travelling round the edge. */
    private fun wave(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        val pieces = 90
        val step = length / pieces
        for (i in 0 until pieces) {
            val x = i / pieces.toFloat()
            val b = (0.5f + 0.5f * sin(2 * PI * (x * 4 - phase)).toFloat()).pow(2f)
            if (b < 0.03f) continue
            segmentPath(i * step, (i + 1) * step + 1f)
            stroke(canvas, segment, w * (1f + b), alpha * b * 0.6f, blur = w * 1.4f)
            stroke(canvas, segment, w * 0.6f, alpha * b, blur = 0f)
        }
    }

    /** A faint edge with sparkles twinkling along it. */
    private fun glitter(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        stroke(canvas, path, w * 0.5f, alpha * 0.25f, blur = 0f)
        for (i in 0 until 48) {
            val tw = sin(2 * PI * (cycles * (1.5f + rnd[i + 48]) + rnd[i + 96])).toFloat()
            if (tw <= 0f) continue
            val b = tw.pow(3f)
            measure.getPosTan(rnd[i] * length, pos, null)
            spot(canvas, pos[0], pos[1], w * (0.5f + 1.6f * b), alpha * b * 0.7f, blur = w * 1.2f)
            spot(canvas, pos[0], pos[1], w * (0.25f + 0.6f * b), alpha * b, blur = 0f)
        }
    }

    /** Bubbles rising up both sides from the bottom. */
    private fun bubbles(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        stroke(canvas, path, w * 0.5f, alpha * 0.2f, blur = 0f)
        val band = width * 0.12f
        for (i in 0 until 22) {
            val speed = 0.6f + rnd[i] * 0.8f
            val p = (cycles * speed + rnd[i + 30]) % 1f
            val x = if (i % 2 == 0) w + rnd[i + 60] * band else width - w - rnd[i + 60] * band
            val y = height * (1.05f - p * 1.1f)
            val radius = w * (0.8f + rnd[i + 90] * 1.8f) * (0.7f + 0.3f * p)
            val fade = sin(PI * p).toFloat()
            line.strokeWidth = max(1f, w * 0.35f)
            line.alpha = (alpha * fade * 255).toInt().coerceIn(0, 255)
            line.maskFilter = null
            canvas.drawCircle(x, y, radius, line)
            spot(canvas, x, y, radius, alpha * fade * 0.25f, blur = radius * 0.6f)
        }
    }

    /** A ring of light around the front camera, like an eclipse, with a bright flare circling it. */
    private fun eclipse(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        val r = camR + w * 0.9f
        ring.reset()
        ring.addCircle(camX, camY, r, Path.Direction.CW)
        val breathe = 0.75f + 0.25f * sin(2 * PI * phase).toFloat()
        stroke(canvas, ring, w * 1.6f, alpha * 0.7f * breathe, blur = w * 1.8f)
        stroke(canvas, ring, w * 0.45f, alpha * breathe, blur = 0f)
        val angle = 2 * PI * phase
        spot(canvas, camX + r * cos(angle).toFloat(), camY + r * sin(angle).toFloat(), w * 1.4f, alpha, blur = w * 1.5f)
        // Faint edge so you still notice it at a glance.
        stroke(canvas, path, w * 0.5f, alpha * 0.25f * breathe, blur = 0f)
    }

    /** A soft beam of light spreading down from the front camera. */
    private fun spotlight(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        val radius = min(width, height) * 0.55f * (0.85f + 0.15f * sin(2 * PI * phase).toFloat())
        area.shader = RadialGradient(
            camX, camY, radius,
            intArrayOf(withAlpha(color, alpha * 0.85f), withAlpha(color2, alpha * 0.35f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(camX, camY, radius, area)
        stroke(canvas, path, w * 0.5f, alpha * 0.4f, blur = 0f)
        ring.reset()
        ring.addCircle(camX, camY, camR + w * 0.5f, Path.Direction.CW)
        stroke(canvas, ring, w * 0.6f, alpha, blur = w)
    }

    // ------------------------------------------------------------------------------- echo family

    /** The edge glows and rings of light ripple inwards from it, fading as they travel. */
    private fun echoRings(canvas: Canvas, phase: Float, alpha: Float, w: Float, offsets: FloatArray) {
        // The edge flares each time a ring leaves it.
        var beat = 0f
        for (o in offsets) beat = max(beat, 1f - ((phase - o + 1f) % 1f) * 4f)
        stroke(canvas, path, w * 2f, alpha * (0.45f + 0.35f * beat), blur = w * 1.5f)
        stroke(canvas, path, w * 0.7f, alpha * (0.7f + 0.3f * beat), blur = 0f)
        val travel = min(width, height) * 0.16f
        val inset = spec.thicknessPx / 2f
        for (o in offsets) {
            val p = (phase - o + 1f) % 1f
            val d = inset + p * travel
            ringRect.set(d, d, width - d, height - d)
            val r = (spec.cornerPx - d).coerceAtLeast(spec.cornerPx * 0.35f)
                .coerceAtMost(min(ringRect.width(), ringRect.height()) / 2f)
            ring.reset()
            ring.addRoundRect(ringRect, r, r, Path.Direction.CW)
            val fade = (1f - p).pow(1.8f)
            stroke(canvas, ring, w * (1.6f - p), alpha * fade * 0.5f, blur = w * (1.2f + p * 2f))
            stroke(canvas, ring, w * (0.6f - 0.4f * p), alpha * fade * 0.8f, blur = 0f)
        }
    }

    /** Light leaves the top, races down both sides and meets at the bottom; fainter echoes follow. */
    private fun echoTop(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        stroke(canvas, path, w * 0.5f, alpha * 0.2f, blur = 0f)
        val echoes = floatArrayOf(0f, 0.14f, 0.28f)
        for ((k, o) in echoes.withIndex()) {
            val p = phase - o
            if (p < 0f) continue
            val strength = alpha * (1f - k * 0.3f) * (1f - p).pow(0.5f)
            val head = p * length / 2f
            val tail = length * 0.1f
            comet(canvas, head, forward = true, strength, w * (1f - k * 0.2f), tail)
            comet(canvas, length - head, forward = false, strength, w * (1f - k * 0.2f), tail)
        }
    }

    /** Rings of light spreading out from the front camera, like ripples on water. */
    private fun echoCamera(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        val maxR = hypot(width.toFloat(), height.toFloat()) * 0.45f
        for (k in 0 until 4) {
            val p = (phase + k / 4f) % 1f
            val fade = (1f - p).pow(1.6f)
            ring.reset()
            ring.addCircle(camX, camY, camR + p * maxR, Path.Direction.CW)
            stroke(canvas, ring, w * (1.6f - p), alpha * fade * 0.5f, blur = w * (1.2f + p * 2f))
            stroke(canvas, ring, w * (0.6f - 0.3f * p), alpha * fade * 0.85f, blur = 0f)
        }
        ring.reset()
        ring.addCircle(camX, camY, camR + w * 0.5f, Path.Direction.CW)
        stroke(canvas, ring, w * 0.6f, alpha, blur = w)
    }

    /** Like Echo, but only the two long sides ripple inwards. */
    private fun echoSides(canvas: Canvas, phase: Float, alpha: Float, w: Float) {
        val beat = (1f - ((phase * 3f) % 1f)).pow(2f)
        glowLine(canvas, left, w, alpha * (0.6f + 0.4f * beat))
        glowLine(canvas, right, w, alpha * (0.6f + 0.4f * beat))
        val travel = width * 0.18f
        leftMeasure.getPosTan(0f, pos, null)
        val sideTop = pos[1]
        for (k in 0 until 3) {
            val p = (phase + k / 3f) % 1f
            val fade = (1f - p).pow(1.8f)
            val top = sideTop + p * travel * 0.6f
            for (x in floatArrayOf(w / 2f + p * travel, width - w / 2f - p * travel)) {
                ring.reset()
                ring.moveTo(x, top)
                ring.lineTo(x, height - top)
                stroke(canvas, ring, w * (1.6f - p), alpha * fade * 0.5f, blur = w * (1.2f + p * 2f))
                stroke(canvas, ring, w * (0.6f - 0.4f * p), alpha * fade * 0.8f, blur = 0f)
            }
        }
    }

    // ---------------------------------------------------------------------------------- sides

    /** A bright light at [head] (distance from the top of a side) with a tail above it. */
    private fun sideComet(canvas: Canvas, m: PathMeasure, head: Float, tail: Float, alpha: Float, w: Float) {
        val pieces = 12
        for (i in pieces - 1 downTo 0) {
            val a = i / pieces.toFloat()
            val b = (i + 1) / pieces.toFloat()
            val from = (head - tail * b).coerceIn(0f, sideLength)
            val to = (head - tail * a).coerceIn(0f, sideLength)
            if (to - from < 0.5f) continue
            segment.reset()
            m.getSegment(from, to, segment, true)
            val fade = (1f - a).pow(1.5f)
            stroke(canvas, segment, w * (2f - a), alpha * fade * 0.55f, blur = w * 1.4f)
            stroke(canvas, segment, w * (1f - 0.5f * a), alpha * fade, blur = 0f)
        }
    }

    private fun sideWave(canvas: Canvas, m: PathMeasure, phase: Float, alpha: Float, w: Float) {
        val pieces = 40
        val step = sideLength / pieces
        for (i in 0 until pieces) {
            val x = i / pieces.toFloat()
            val b = (0.5f + 0.5f * sin(2 * PI * (x * 2.5f - phase)).toFloat()).pow(2f)
            if (b < 0.03f) continue
            segment.reset()
            m.getSegment(i * step, min(sideLength, (i + 1) * step + 1f), segment, true)
            stroke(canvas, segment, w * (1f + 1.2f * b), alpha * b * 0.6f, blur = w * 1.4f)
            stroke(canvas, segment, w * 0.6f, alpha * b, blur = 0f)
        }
    }

    /** Short drops of light falling down a side at different speeds. */
    private fun sideRain(canvas: Canvas, m: PathMeasure, side: Path, phase: Float, alpha: Float, w: Float, seed: Int) {
        stroke(canvas, side, w * 0.4f, alpha * 0.2f, blur = 0f)
        for (i in 0 until 7) {
            val speed = 0.7f + rnd[seed + i] * 1.1f
            val p = (cycles * speed + rnd[seed + i + 20]) % 1f
            val len = sideLength * (0.08f + rnd[seed + i + 40] * 0.1f)
            sideComet(canvas, m, p * (sideLength + len), len, alpha * (0.6f + 0.4f * rnd[seed + i + 10]), w * 0.8f)
        }
    }

    companion object {
        /** The set gap between separate lightning / crack lines (dp); thicker lines add a little to it. */
        const val CRACK_GAP_DP = 10f
        private val RAINBOW = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
            0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFFFF3B30.toInt(),
        )
    }
}

private const val GIVE_UP_MS = 3_000L
