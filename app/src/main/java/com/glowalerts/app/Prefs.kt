package com.glowalerts.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LightGroup(val label: String) {
    AROUND("Around the screen"),
    ECHO("Echo"),
    SIDES("Sides only"),
    CRACK("Cracking"),
}

enum class LightEffect(val label: String, val group: LightGroup = LightGroup.AROUND) {
    GLOW("Glow"),
    LINE("Line"),
    PULSE("Pulse"),
    HEARTBEAT("Heartbeat"),
    COMET("Comet"),
    TWIN("Twin comets"),
    WAVE("Wave"),
    GLITTER("Glitter"),
    RAINBOW("Rainbow"),
    FLASH("Flash"),
    BUBBLES("Bubbles"),
    ECLIPSE("Eclipse"),
    SPOTLIGHT("Spotlight"),
    ECHO("Echo", LightGroup.ECHO),
    ECHO_DOUBLE("Double echo", LightGroup.ECHO),
    ECHO_TOP("Echo from the top", LightGroup.ECHO),
    ECHO_CAMERA("Echo from the camera", LightGroup.ECHO),
    ECHO_SIDES("Echo on the sides", LightGroup.ECHO),
    SIDE_GLOW("Side glow", LightGroup.SIDES),
    SIDE_PULSE("Side pulse", LightGroup.SIDES),
    SIDE_SWEEP("Side sweep", LightGroup.SIDES),
    SIDE_BOUNCE("Side bounce", LightGroup.SIDES),
    SIDE_WAVE("Side wave", LightGroup.SIDES),
    SIDE_RAIN("Side rain", LightGroup.SIDES),
    SIDE_FLASH("Side flash", LightGroup.SIDES),
    CRACK_QUAKE("Earthquake", LightGroup.CRACK),
    CRACK_SHATTER("Shattered glass", LightGroup.CRACK),
    CRACK_WEB("Spiderweb", LightGroup.CRACK),
    CRACK_LIGHTNING("Lightning crack", LightGroup.CRACK),
    CRACK_FAULTS("Fault lines", LightGroup.CRACK),
    CRACK_EDGES("Cracked edges", LightGroup.CRACK),
    ;

    val isCrack get() = group == LightGroup.CRACK
}

enum class ColorMode(val label: String) {
    APP("Each app's colour"),
    ONE("One colour"),
    TWO("Two-colour gradient"),
    RAINBOW("Rainbow"),
}

/** Where a crack starts spreading from. */
enum class CrackOrigin(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom"),
    LEFT("Left"),
    RIGHT("Right"),
    CENTER("Centre"),
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_RIGHT("Bottom right"),
    CAMERA("Camera"),
    RANDOM("Random"),
}

data class Settings(
    /** Master switch. */
    val enabled: Boolean = true,
    val effect: LightEffect = LightEffect.GLOW,
    val colorMode: ColorMode = ColorMode.APP,
    val color1: Int = 0xFF3D8BFF.toInt(),
    val color2: Int = 0xFFB04DFF.toInt(),
    /** 0.25..3, 1 = normal. */
    val speed: Float = 1f,
    val thicknessDp: Float = 6f,
    /** How soft and wide the glow around lines is, 0..5 (1 = normal, 0 = no glow). */
    val glow: Float = 1f,
    /** 20..100 % */
    /** 20..300 %; above 100 the light is stacked for an over-bright, blown-out look. */
    val brightness: Int = 100,
    /** How long it plays, 0.1..20 s. */
    val seconds: Float = 5f,
    /** Follow the screen's own rounded corners. */
    val matchCorners: Boolean = true,
    val cornerDp: Float = 40f,

    // Cracking
    val crackOrigin: CrackOrigin = CrackOrigin.TOP,
    /** Appear in one go instead of spreading from [crackOrigin]. */
    val crackAllAtOnce: Boolean = false,
    val crackLineDp: Float = 3f,
    /** How many branches and pieces, 1..10. */
    val crackDetail: Int = 5,
    /** A bright flash when the crack hits. */
    val crackFlash: Boolean = true,
    /** The impact flash fills the whole screen (off: a burst where it starts). */
    val crackFlashFull: Boolean = false,
    /** Lightning: land at the bottom of the screen as you're holding it (sideways too). */
    val lightningTilt: Boolean = true,
    /** Lightning from the camera: a glowing ring around the camera hole. */
    val camRing: Boolean = true,
    /** Fine-tuning the camera hole position and size, in dp. */
    val camOffsetX: Float = 0f,
    val camOffsetY: Float = 0f,
    val camSizeAdjust: Float = 0f,
    val camRingDp: Float = 3f,
    /** 0..5 (1 = normal). */
    val camRingGlow: Float = 1f,
    /** Also glow around the screen edge while cracked. */
    val crackEdgeGlow: Boolean = false,
    /** How zig-zag the cracks are, 0.2..2 (1 = normal). */
    val crackJagged: Float = 1f,
    /** How long the side branches grow, 0.3..2.5 (1 = normal). */
    val crackBranchLength: Float = 1f,
    /** How many main cracks, 1..6. */
    val crackCount: Int = 1,
    /** How hard the screen shudders while it cracks, 0..3 (1 = normal). */
    val crackShake: Float = 1f,
    /** A white-hot line down the middle of each crack. */
    val crackCore: Boolean = true,
    /** Flicker like lightning while forming. */
    val crackFlicker: Boolean = false,
    /** Cracks shimmer (gently pulse) once formed. */
    val crackShimmer: Boolean = true,
    /** How many times it cracks during one alert, 1..6. */
    val crackRepeats: Int = 1,
    /** At the end, the crack pulls back into where it started instead of just fading. */
    val crackRetract: Boolean = false,
    /** Use the same crack every time instead of a new random one. */
    val crackSamePattern: Boolean = false,
    /** The saved pattern for [crackSamePattern]. */
    val crackSeed: Long = 20261009L,

    // When
    val onUnlocked: Boolean = true,
    val onLocked: Boolean = true,
    val onAod: Boolean = true,
    /** Turn the screen on for notifications that arrive while it's fully off. */
    val wakeScreen: Boolean = true,
    /** On the Always On Display, wake to the lock screen so the lighting is sure to show. */
    val wakeFromAod: Boolean = true,
    /** Also light up for notifications the app sends quietly. */
    val includeSilent: Boolean = true,
    /** Run a foreground service so Android never puts the app to sleep or delays it. */
    val alwaysReady: Boolean = true,
    /** Also keep the processor awake all the time (uses more battery). */
    val keepAwake: Boolean = true,
    /** Show the "Glow Alerts is ready" notification (Always ready works either way). */
    val readyNotification: Boolean = true,
    /** Play the effect on screen whenever a setting is changed. */
    val autoPreview: Boolean = true,
    /** Apps that never light up. */
    val excluded: Set<String> = emptySet(),
    /** Colours picked recently, newest first. */
    val recentColors: List<Int> = emptyList(),
)

/** Settings, saved in SharedPreferences and observable for the screen. */
object Prefs {
    private val state = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = state.asStateFlow()
    private var loaded = false

    fun get(context: Context): Settings {
        if (!loaded) {
            val p = prefs(context)
            val d = Settings()
            state.value = Settings(
                enabled = p.getBoolean("enabled", d.enabled),
                effect = p.enum("effect", d.effect),
                colorMode = p.enum("colorMode", d.colorMode),
                color1 = p.getInt("color1", d.color1),
                color2 = p.getInt("color2", d.color2),
                speed = p.getFloat("speed", d.speed),
                thicknessDp = p.getFloat("thickness", d.thicknessDp),
                glow = p.getFloat("glow", d.glow),
                brightness = p.getInt("brightness", d.brightness),
                seconds = if (p.contains("secondsF")) p.getFloat("secondsF", d.seconds) else p.getInt("seconds", 5).toFloat(),
                matchCorners = p.getBoolean("matchCorners", d.matchCorners),
                cornerDp = p.getFloat("corner", d.cornerDp),
                crackOrigin = p.enum("crackOrigin", d.crackOrigin),
                crackAllAtOnce = p.getBoolean("crackAllAtOnce", d.crackAllAtOnce),
                crackLineDp = p.getFloat("crackLine", d.crackLineDp),
                crackDetail = p.getInt("crackDetail", d.crackDetail),
                crackFlash = p.getBoolean("crackFlash", d.crackFlash),
                crackEdgeGlow = p.getBoolean("crackEdgeGlow", d.crackEdgeGlow),
                crackFlashFull = p.getBoolean("crackFlashFull", d.crackFlashFull),
                lightningTilt = p.getBoolean("lightningTilt", d.lightningTilt),
                camRing = p.getBoolean("camRing", d.camRing),
                camOffsetX = p.getFloat("camOffsetX", d.camOffsetX),
                camOffsetY = p.getFloat("camOffsetY", d.camOffsetY),
                camSizeAdjust = p.getFloat("camSizeAdjust", d.camSizeAdjust),
                camRingDp = p.getFloat("camRingDp", d.camRingDp),
                camRingGlow = p.getFloat("camRingGlow", d.camRingGlow),
                crackJagged = p.getFloat("crackJagged", d.crackJagged),
                crackBranchLength = p.getFloat("crackBranchLength", d.crackBranchLength),
                crackCount = p.getInt("crackCount", d.crackCount),
                crackShake = p.getFloat("crackShake", d.crackShake),
                crackCore = p.getBoolean("crackCore", d.crackCore),
                crackFlicker = p.getBoolean("crackFlicker", d.crackFlicker),
                crackShimmer = p.getBoolean("crackShimmer", d.crackShimmer),
                crackRepeats = p.getInt("crackRepeats", d.crackRepeats),
                crackRetract = p.getBoolean("crackRetract", d.crackRetract),
                crackSamePattern = p.getBoolean("crackSamePattern", d.crackSamePattern),
                crackSeed = p.getLong("crackSeed", d.crackSeed),
                onUnlocked = p.getBoolean("onUnlocked", d.onUnlocked),
                onLocked = p.getBoolean("onLocked", d.onLocked),
                onAod = p.getBoolean("onAod", d.onAod),
                wakeScreen = p.getBoolean("wakeScreen2", d.wakeScreen),
                wakeFromAod = p.getBoolean("wakeFromAod", d.wakeFromAod),
                includeSilent = p.getBoolean("includeSilent", d.includeSilent),
                alwaysReady = p.getBoolean("alwaysReady", d.alwaysReady),
                autoPreview = p.getBoolean("autoPreview", d.autoPreview),
                keepAwake = p.getBoolean("keepAwake", d.keepAwake),
                readyNotification = p.getBoolean("readyNotification", d.readyNotification),
                excluded = p.getStringSet("excluded", emptySet()).orEmpty().toSet(),
                recentColors = p.getString("recentColors", "").orEmpty().split(",")
                    .mapNotNull { it.toLongOrNull()?.toInt() },
            )
            loaded = true
        }
        return state.value
    }

    fun update(context: Context, change: (Settings) -> Settings) {
        val s = change(get(context))
        state.value = s
        prefs(context).edit()
            .putBoolean("enabled", s.enabled)
            .putString("effect", s.effect.name)
            .putString("colorMode", s.colorMode.name)
            .putInt("color1", s.color1)
            .putInt("color2", s.color2)
            .putFloat("speed", s.speed)
            .putFloat("thickness", s.thicknessDp)
            .putFloat("glow", s.glow)
            .putInt("brightness", s.brightness)
            .putFloat("secondsF", s.seconds)
            .putBoolean("matchCorners", s.matchCorners)
            .putFloat("corner", s.cornerDp)
            .putString("crackOrigin", s.crackOrigin.name)
            .putBoolean("crackAllAtOnce", s.crackAllAtOnce)
            .putFloat("crackLine", s.crackLineDp)
            .putInt("crackDetail", s.crackDetail)
            .putBoolean("crackFlash", s.crackFlash)
            .putBoolean("crackEdgeGlow", s.crackEdgeGlow)
            .putBoolean("crackFlashFull", s.crackFlashFull)
            .putBoolean("lightningTilt", s.lightningTilt)
            .putBoolean("camRing", s.camRing)
            .putFloat("camOffsetX", s.camOffsetX)
            .putFloat("camOffsetY", s.camOffsetY)
            .putFloat("camSizeAdjust", s.camSizeAdjust)
            .putFloat("camRingDp", s.camRingDp)
            .putFloat("camRingGlow", s.camRingGlow)
            .putFloat("crackJagged", s.crackJagged)
            .putFloat("crackBranchLength", s.crackBranchLength)
            .putInt("crackCount", s.crackCount)
            .putFloat("crackShake", s.crackShake)
            .putBoolean("crackCore", s.crackCore)
            .putBoolean("crackFlicker", s.crackFlicker)
            .putBoolean("crackShimmer", s.crackShimmer)
            .putInt("crackRepeats", s.crackRepeats)
            .putBoolean("crackRetract", s.crackRetract)
            .putBoolean("crackSamePattern", s.crackSamePattern)
            .putLong("crackSeed", s.crackSeed)
            .putBoolean("onUnlocked", s.onUnlocked)
            .putBoolean("onLocked", s.onLocked)
            .putBoolean("onAod", s.onAod)
            .putBoolean("wakeScreen2", s.wakeScreen)
            .putBoolean("wakeFromAod", s.wakeFromAod)
            .putBoolean("includeSilent", s.includeSilent)
            .putBoolean("alwaysReady", s.alwaysReady)
            .putBoolean("autoPreview", s.autoPreview)
            .putBoolean("keepAwake", s.keepAwake)
            .putBoolean("readyNotification", s.readyNotification)
            .putStringSet("excluded", s.excluded)
            .putString("recentColors", s.recentColors.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() })
            .apply()
    }

    /** Remembers a picked colour at the front of the recent list. */
    fun addRecentColor(context: Context, color: Int) = update(context) {
        it.copy(recentColors = (listOf(color) + it.recentColors.filter { c -> c != color }).take(12))
    }

    /** Every setting as a small JSON file (Save settings), keeping each value's type. */
    fun exportJson(context: Context): String {
        val o = org.json.JSONObject().put("app", "Glow Alerts").put("version", 1)
        val values = org.json.JSONObject()
        for ((k, v) in prefs(context).all) {
            val e = org.json.JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> continue
            }
            values.put(k, e)
        }
        return o.put("settings", values).toString(1)
    }

    /** Puts saved settings back (Restore settings). False if the file isn't a Glow Alerts settings file. */
    fun importJson(context: Context, text: String): Boolean {
        val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return false
        if (o.optString("app") != "Glow Alerts") return false
        val values = o.optJSONObject("settings") ?: return false
        val ed = prefs(context).edit().clear()
        for (k in values.keys()) {
            val e = values.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "set" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        ed.commit()
        // Read them back in and pass them on, as if they'd been changed on screen.
        loaded = false
        val s = get(context)
        update(context) { s }
        return true
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
        runCatching { enumValueOf<E>(getString(key, null) ?: return default) }.getOrDefault(default)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
