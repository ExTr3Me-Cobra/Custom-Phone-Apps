package mammyframe

import com.sun.jna.platform.win32.Kernel32
import java.io.File
import java.util.Calendar

/** Whether the schedule says photos should be showing (ON) or the screen should be black (OFF). */
enum class Phase { ON, OFF }

object Schedule {
    fun minuteOfDay(cal: Calendar = Calendar.getInstance()) =
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    /** Null when the schedule is off (or start and end are the same time). */
    fun phaseNow(s: Settings, cal: Calendar = Calendar.getInstance()): Phase? {
        if (!s.scheduleOn || s.startMinute == s.endMinute) return null
        val now = minuteOfDay(cal)
        val on = if (s.startMinute < s.endMinute) now >= s.startMinute && now < s.endMinute
        else now >= s.startMinute || now < s.endMinute // overnight, e.g. 8 PM to 2 AM
        return if (on) Phase.ON else Phase.OFF
    }
}

val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Starting Mammy Frame when Windows starts: a tiny file in the user's Startup folder. */
object StartWithWindows {
    /** The installed program (set by the Windows launcher); null when run some other way. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    private val startupFile: File? get() = System.getenv("APPDATA")?.let {
        File(it, "Microsoft\\Windows\\Start Menu\\Programs\\Startup\\Mammy Frame.cmd")
    }

    val available get() = isWindows && exe != null && startupFile != null
    val enabled get() = startupFile?.exists() == true

    fun set(on: Boolean) {
        val f = startupFile ?: return
        if (on) {
            val path = exe ?: return
            f.writeText("@echo off\r\nstart \"\" \"$path\" --startup\r\n")
        } else {
            f.delete()
        }
    }
}

/** Keeps the screen on and the PC awake while photos are showing (Windows only). */
object KeepAwake {
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    /** [display]: keep the screen on. [system]: keep the PC from sleeping (e.g. while the AI checks). */
    fun set(display: Boolean, system: Boolean) {
        if (!isWindows) return
        runCatching {
            var flags = ES_CONTINUOUS
            if (display) flags = flags or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED
            if (system) flags = flags or ES_SYSTEM_REQUIRED
            Kernel32.INSTANCE.SetThreadExecutionState(flags)
        }
    }
}
