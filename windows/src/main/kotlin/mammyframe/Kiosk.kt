package mammyframe

import java.io.File

/**
 * Keeps Mammy Frame open: no close or minimise, always in front, and a small guard program that
 * opens it again if it's stopped some other way (Task Manager, a crash). The only way out is
 * "Force close Mammy Frame" in the developer settings.
 *
 * The guard is the same program started with --guard <pid>. Each watches the other: if the frame
 * stops, the guard opens it again; if the guard stops, the frame starts a new guard.
 */
object Kiosk {
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    /** Left by "Force close" (and by Windows shutting down or signing out): don't reopen. */
    private val stopFile get() = File(dataDir, "closed-on-purpose")

    /** "<guard's process number> <frame's process number>", so the frame can find its guard. */
    private val guardFile get() = File(dataDir, "guard.txt")

    /** Only for the installed program (not a test run or the build machine's checks). */
    val active get() = isWindows && exe != null

    /** Set by "Force close"; the window really closes. */
    @Volatile var closing = false
        private set

    /** "Open log folder": lets File Explorer come in front for a couple of minutes. */
    @Volatile var stepAsideUntil = 0L

    fun steppingAside() = System.currentTimeMillis() < stepAsideUntil

    /** Called when the frame starts. */
    fun start() {
        if (!active) return
        stopFile.delete()
        // Windows shutting down or signing out ends Java normally (a crash or Task Manager doesn't):
        // the guard mustn't fight that.
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { stopFile.writeText("windows") } })
        Thread({
            while (true) {
                val guard = runCatching { startGuard() }.onFailure { Log.e("starting the guard", it) }.getOrNull()
                if (guard == null) { Thread.sleep(30_000); continue }
                guard.onExit().join()
                if (closing || stopFile.exists()) return@Thread
                Log.i("The guard stopped; starting it again")
                Thread.sleep(2_000)
            }
        }, "guard-watch").apply { isDaemon = true }.start()
    }

    private fun startGuard(): ProcessHandle? {
        val path = exe ?: return null
        val me = ProcessHandle.current().pid()
        guardFile.delete()
        // Started through "start" so it isn't part of this program's process tree (ending the
        // frame's tree in Task Manager doesn't end the guard too).
        ProcessBuilder("cmd", "/c", "start", "\"\"", "/b", path, "--guard", me.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        // Find it (it's a new program, not a child of this one): it writes down its number.
        repeat(100) {
            val line = runCatching { guardFile.readText().trim().split(" ") }.getOrNull()
            if (line?.getOrNull(1) == me.toString()) {
                line[0].toLongOrNull()?.let(ProcessHandle::of)?.orElse(null)?.let {
                    Log.i("Guard running (process ${it.pid()})"); return it
                }
            }
            Thread.sleep(200)
        }
        Log.i("Couldn't find the guard after starting it")
        return null
    }

    private var lock: java.nio.channels.FileLock? = null

    /** False if Mammy Frame is already open (opening it again then does nothing). */
    fun onlyCopy(): Boolean {
        if (!active) return true
        return runCatching {
            val ch = java.io.RandomAccessFile(File(dataDir, "running.lock"), "rw").channel
            ch.tryLock()?.also { lock = it } != null
        }.getOrDefault(true)
    }

    /** "Force close Mammy Frame" in the developer settings. */
    fun forceClose(exit: () -> Unit) {
        Log.i("Force close Mammy Frame (developer settings)")
        closing = true
        runCatching { stopFile.writeText("force close") }
        exit()
    }

    /** The guard program: wait for the frame to stop; if it wasn't on purpose, open it again. */
    fun runGuard(args: Array<String>): Nothing {
        val path = exe
        var pid = args.getOrNull(args.indexOf("--guard") + 1)?.toLongOrNull()
        runCatching { guardFile.writeText("${ProcessHandle.current().pid()} $pid") }
        val starts = ArrayDeque<Long>()
        while (path != null && pid != null) {
            val frame = ProcessHandle.of(pid).orElse(null)
            frame?.onExit()?.join()
            Thread.sleep(1_500)
            if (stopFile.exists()) break
            // Somebody ended it (or it crashed): open it again, unless it keeps dying straight away.
            val now = System.currentTimeMillis()
            starts.addLast(now)
            while (starts.isNotEmpty() && now - starts.first() > 10 * 60_000) starts.removeFirst()
            if (starts.size > 5) {
                Log.i("Guard: Mammy Frame stopped 6 times in 10 minutes; not opening it again")
                break
            }
            Log.i("Guard: Mammy Frame stopped without being closed on purpose; opening it again")
            val p = runCatching { ProcessBuilder(path, "--startup", "--reopened").start() }
                .onFailure { Log.e("Guard: reopening", it) }.getOrNull() ?: break
            // The new frame starts its own guard; this one is done.
            Thread.sleep(5_000)
            if (p.isAlive) break
            pid = p.pid()
        }
        kotlin.system.exitProcess(0)
    }
}
