package mammyframe

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.SwingUtilities

/**
 * The diagnostic log, %APPDATA%\MammyFrame\log.txt. It records as much as possible:
 *  - what the PC is (Windows, Java, every screen and its scaling and refresh rate)
 *  - everything the app or its libraries print (Java, Compose, the drawing library Skiko)
 *  - every freeze or stutter of the app's main thread over 100 ms, with what it was doing
 *  - the drawing library's own frame-rate counter and every slow frame
 *  - every click, key and window event, every screen change
 *  - memory and Java clean-up (GC) every 30 seconds
 *  - errors and crashes (and Java's crash report from an earlier run)
 * Each line is written straight away, so nothing is lost if the app dies.
 * "Copy diagnostic log" in the developer settings copies it.
 */
object Log {
    val file by lazy { File(dataDir, "log.txt") }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private const val MAX_BYTES = 3_000_000L
    private var out: PrintStream? = null
    private var originalOut: PrintStream = System.out

    @Synchronized
    private fun writer(): PrintStream {
        out?.let { return it }
        if (file.length() > MAX_BYTES) {
            // Keep it from growing forever: keep the newer half.
            runCatching {
                val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText("…(older lines removed)\n" + keep.substringAfter('\n'))
            }
        }
        return PrintStream(FileOutputStream(file, true), true, "UTF-8").also { out = it }
    }

    @Synchronized
    fun i(msg: String) {
        runCatching {
            val line = "${time.format(Date())}  [${Thread.currentThread().name}] $msg"
            writer().println(line)
            writer().flush()
        }
    }

    fun e(msg: String, t: Throwable?) {
        val trace = t?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() } ?: ""
        i("ERROR $msg${if (t != null) ": $t\n$trace" else ""}")
    }

    /** Everything worth knowing about this PC, then start recording everything. Call first thing. */
    fun startup(args: Array<String>) {
        i("================ Mammy Frame ${System.getProperty("jpackage.app-version") ?: "(dev)"} starting, args=${args.toList()}")
        i("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        i("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}, " +
            "max memory ${Runtime.getRuntime().maxMemory() / 1_048_576} MB, ${Runtime.getRuntime().availableProcessors()} processors")
        safely("Java options") { i("Java options: ${ManagementFactory.getRuntimeMXBean().inputArguments}") }
        i("Program: ${System.getProperty("jpackage.app-path")}, data: ${dataDir.path}")
        screens("at start")
        i("Drawing settings: " + listOf("skiko.renderApi", "skiko.vsync.enabled", "skiko.fps.enabled")
            .joinToString { "$it=${System.getProperty(it) ?: "(default)"}" })

        safely("console capture") { captureConsole() }
        Thread.setDefaultUncaughtExceptionHandler { th, t -> e("uncaught on thread ${th.name}", t) }
        safely("close hook") { Runtime.getRuntime().addShutdownHook(Thread { i("Mammy Frame closing (normal close)") }) }
        safely("crash reports") { crashReports() }
        safely("freeze watch") { watchMainThread() }
        safely("memory watch") { watchMemory() }
        i("Logging started")
    }

    /** Logging must never stop the app: anything that goes wrong here (even a missing part of Java) is just noted. */
    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { runCatching { e("diagnostics: $what", t) } }
    }

    fun screens(why: String) = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed { n, d ->
            val m = d.displayMode
            val c = d.defaultConfiguration
            i("Screen $n ($why): ${d.iDstring} ${m.width}x${m.height} @ ${m.refreshRate} Hz, " +
                "scaling ${(c.defaultTransform.scaleX * 100).toInt()}%, desktop area ${c.bounds}")
        }
    }.onFailure { e("reading screens", it) }

    /** Everything printed to the console by the app or any library goes into the log too. */
    private fun captureConsole() {
        originalOut = System.out
        fun tee(original: PrintStream, tag: String) = PrintStream(object : OutputStream() {
            private val buf = StringBuilder()
            override fun write(b: Int) {
                original.write(b)
                if (b == '\n'.code) { i("$tag ${buf.toString().trimEnd()}"); buf.setLength(0) } else buf.append(b.toChar())
            }
        }, true)
        System.setOut(tee(System.out, "(printed)"))
        System.setErr(tee(System.err, "(printed error)"))
    }

    /**
     * Freeze and stutter watch: every 100 ms, check the app's main (screen) thread answers. If it
     * takes longer than 100 ms, write down how long, and what it was busy doing.
     */
    private fun watchMainThread() {
        var edt: Thread? = null
        SwingUtilities.invokeLater { edt = Thread.currentThread() }
        Thread({
            while (true) {
                Thread.sleep(100)
                val asked = System.nanoTime()
                val answer = java.util.concurrent.atomic.AtomicLong(0L)
                SwingUtilities.invokeLater { answer.set(System.nanoTime()) }
                var reported = false
                while (answer.get() == 0L) {
                    Thread.sleep(10)
                    val waited = (System.nanoTime() - asked) / 1_000_000
                    if (!reported && waited >= 250) {
                        reported = true
                        val stack = edt?.stackTrace?.take(25)?.joinToString("\n    at ") ?: "?"
                        i("STALL: the app's screen thread hasn't answered for $waited ms. It is busy with:\n    at $stack")
                    }
                }
                val took = (answer.get() - asked) / 1_000_000
                if (took >= 100) i("STUTTER: the screen thread was held up $took ms")
            }
        }, "freeze-watch").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    /** Memory and Java clean-up (GC) time, every 30 seconds. */
    private fun watchMemory() {
        Thread({ try {
            var lastGcMs = 0L
            var lastGcCount = 0L
            while (true) {
                Thread.sleep(30_000)
                val rt = Runtime.getRuntime()
                val gcs = ManagementFactory.getGarbageCollectorMXBeans()
                val gcMs = gcs.sumOf { it.collectionTime.coerceAtLeast(0) }
                val gcCount = gcs.sumOf { it.collectionCount.coerceAtLeast(0) }
                i("Memory: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB used of ${rt.totalMemory() / 1_048_576} MB, " +
                    "clean-ups in last 30 s: ${gcCount - lastGcCount} taking ${gcMs - lastGcMs} ms, " +
                    "threads: ${Thread.activeCount()}")
                lastGcMs = gcMs; lastGcCount = gcCount
            }
        } catch (t: Throwable) { e("memory watch stopped", t) } }, "memory-watch").apply { isDaemon = true }.start()
    }

    /**
     * If Java itself crashed, it leaves an hs_err_pid*.log file. Copy the important part of any
     * new one into this log, once.
     */
    private fun crashReports() = runCatching {
        val seenFile = File(dataDir, "crashes-seen.txt")
        val seen = runCatching { seenFile.readLines().toSet() }.getOrDefault(emptySet())
        val app = System.getProperty("jpackage.app-path")?.let { File(it).parentFile }
        val places = listOfNotNull(
            dataDir, File(System.getProperty("user.dir")), File(System.getProperty("java.io.tmpdir")),
            app, app?.let { File(it, "app") },
        ).distinct()
        val found = places.flatMap { d -> d.listFiles { f -> f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }.orEmpty().toList() }
            .filter { it.path !in seen && System.currentTimeMillis() - it.lastModified() < 14L * 24 * 3600 * 1000 }
        for (f in found) {
            val lines = f.readLines()
            i("CRASH REPORT from an earlier run (${f.path}):\n" + lines.take(120).joinToString("\n"))
            seenFile.appendText(f.path + "\n")
        }
        if (found.isEmpty()) i("No Java crash reports found from earlier runs.")
    }.onFailure { e("looking for crash reports", it) }

    /** Opens the folder with the log in File Explorer. */
    fun openFolder() = runCatching { java.awt.Desktop.getDesktop().open(dataDir) }.onFailure { e("opening the log folder", it) }

    fun text(): String = runCatching { file.readText() }.getOrDefault("(no log yet)")

    /** The log plus the current state, ready to paste. */
    fun report(extra: String): String = buildString {
        append("Mammy Frame diagnostic log\n")
        append(extra).append("\n\n")
        // The last two runs (e.g. one that crashed and the one after it), at most ~150 KB.
        val all = text()
        val marker = "================ Mammy Frame"
        val last = all.lastIndexOf(marker)
        val before = if (last > 0) all.lastIndexOf(marker, last - 1) else -1
        val from = (if (before >= 0) before else maxOf(last, 0)).let { all.lastIndexOf('\n', it).coerceAtLeast(0) }
        append(all.substring(from).takeLast(150_000))
    }
}
