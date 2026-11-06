package mammyframe

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A diagnostic log in %APPDATA%\MammyFrame\log.txt: what the PC is, errors and crashes, and how
 * smoothly the screen is drawing. "Copy diagnostic log" in the developer settings copies it.
 */
object Log {
    private val file by lazy { File(dataDir, "log.txt") }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private const val MAX_BYTES = 400_000L

    @Synchronized
    fun i(msg: String) {
        runCatching {
            // Keep it small: when it gets big, keep only the newer half.
            if (file.length() > MAX_BYTES) {
                val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText("…(older lines removed)\n" + keep.substringAfter('\n'))
            }
            file.appendText("${time.format(Date())}  $msg\n")
        }
    }

    fun e(msg: String, t: Throwable?) {
        val trace = t?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() } ?: ""
        i("ERROR $msg${if (t != null) ": $t\n$trace" else ""}")
    }

    /** Everything worth knowing about this PC, written once at startup. */
    fun startup(args: Array<String>) {
        i("================ Mammy Frame ${System.getProperty("jpackage.app-version") ?: "(dev)"} starting, args=${args.toList()}")
        i("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        i("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}, " +
            "max memory ${Runtime.getRuntime().maxMemory() / 1_048_576} MB, ${Runtime.getRuntime().availableProcessors()} processors")
        runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed { n, d ->
                val m = d.displayMode
                val scale = d.defaultConfiguration.defaultTransform.scaleX
                i("Screen $n: ${m.width}x${m.height} @ ${m.refreshRate} Hz, scaling ${(scale * 100).toInt()}%")
            }
        }.onFailure { e("reading screens", it) }
        i("Drawing: skiko.renderApi=${System.getProperty("skiko.renderApi") ?: "(default)"}")
        Thread.setDefaultUncaughtExceptionHandler { th, t -> e("uncaught on thread ${th.name}", t) }
    }

    fun text(): String = runCatching { file.readText() }.getOrDefault("(no log yet)")

    /** The log plus the current state, ready to paste. */
    fun report(extra: String): String = buildString {
        append("Mammy Frame diagnostic log\n")
        append(extra).append("\n\n")
        append(text().takeLast(60_000))
    }
}
