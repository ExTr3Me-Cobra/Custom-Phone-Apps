package com.motiondots.app

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager

/**
 * A full-screen window that only shows the dots and lets every touch through.
 * [accessibility]: drawn by the accessibility service, so it also covers the status bar,
 * notifications, Settings and the lock screen. Otherwise a normal "display over other apps"
 * window, which Android keeps at 80 % opacity at most (above that it blocks touches beneath).
 */
class Overlay(private val context: Context, private val accessibility: Boolean) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val motion = Motion(context)
    private var view: DotsView? = null

    val showing get() = view != null

    fun show() {
        if (view != null) return
        val v = DotsView(context, motion).apply { maxAlpha = if (accessibility) 1f else 0.8f }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Motion dots"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            if (!accessibility) alpha = 0.8f
        }
        runCatching { wm.addView(v, lp) }.onSuccess {
            view = v
            motion.start()
        }
    }

    fun hide() {
        val v = view ?: return
        motion.stop()
        runCatching { wm.removeView(v) }
        view = null
    }
}
