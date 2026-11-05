package com.mammyframe.app

import android.content.Context

/** The transition styles offered in the Transitions setting. */
enum class Transition(val label: String) {
    FADE("Fade"),
    FADE_BLACK("Fade through black"),
    SLIDE("Slide"),
    SLIDE_UP("Slide up"),
    ZOOM("Zoom in"),
    NONE("None (instant)"),
    RANDOM("Mix of all");

    companion object {
        /** Styles RANDOM picks from. */
        val mixable = listOf(FADE, FADE_BLACK, SLIDE, SLIDE_UP, ZOOM)
    }
}

/** Everything the app remembers. Times are minutes after midnight. */
data class Settings(
    val folderUri: String? = null,
    val folderName: String? = null,
    val scheduleOn: Boolean = false,
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 21 * 60,
    val transition: Transition = Transition.FADE,
    val transitionMs: Int = 1500,
    val photoSeconds: Int = 10,
    val gentleZoom: Boolean = true,
    /** Shows the hidden Schedule and Transitions settings (tap the name 10 times quickly). */
    val developer: Boolean = false,
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("mammyframe", Context.MODE_PRIVATE)

    fun load(c: Context): Settings {
        val p = sp(c)
        val d = Settings()
        return Settings(
            folderUri = p.getString("folderUri", null),
            folderName = p.getString("folderName", null),
            scheduleOn = p.getBoolean("scheduleOn", d.scheduleOn),
            startMinute = p.getInt("startMinute", d.startMinute),
            endMinute = p.getInt("endMinute", d.endMinute),
            transition = runCatching { Transition.valueOf(p.getString("transition", null)!!) }
                .getOrDefault(d.transition),
            transitionMs = p.getInt("transitionMs", d.transitionMs),
            photoSeconds = p.getInt("photoSeconds", d.photoSeconds),
            gentleZoom = p.getBoolean("gentleZoom", d.gentleZoom),
            developer = p.getBoolean("developer", d.developer),
        )
    }

    fun save(c: Context, s: Settings) {
        sp(c).edit()
            .putString("folderUri", s.folderUri)
            .putString("folderName", s.folderName)
            .putBoolean("scheduleOn", s.scheduleOn)
            .putInt("startMinute", s.startMinute)
            .putInt("endMinute", s.endMinute)
            .putString("transition", s.transition.name)
            .putInt("transitionMs", s.transitionMs)
            .putInt("photoSeconds", s.photoSeconds)
            .putBoolean("gentleZoom", s.gentleZoom)
            .putBoolean("developer", s.developer)
            .apply()
    }
}
