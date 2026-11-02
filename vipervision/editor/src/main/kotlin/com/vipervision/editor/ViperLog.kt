package com.vipervision.editor

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Viper Vision's diagnostic log: what the app was doing, how much memory it had, and how it
 * ended, so a crash or a tool that silently did nothing can be pasted back for a fix.
 *
 * - Lines go to a file in the app's private storage (no permissions, nothing leaves the phone
 *   unless the user copies or shares it from Settings → Diagnostic log). Capped at ~1 MB.
 * - Every line is written straight to the file, so when Android kills the app outright (out of
 *   memory, a crash inside an AI model's native code) the last thing it was doing is still there.
 * - On the next start it records why Android says the last run ended (Android 11+), e.g. "killed:
 *   low memory" or "native crash", with the memory the app was using at that moment.
 */
object ViperLog {

    private var file: File? = null
    private var appContext: Context? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Call once from the Application. */
    fun install(app: Application) {
        if (file != null) return
        appContext = app.applicationContext
        file = File(File(app.filesDir, "viper_logs").apply { mkdirs() }, "log.txt")
        line("APP", "──── Viper Vision started (pid ${Process.myPid()}) ${versionOf(app)} ────")
        line("APP", deviceSummary(app))
        logPreviousExits(app)
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = line("MEM", "Android asked to free memory (level $level). ${memory()}")
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = line("MEM", "Android says memory is very low. ${memory()}")
        })
    }

    /**
     * Logs crashes, then hands them on. Call after the app's other crash handlers are set: Image
     * Toolbox's ends the process without passing the crash on, so it must come after this one.
     */
    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            error("CRASH", "Uncaught on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun i(tag: String, message: String) = line(tag, message)

    fun error(tag: String, message: String, error: Throwable? = null) {
        line(tag, "ERROR $message" + (error?.let { "\n" + stackTrace(it) } ?: ""))
    }

    /** Logs [what] starting (with memory), then its time and outcome. Rethrows failures. */
    inline fun <T> step(tag: String, what: String, block: () -> T): T {
        i(tag, "▶ $what. ${memory()}")
        val start = System.nanoTime()
        return try {
            block().also { i(tag, "✓ $what in ${(System.nanoTime() - start) / 1_000_000} ms. ${memory()}") }
        } catch (t: Throwable) {
            error(tag, "✗ $what failed after ${(System.nanoTime() - start) / 1_000_000} ms. ${memory()}", t)
            throw t
        }
    }

    /** Size, pixel format and whether it can hold transparency. */
    fun describe(bitmap: Bitmap?): String =
        if (bitmap == null) "no image" else "${bitmap.width}×${bitmap.height} ${bitmap.config} alpha=${bitmap.hasAlpha()}"

    /**
     * How much of [bitmap] is see-through, from a grid of samples: tells "the background remover
     * found nothing" (≈0%) apart from "it worked but the preview lost it".
     */
    fun transparency(bitmap: Bitmap): String {
        if (!bitmap.hasAlpha()) return "no transparency (opaque format)"
        val steps = 120
        var clear = 0
        var partial = 0
        for (yi in 0 until steps) for (xi in 0 until steps) {
            val a = android.graphics.Color.alpha(bitmap.getPixel(xi * (bitmap.width - 1) / (steps - 1), yi * (bitmap.height - 1) / (steps - 1)))
            if (a < 16) clear++ else if (a < 240) partial++
        }
        val total = steps * steps
        return "transparent ${clear * 100 / total}%, semi ${partial * 100 / total}%, solid ${(total - clear - partial) * 100 / total}%"
    }

    /** App and phone memory in one line. */
    fun memory(): String {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val javaMax = runtime.maxMemory() / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        val system = appContext?.getSystemService(ActivityManager::class.java)?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo).let {
                " | phone free ${it.availMem / MB}/${it.totalMem / MB} MB" + if (it.lowMemory) " (LOW)" else ""
            }
        }.orEmpty()
        return "Mem: java ${javaUsed}/${javaMax} MB, native $native MB$system"
    }

    /** Everything to paste: phone, app, why recent runs ended, the log, and the app's own system log. */
    fun report(): String = buildString {
        val context = appContext
        appendLine("=== Viper Vision diagnostic report (${time.format(Date())}) ===")
        if (context != null) {
            appendLine(versionOf(context))
            appendLine(deviceSummary(context))
            appendLine(runCatching { ServiceStatus.describe(context) }.getOrDefault("Accessibility service: unknown"))
        }
        appendLine(memory())
        appendLine()
        appendLine("=== Viper Vision log (newest last) ===")
        appendLine(readLog().takeLast(MAX_REPORT_LOG))
        appendLine()
        // Android logs every frame it draws; only warnings, errors and AI/crash lines are useful.
        appendLine("=== System warnings and errors (this app only, newest last) ===")
        appendLine(
            systemLog().lineSequence()
                .filter { line -> line.isNotBlank() && "ViperVision" !in line && USEFUL_SYSTEM_LINE.containsMatchIn(line) }
                .joinToString("\n")
                .takeLast(MAX_REPORT_SYSTEM_LOG)
        )
    }

    fun clear() {
        synchronized(this) {
            file?.writeText("")
            file?.let { File(it.parentFile, "log.1.txt").delete() }
        }
        line("APP", "Log cleared")
    }

    private fun readLog(): String = synchronized(this) {
        val current = file ?: return ""
        val old = File(current.parentFile, "log.1.txt")
        (if (old.exists()) old.readText() else "") + (if (current.exists()) current.readText() else "")
    }

    private fun line(tag: String, message: String) {
        val text = "${time.format(Date())} [$tag] $message\n"
        android.util.Log.i("ViperVision", "[$tag] $message")
        synchronized(this) {
            val target = file ?: return
            runCatching {
                if (target.length() > MAX_FILE) {
                    val old = File(target.parentFile, "log.1.txt")
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText(text)
            }
        }
    }

    private fun logPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences("viper_log", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("last_exit", 0L)
        val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull().orEmpty()
        exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }.forEach { exit ->
            line(
                "EXIT",
                "Previous run (pid ${exit.pid}, ${time.format(Date(exit.timestamp))}) ended: ${reasonName(exit.reason)}" +
                        (exit.description?.let { " — $it" } ?: "") +
                        ". It was using ${exit.pss / 1024} MB (PSS), ${exit.rss / 1024} MB (RSS)" +
                        ", importance ${exit.importance}"
            )
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit", it).apply() }
    }

    private fun reasonName(reason: Int): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "$reason" else when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED: LOW MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (app code)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native code, e.g. an AI model)"
        ApplicationExitInfo.REASON_ANR -> "FROZE (not responding)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "KILLED: too much resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "KILLED by a signal (often out of memory)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "closed normally"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user / force-stopped"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "other (system)"
        else -> "unknown ($reason)"
    }

    private fun systemLog(): String = runCatching {
        // An app can read only its own lines; this includes ONNX Runtime and Android messages
        // from earlier runs that the in-app log can't see (e.g. native crash details).
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500", "-b", "main,system,crash")
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().also { process.destroy() }
    }.getOrElse { "(couldn't read: ${it.message})" }

    private fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Viper Vision ${info.versionName}"
    }.getOrDefault("Viper Vision")

    private fun deviceSummary(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java)
        val heapClass = manager?.largeMemoryClass ?: 0
        return "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "CPU ${Build.SUPPORTED_ABIS.firstOrNull()}, ${Runtime.getRuntime().availableProcessors()} cores, " +
                "app heap limit $heapClass MB"
    }

    private fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    private const val MB = 1024L * 1024L
    private const val MAX_FILE = 1_000_000L
    private const val MAX_REPORT_LOG = 45_000
    private const val MAX_REPORT_SYSTEM_LOG = 12_000

    /** Warning/error/fatal lines, and anything from the AI engine or about crashes and memory. */
    private val USEFUL_SYSTEM_LINE = Regex("""^\S+ \S+ [WEF]/|onnx|Onnx|ORT|AndroidRuntime|libc|lowmemory|OutOfMemory""")
}
