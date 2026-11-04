package org.fossify.gallery.helpers

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import org.fossify.commons.extensions.getAlertDialogBuilder
import kotlin.math.sin

/**
 * Viper Vision: a one-time "What's this?" on the Locked album's tile (shining, so it's noticed).
 * Tapping it explains the album; after that it's gone for good.
 */
object LockedAlbumHint {
    private const val PREF = "vv_locked_hint_seen"
    private const val TAG = "vv_locked_hint"

    fun seen(context: Context) = context.getSharedPreferences("Prefs", Context.MODE_PRIVATE).getBoolean(PREF, false)

    /** Adds (or removes) the hint on an album tile [root] whose picture is [thumbnail]. */
    fun bind(activity: Activity, root: View, thumbnail: View, show: Boolean, accent: Int) {
        val holder = root as? RelativeLayout ?: return
        val existing = holder.findViewWithTag<View>(TAG)
        if (!show) {
            existing?.let { holder.removeView(it) }
            return
        }
        if (existing != null) return
        val density = activity.resources.displayMetrics.density
        val chip = ShineChip(activity, accent).apply {
            setOnClickListener {
                activity.getSharedPreferences("Prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF, true).apply()
                (parent?.parent as? ViewGroup)?.removeView(parent as View)
                explain(activity)
            }
        }
        val frame = FrameLayout(activity).apply {
            tag = TAG
            clipChildren = false
            addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val params = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.ALIGN_START, thumbnail.id)
            addRule(RelativeLayout.ALIGN_END, thumbnail.id)
            addRule(RelativeLayout.ALIGN_TOP, thumbnail.id)
            addRule(RelativeLayout.ALIGN_BOTTOM, thumbnail.id)
            width = 0
            height = 0
        }
        holder.clipChildren = false
        holder.addView(frame, params)
        frame.layoutParams = params.apply { width = ViewGroup.LayoutParams.MATCH_PARENT; height = ViewGroup.LayoutParams.MATCH_PARENT }
        frame.elevation = 4 * density
    }

    private fun explain(activity: Activity) {
        activity.getAlertDialogBuilder()
            .setTitle("The Locked album")
            .setMessage(
                "A private album for photos and videos you don't want others to see.\n\n" +
                    "• What's in it is hidden from your other albums, the photo grid and other apps.\n" +
                    "• It only opens with your fingerprint, face or screen lock.\n" +
                    "• To add photos: select them, or open one, then ⋮ → Move to Locked album. You can also select whole albums.\n" +
                    "• To take them out: open the Locked album, select them, then Move out of Locked album. They go back where they came from.\n\n" +
                    "They're hidden, not encrypted: a file manager can still see the Pictures/Locked folder."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    /** The "What's this?" pill: the app's colour, a shine sweeping across it and a gentle glow. */
    private class ShineChip(context: Context, private val accent: Int) : View(context) {
        private val density = resources.displayMetrics.density
        private val text = "What's this?"
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14 * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val padX = 16 * density
        private val padY = 9 * density
        private val glowSize = 8 * density
        private var phase = 0f
        private val shader = LinearGradient(
            0f, 0f, 60 * density, 0f,
            intArrayOf(Color.TRANSPARENT, 0xAAFFFFFF.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        private val shaderMatrix = Matrix()
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }

        init {
            shine.shader = shader
            isClickable = true
            contentDescription = text
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = textPaint.measureText(text) + padX * 2 + glowSize * 2
            val h = textPaint.fontSpacing + padY * 2 + glowSize * 2
            setMeasuredDimension(w.toInt(), h.toInt())
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val pill = RectF(glowSize, glowSize, width - glowSize, height - glowSize)
            val r = pill.height() / 2
            // A soft glow breathing round the pill.
            val breath = (sin(phase * 2 * Math.PI).toFloat() + 1f) / 2f
            for (i in 3 downTo 1) {
                val grow = glowSize * i / 3f * (0.6f + 0.4f * breath)
                glow.alpha = (40 + 30 * breath).toInt() / i
                canvas.drawRoundRect(RectF(pill.left - grow, pill.top - grow, pill.right + grow, pill.bottom + grow), r + grow, r + grow, glow)
            }
            canvas.drawRoundRect(pill, r, r, fill)
            // The shine: a light band sweeping left to right, then a pause.
            val travel = pill.width() + 120 * density
            val x = pill.left - 60 * density + (phase * 1.6f).coerceAtMost(1f) * travel
            shaderMatrix.setTranslate(x, 0f)
            shaderMatrix.postSkew(-0.3f, 0f)
            shader.setLocalMatrix(shaderMatrix)
            canvas.save()
            canvas.clipRect(pill)
            canvas.drawRoundRect(pill, r, r, shine)
            canvas.restore()
            canvas.drawText(text, pill.centerX() - textPaint.measureText(text) / 2, pill.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
    }
}
