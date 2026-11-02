package com.motiondots.app

import android.content.Context
import android.content.SharedPreferences

/** Everything the app remembers. Changes apply to the dots straight away. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("motion_dots", Context.MODE_PRIVATE)

    /** The dots are switched on (notification, tile and app button). */
    var on: Boolean
        get() = sp.getBoolean("on", false)
        set(v) = sp.edit().putBoolean("on", v).apply()

    /** Draw through the accessibility service (covers the status bar, shade, Settings, lock screen). */
    var useAccessibility: Boolean
        get() = sp.getBoolean("use_a11y", false)
        set(v) = sp.edit().putBoolean("use_a11y", v).apply()

    /** Dot diameter in dp. */
    var size: Float
        get() = sp.getFloat("size", 9f)
        set(v) = sp.edit().putFloat("size", v).apply()

    /** Gap between dots in dp (smaller = more dots). */
    var spacing: Float
        get() = sp.getFloat("spacing", 70f)
        set(v) = sp.edit().putFloat("spacing", v).apply()

    /** How far in from the edge the dot columns sit, in dp. */
    var inset: Float
        get() = sp.getFloat("inset", 14f)
        set(v) = sp.edit().putFloat("inset", v).apply()

    /** How far (dp) a set of dots travels before it fades out and a fresh set fades in. */
    var travel: Float
        get() = sp.getFloat("travel", 45f)
        set(v) = sp.edit().putFloat("travel", v).apply()

    /** 0..1. A normal overlay is capped at 0.8 by Android, or touches under it get blocked. */
    var opacity: Float
        get() = sp.getFloat("opacity", 0.8f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    /** How far the dots move per bit of acceleration (1 = default). */
    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 1f)
        set(v) = sp.edit().putFloat("sensitivity", v).apply()

    /** 0 (snappy) .. 1 (smooth): how much the motion is smoothed before the dots follow it. */
    var smoothing: Float
        get() = sp.getFloat("smoothing", 0.4f)
        set(v) = sp.edit().putFloat("smoothing", v).apply()

    /** Index into [COLORS]. */
    var color: Int
        get() = sp.getInt("color", 0)
        set(v) = sp.edit().putInt("color", v).apply()

    /** 0 = left and right edges, 1 = all four edges. */
    var layout: Int
        get() = sp.getInt("layout", 0)
        set(v) = sp.edit().putInt("layout", v).apply()

    /** Fade the dots out when the phone hasn't felt any vehicle-like motion for a while. */
    var hideWhenStill: Boolean
        get() = sp.getBoolean("hide_still", false)
        set(v) = sp.edit().putBoolean("hide_still", v).apply()

    /** Swap the direction (for someone facing backwards, e.g. on a train). */
    var reversed: Boolean
        get() = sp.getBoolean("reversed", false)
        set(v) = sp.edit().putBoolean("reversed", v).apply()

    /** Turn the dots on in a vehicle and off when the ride ends. */



    companion object {
        /** Fill and outline; the outline keeps the dots visible on light and dark screens. */
        val COLORS = listOf(
            Triple("White", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
            Triple("Black", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            Triple("Grey", 0xFF9AA0A6.toInt(), 0xFF202124.toInt()),
            Triple("Blue", 0xFF5EC8FF.toInt(), 0xFF00263A.toInt()),
            Triple("Yellow", 0xFFFFD54F.toInt(), 0xFF3A2C00.toInt()),
            Triple("Green", 0xFF69F0AE.toInt(), 0xFF00331A.toInt())
        )
    }
}
