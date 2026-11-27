package com.labelrescue.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.labelrescue.core.fusion.DiagnosticLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything for finding bugs and crashes, kept apart from the training export:
 *  - a crash in the app's own code writes crash-<time>.txt (the error, its stack, and the end of
 *    the scan's log at that moment) before the app dies;
 *  - during a scan its log is saved every couple of seconds (scan_in_progress.txt), so even a
 *    native crash (camera, ML Kit, LiteRT), which kills the app without warning, leaves a trace;
 *  - Android's own record of why the app last stopped (ApplicationExitInfo, Android 11+), with
 *    the native tombstone or ANR trace when there is one.
 * [report] puts it all in one text for "Copy bug report" (Developer Mode settings).
 */
object CrashLog {
    @Volatile private var dir: File? = null
    /** The scan running now (null between scans). */
    @Volatile var current: DiagnosticLog? = null
    private val crumbs = ArrayDeque<String>()
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun install(ctx: Context) {
        val d = File(ctx.filesDir, "crash_logs").apply { mkdirs() }
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val scan = current?.text()?.takeLast(120_000).orEmpty()
                File(d, "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt").writeText(
                    "CRASH $stamp on thread ${t.name}\n${header(ctx)}\n\n$sw\nRecent app events:\n${crumbsText()}\n\nScan log at the crash (end):\n$scan\n",
                )
                // Keep the newest few.
                d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** An error the app caught and survived (the scan failed): recorded like a crash. */
    fun caught(ctx: Context, e: Throwable) {
        val d = dir ?: return
        runCatching {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            File(d, "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-caught.txt").writeText(
                "ERROR (caught, the scan failed) $stamp\n${header(ctx)}\n\n$sw\nRecent app events:\n${crumbsText()}\n\nScan log (end):\n${current?.text()?.takeLast(120_000).orEmpty()}\n",
            )
        }
    }

    /** A short app event (screen, scan start/end) for context in a crash report. */
    fun note(s: String) { synchronized(crumbs) { crumbs.addLast("$stamp $s"); while (crumbs.size > 60) crumbs.removeFirst() } }

    private fun crumbsText() = synchronized(crumbs) { crumbs.joinToString("\n") }

    /** Saves the running scan's log (called every couple of seconds during a scan). */
    fun saveProgress() {
        val d = dir ?: return
        val log = current ?: return
        runCatching { File(d, "scan_in_progress.txt").writeText("SCAN IN PROGRESS, saved $stamp\nRecent app events:\n${crumbsText()}\n\n" + log.text().takeLast(200_000)) }
    }

    /** The scan finished normally: its in-progress log is no longer a crash clue. */
    fun scanEnded() { current = null; dir?.let { File(it, "scan_in_progress.txt").delete() } }

    fun header(ctx: Context): String {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val rt = Runtime.getRuntime()
        return "app $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "memory used ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB"
    }

    /** Everything for a bug report, newest first, at most [limit] characters. */
    fun report(ctx: Context, lastScanLog: String?, limit: Int = 250_000): String {
        val d = dir ?: File(ctx.filesDir, "crash_logs")
        val sb = StringBuilder()
        sb.append("Label Rescue bug report, $stamp\n${header(ctx)}\n\n")
        sb.append("=== Why the app last stopped (Android's record) ===\n").append(exitReasons(ctx)).append('\n')
        val crashes = d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()
        sb.append("=== Crashes in the app's code: ${crashes.size} ===\n")
        for (f in crashes.take(3)) sb.append("--- ${f.name}\n").append(f.readText().take(60_000)).append('\n')
        File(d, "scan_in_progress.txt").takeIf { it.exists() }?.let {
            sb.append("=== A scan that never finished (the app stopped during it) ===\n").append(it.readText().takeLast(80_000)).append('\n')
        }
        sb.append("=== Recent app events ===\n").append(crumbsText()).append("\n\n")
        lastScanLog?.let { sb.append("=== Last finished scan's log (end) ===\n").append(it.takeLast(60_000)).append('\n') }
        return if (sb.length > limit) sb.substring(0, limit) + "\n[cut at $limit characters]" else sb.toString()
    }

    private fun exitReasons(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "(needs Android 11+)\n"
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return "(unavailable)\n"
        val list = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull().orEmpty()
        if (list.isEmpty()) return "(none recorded)\n"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return list.joinToString("\n") { x ->
            val reason = when (x.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash (app code)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH (camera, ML Kit, LiteRT...)"
                ApplicationExitInfo.REASON_ANR -> "ANR (app froze)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: too much CPU/memory"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
                ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
                ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
                ApplicationExitInfo.REASON_EXIT_SELF -> "app exited itself"
                ApplicationExitInfo.REASON_OTHER -> "other"
                else -> "reason ${x.reason}"
            }
            val trace = if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR)
                runCatching { x.traceInputStream?.bufferedReader()?.use { it.readText().take(30_000) } }.getOrNull()?.let { "\n  trace:\n$it" } ?: "" else ""
            "${fmt.format(Date(x.timestamp))}: $reason · ${x.description ?: ""} · status ${x.status} · memory ${x.pss / 1024} MB$trace"
        } + "\n"
    }

    fun clear(ctx: Context) { File(ctx.filesDir, "crash_logs").listFiles()?.forEach { it.delete() } }
}
