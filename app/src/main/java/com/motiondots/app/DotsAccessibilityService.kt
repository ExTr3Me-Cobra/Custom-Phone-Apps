package com.motiondots.app

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent

/**
 * Optional: draws the dots as an accessibility overlay, which Android lets cover everything
 * (status bar, notification shade, Quick Settings, Settings, lock screen) at full opacity.
 * It reads nothing on the screen.
 */
class DotsAccessibilityService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private var overlay: Overlay? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "on" || key == "use_a11y") update()
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        running = true
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        prefs.sp.edit().putLong("a11y_running", System.currentTimeMillis()).apply()
        update()
    }

    private fun update() {
        if (prefs.on && prefs.useAccessibility) {
            if (overlay == null) overlay = Overlay(this, accessibility = true)
            overlay?.show()
        } else {
            overlay?.hide()
            overlay = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running = false
        if (::prefs.isInitialized) {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
            prefs.sp.edit().putLong("a11y_running", 0L).apply()
        }
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
    }
}
