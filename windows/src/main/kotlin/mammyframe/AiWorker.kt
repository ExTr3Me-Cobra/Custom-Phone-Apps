package mammyframe

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.TimeUnit

/** What the AI found for one photo: the camera's tag, the turn it suggests, and how sure it is. */
data class AiAnswer(val exif: Int, val fixCw: Int, val certainty: Float)

/** Looks at one photo. Shared by the helper program and the in-app fallback. */
fun checkPhoto(model: OrientationModel, file: File): AiAnswer? {
    val exif = Photos.exifOrientation(file)
    // Read at about twice the AI's size, put the right way up for its camera tag, then squash to 416x416.
    val img = Photos.read(file, OrientationModel.RESIZE * 2) ?: return null
    val (flip, rot) = Orientation.toFlipRotate(exif)
    val square = Photos.scaleTo(Photos.transform(img, flip, rot), OrientationModel.RESIZE, OrientationModel.RESIZE)
    val r = model.predict(square)
    return AiAnswer(exif, r.fixCw, r.certainty)
}

/** Something that checks photos one at a time. */
interface AiEngine : Closeable {
    /** null = the photo couldn't be read. Throws [AiCrashed] if the AI itself failed twice on it. */
    fun check(file: File): AiAnswer?
}

class AiCrashed(msg: String) : Exception(msg)

/**
 * The AI runs in a separate helper program (the same Mammy Frame program, started with
 * --ai-worker). If the AI library ever crashes, only the helper dies: Mammy Frame notes it,
 * starts a new helper, tries that photo once more, and otherwise carries on with the next.
 */
class WorkerEngine(private val exe: String) : AiEngine {
    private var process: Process? = null
    private var toWorker: PrintWriter? = null
    private var fromWorker: BufferedReader? = null
    var restarts = 0
        private set

    private fun start() {
        val p = ProcessBuilder(exe, "--ai-worker").redirectErrorStream(false).start()
        // The helper's own messages go into the log.
        Thread({
            runCatching { p.errorStream.bufferedReader().forEachLine { Log.i("(AI helper) $it") } }
        }, "ai-helper-messages").apply { isDaemon = true }.start()
        toWorker = PrintWriter(p.outputStream.bufferedWriter(), true)
        fromWorker = p.inputStream.bufferedReader()
        val hello = fromWorker!!.readLine()
        if (hello != "READY") { p.destroyForcibly(); throw AiCrashed("the AI helper didn't start (said: $hello)") }
        process = p
        Log.i("AI helper started (process ${p.pid()})")
    }

    private fun stop() {
        runCatching { toWorker?.println("QUIT") }
        process?.let { if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null; toWorker = null; fromWorker = null
    }

    private fun ask(file: File): AiAnswer? {
        if (process?.isAlive != true) start()
        toWorker!!.println(file.path)
        val line = fromWorker!!.readLine() ?: throw AiCrashed("the AI helper stopped (exit ${process?.waitFor()})")
        val parts = line.split('\t')
        return when (parts[0]) {
            "OK" -> AiAnswer(parts[1].toInt(), parts[2].toInt(), parts[3].toFloat())
            "NONE" -> null
            else -> throw IllegalStateException("AI helper error: ${parts.drop(1).joinToString(" ")}")
        }
    }

    override fun check(file: File): AiAnswer? {
        return try {
            ask(file)
        } catch (e: AiCrashed) {
            Log.i("AI helper crashed on ${file.path}: ${e.message}. Starting a new one and trying again.")
            stop(); restarts++
            try { ask(file) } catch (e2: AiCrashed) {
                Log.i("AI helper crashed again on ${file.path}; that photo goes to review.")
                stop(); restarts++
                throw e2
            }
        }
    }

    override fun close() = stop()

    companion object {
        /** The helper program's side: answer one photo per line until told to stop. */
        fun runWorker(): Nothing {
            val out = System.out
            System.setOut(System.err) // anything else printed goes to the log, not the answers
            val model = OrientationModel.open()
            out.println("READY"); out.flush()
            val input = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val path = input.readLine() ?: break
                if (path == "QUIT") break
                // For the automatic test only: die abruptly on one chosen photo, like a real AI crash.
                if (System.getenv("MAMMYFRAME_TEST_CRASH_ON")?.let { File(path).name == it } == true) Runtime.getRuntime().halt(3)
                val answer = try {
                    checkPhoto(model, File(path))?.let { "OK\t${it.exif}\t${it.fixCw}\t${it.certainty}" } ?: "NONE"
                } catch (t: Throwable) {
                    "ERR\t${t.javaClass.simpleName}: ${t.message?.replace('\t', ' ')?.replace('\n', ' ')}"
                }
                out.println(answer); out.flush()
            }
            model.close()
            kotlin.system.exitProcess(0)
        }
    }
}

/** In the app itself (used when running without the installed program, e.g. on the build machine). */
class InProcessEngine : AiEngine {
    private val model = OrientationModel.open()
    override fun check(file: File) = checkPhoto(model, file)
    override fun close() = model.close()
}
