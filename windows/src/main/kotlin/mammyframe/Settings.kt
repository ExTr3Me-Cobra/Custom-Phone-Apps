package mammyframe

import java.io.File
import java.util.Properties

/** Where Mammy Frame keeps its settings and lists on this PC (%APPDATA%\MammyFrame). */
val dataDir: File by lazy {
    val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
    File(base, "MammyFrame").apply { mkdirs() }
}

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
        val mixable = listOf(FADE, FADE_BLACK, SLIDE, SLIDE_UP, ZOOM)
    }
}

/** Everything the app remembers. Times are minutes after midnight. */
data class Settings(
    val folder: String? = null,
    val scheduleOn: Boolean = false,
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 21 * 60,
    val transition: Transition = Transition.FADE,
    val transitionMs: Int = 1500,
    val photoSeconds: Int = 10,
    val gentleZoom: Boolean = true,
    val developer: Boolean = false,
) {
    val folderName: String? get() = folder?.let { File(it).name.ifEmpty { it } }
}

object Prefs {
    private val file get() = File(dataDir, "settings.properties")

    fun load(): Settings {
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }
        val d = Settings()
        fun int(k: String, def: Int) = p.getProperty(k)?.toIntOrNull() ?: def
        fun bool(k: String, def: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: def
        return Settings(
            folder = p.getProperty("folder"),
            scheduleOn = bool("scheduleOn", d.scheduleOn),
            startMinute = int("startMinute", d.startMinute),
            endMinute = int("endMinute", d.endMinute),
            transition = runCatching { Transition.valueOf(p.getProperty("transition")) }.getOrDefault(d.transition),
            transitionMs = int("transitionMs", d.transitionMs),
            photoSeconds = int("photoSeconds", d.photoSeconds),
            gentleZoom = bool("gentleZoom", d.gentleZoom),
            developer = bool("developer", d.developer),
        )
    }

    fun save(s: Settings) {
        val p = Properties()
        s.folder?.let { p.setProperty("folder", it) }
        p.setProperty("scheduleOn", s.scheduleOn.toString())
        p.setProperty("startMinute", s.startMinute.toString())
        p.setProperty("endMinute", s.endMinute.toString())
        p.setProperty("transition", s.transition.name)
        p.setProperty("transitionMs", s.transitionMs.toString())
        p.setProperty("photoSeconds", s.photoSeconds.toString())
        p.setProperty("gentleZoom", s.gentleZoom.toString())
        p.setProperty("developer", s.developer.toString())
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { p.store(it, "Mammy Frame") }
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
