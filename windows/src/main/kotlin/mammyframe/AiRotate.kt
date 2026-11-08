package mammyframe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import javax.imageio.ImageIO

/**
 * AI rotate (Windows): checks every photo in the folder, turns the ones the AI is sure about,
 * and keeps the rest "for review". Same rules as the tablet version.
 *
 * How a photo is turned (always so it can be undone):
 *  - JPEG: only its rotation tag is changed (the picture itself isn't re-saved, so no quality is
 *    lost); its "date modified" is kept. Undo puts the original tag back.
 *  - PNG / BMP: saved turned (no quality loss), with an exact copy of the original kept for undo.
 *  - WebP / GIF: turned inside Mammy Frame only.
 *
 * Everything to do with the records runs on one background thread ([store]), one step at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AiRotate {
    enum class Method { EXIF, PIXELS, APP }
    enum class Kind { UPRIGHT, AUTO, REVIEW, MANUAL }

    data class Rec(
        val path: String,
        val folder: String,
        val name: String,
        val ext: String,
        val modified: Long,
        val size: Long,
        val origExif: Int,
        val fix: Int,
        val certainty: Float,
        val decision: Int = -1,
        val applied: Int = 0,
        val method: Method,
    ) {
        val cameraDisagrees get() = origExif in 2..8 && fix != 0
    }

    data class Status(
        val loaded: Boolean = false,
        val running: Boolean = false,
        val saving: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val threshold: Float = 0.95f,
        val rotationsOn: Boolean = true,
        val reviewShowsAi: Boolean = true,
        val askBeforeTurning: Boolean = false,
        val message: String? = null,
        val revision: Int = 0,
        val startedAt: Long = 0L,
        val toAnalyze: Int = 0,
        val analyzed: Int = 0,
        val analyzeStartedAt: Long = 0L,
        val lastRunMs: Long = -1L,
        val lastRunAnalyzed: Int = 0,
        val lastRunTotal: Int = 0,
        val lastRunFinished: Boolean = true,
        val lastRunEndedAt: Long = 0L,
    )

    private val store = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + store)
    private val records = LinkedHashMap<String, Rec>()
    private var scanJob: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun now() = System.nanoTime() / 1_000_000

    // ---------------------------------------------------------------- the rules

    fun kind(r: Rec, threshold: Float, askFirst: Boolean = _status.value.askBeforeTurning): Kind = when {
        r.decision >= 0 -> Kind.MANUAL
        r.origExif in 2..8 && r.fix == 0 -> Kind.UPRIGHT
        r.cameraDisagrees -> Kind.REVIEW
        r.certainty >= threshold -> when {
            r.fix == 0 -> Kind.UPRIGHT
            askFirst -> Kind.REVIEW
            else -> Kind.AUTO
        }
        else -> Kind.REVIEW
    }

    fun waitingForOk(r: Rec, threshold: Float): Boolean =
        r.decision < 0 && !r.cameraDisagrees && r.fix != 0 && r.certainty >= threshold

    fun target(r: Rec, threshold: Float): Int = when (kind(r, threshold)) {
        Kind.MANUAL -> r.decision
        Kind.AUTO -> r.fix
        else -> 0
    }

    private fun desired(r: Rec): Int {
        val s = _status.value
        return if (s.rotationsOn) target(r, s.threshold) else 0
    }

    // ---------------------------------------------------------------- the list (SQLite) and settings

    private val dir get() = File(dataDir, "airotate").apply { mkdirs() }
    private fun backupFile(path: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dir, "originals").apply { mkdirs() }, h)
    }

    private var conn: Connection? = null
    private fun db(): Connection = conn ?: DriverManager.getConnection("jdbc:sqlite:" + File(dir, "airotate.db").path).also {
        it.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS photos (
                    path TEXT PRIMARY KEY, folder TEXT NOT NULL, name TEXT, ext TEXT,
                    modified INTEGER, size INTEGER, origExif INTEGER, fix INTEGER, certainty REAL,
                    decision INTEGER, applied INTEGER, method TEXT)"""
            )
        }
        conn = it
    }

    private val settingsFile get() = File(dir, "settings.properties")

    private fun ensureLoaded() {
        if (_status.value.loaded) return
        db().createStatement().use { st ->
            st.executeQuery("SELECT path, folder, name, ext, modified, size, origExif, fix, certainty, decision, applied, method FROM photos").use { rs ->
                while (rs.next()) {
                    val r = Rec(
                        rs.getString(1), rs.getString(2), rs.getString(3) ?: "", rs.getString(4) ?: "",
                        rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getInt(8), rs.getFloat(9),
                        rs.getInt(10), rs.getInt(11),
                        runCatching { Method.valueOf(rs.getString(12)) }.getOrDefault(Method.APP),
                    )
                    records[r.path] = r
                }
            }
        }
        val p = Properties()
        runCatching { settingsFile.inputStream().use(p::load) }
        fun f(k: String, d: Float) = p.getProperty(k)?.toFloatOrNull() ?: d
        fun b(k: String, d: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
        fun l(k: String, d: Long) = p.getProperty(k)?.toLongOrNull() ?: d
        fun i(k: String, d: Int) = p.getProperty(k)?.toIntOrNull() ?: d
        _status.update {
            it.copy(
                loaded = true,
                threshold = f("threshold", it.threshold), rotationsOn = b("rotationsOn", it.rotationsOn),
                reviewShowsAi = b("reviewShowsAi", it.reviewShowsAi), askBeforeTurning = b("askBeforeTurning", it.askBeforeTurning),
                lastRunMs = l("lastRunMs", -1L), lastRunAnalyzed = i("lastRunAnalyzed", 0), lastRunTotal = i("lastRunTotal", 0),
                lastRunFinished = b("lastRunFinished", true), lastRunEndedAt = l("lastRunEndedAt", 0L),
            )
        }
    }

    private fun saveSettings() {
        val s = _status.value
        val p = Properties()
        p.setProperty("threshold", s.threshold.toString())
        p.setProperty("rotationsOn", s.rotationsOn.toString())
        p.setProperty("reviewShowsAi", s.reviewShowsAi.toString())
        p.setProperty("askBeforeTurning", s.askBeforeTurning.toString())
        p.setProperty("lastRunMs", s.lastRunMs.toString())
        p.setProperty("lastRunAnalyzed", s.lastRunAnalyzed.toString())
        p.setProperty("lastRunTotal", s.lastRunTotal.toString())
        p.setProperty("lastRunFinished", s.lastRunFinished.toString())
        p.setProperty("lastRunEndedAt", s.lastRunEndedAt.toString())
        runCatching { settingsFile.outputStream().use { p.store(it, "Mammy Frame AI rotate") } }
    }

    /** Remembers one photo (in memory and in the database: one row, not the whole list). */
    private fun put(r: Rec) {
        records[r.path] = r
        db().prepareStatement("INSERT OR REPLACE INTO photos VALUES (?,?,?,?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.path); ps.setString(2, r.folder); ps.setString(3, r.name); ps.setString(4, r.ext)
            ps.setLong(5, r.modified); ps.setLong(6, r.size); ps.setInt(7, r.origExif); ps.setInt(8, r.fix)
            ps.setFloat(9, r.certainty); ps.setInt(10, r.decision); ps.setInt(11, r.applied); ps.setString(12, r.method.name)
            ps.executeUpdate()
        }
    }

    private fun forget(path: String) {
        records.remove(path)
        db().prepareStatement("DELETE FROM photos WHERE path = ?").use { it.setString(1, path); it.executeUpdate() }
    }

    private fun <T> batch(block: () -> T): T {
        val c = db()
        c.autoCommit = false
        try {
            val r = block()
            c.commit()
            return r
        } catch (e: Throwable) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
    }

    private var lastNotice = 0L
    private fun changed() {
        lastNotice = now()
        _status.update { it.copy(revision = it.revision + 1) }
    }

    private fun changedSoon(done: Int, analyzed: Int) {
        if (now() - lastNotice < 1000) return
        lastNotice = now()
        _status.update { it.copy(done = done, analyzed = analyzed, revision = it.revision + 1) }
    }

    // ---------------------------------------------------------------- reading, for the screens

    suspend fun recordsIn(folder: String?): List<Rec> = withContext(store) {
        ensureLoaded()
        records.values.filter { it.folder == folder }
    }

    suspend fun appTurns(): Map<String, Int> = withContext(store) {
        ensureLoaded()
        records.values.filter { it.method == Method.APP && it.applied != 0 }.associate { it.path to it.applied }
    }

    suspend fun get(path: String): Rec? = withContext(store) { ensureLoaded(); records[path] }

    fun load() { scope.launch { ensureLoaded(); changed() } }

    fun clearMessage() = _status.update { it.copy(message = null) }

    // ---------------------------------------------------------------- changing things

    fun setThreshold(t: Float) = scope.launch { ensureLoaded(); _status.update { it.copy(threshold = t) }; syncAll() }
    fun setRotationsOn(on: Boolean) = scope.launch { ensureLoaded(); _status.update { it.copy(rotationsOn = on) }; syncAll() }
    fun setAskBeforeTurning(on: Boolean) = scope.launch { ensureLoaded(); _status.update { it.copy(askBeforeTurning = on) }; syncAll() }
    fun setReviewShowsAi(on: Boolean) = scope.launch {
        ensureLoaded(); _status.update { it.copy(reviewShowsAi = on) }; saveSettings(); changed()
    }

    /** Your choice for a photo (degrees clockwise from the original; 0 = leave it as it is). */
    fun decide(paths: Collection<String>, choose: (Rec) -> Int) = scope.launch {
        ensureLoaded()
        for (p in paths) {
            val r = records[p] ?: continue
            val chosen = r.copy(decision = ((choose(r) % 360) + 360) % 360)
            put(chosen)
            writeFile(chosen)
        }
        changed()
    }

    private fun syncAll() {
        saveSettings()
        val toChange = records.values.filter { desired(it) != it.applied }
        if (toChange.isEmpty()) { changed(); return }
        _status.update { it.copy(saving = true) }
        try {
            batch { for (r in toChange) writeFile(r) }
        } finally {
            _status.update { it.copy(saving = false) }
            changed()
        }
    }

    // ---------------------------------------------------------------- checking the folder

    fun start(folder: String?) {
        if (folder == null || scanJob?.isActive == true) return
        if (!Files.isWritable(File(folder).toPath())) {
            _status.update { it.copy(message = "Mammy Frame isn't allowed to change files in this folder.") }
            return
        }
        _status.update { it.copy(running = true, done = 0, total = 0, message = null) }
        scanJob = scope.launch { scan(folder) }
    }

    fun stop(message: String? = null) {
        scanJob?.cancel()
        if (message != null) _status.update { it.copy(message = message) }
    }

    private suspend fun scan(folder: String) {
        ensureLoaded()
        var engine: AiEngine? = null
        val started = now()
        _status.update { it.copy(startedAt = started, toAnalyze = 0, analyzed = 0, analyzeStartedAt = 0L) }
        var analyzed = 0
        var finished = false
        var total = 0
        try {
            val files = Photos.files(folder)
            total = files.size
            fun needsCheck(f: PhotoFile) = records[f.path].let { it == null || it.modified != f.modified || it.size != f.size }
            val toAnalyze = files.count(::needsCheck)
            _status.update { it.copy(total = files.size, toAnalyze = toAnalyze, analyzeStartedAt = now()) }
            val present = files.map { it.path }.toSet()
            val gone = records.values.filter { it.folder == folder && it.path !in present }
            if (gone.isNotEmpty()) batch { gone.forEach { forget(it.path) } }
            Photos.remember(folder, files.map { it.path })

            for ((i, f) in files.withIndex()) {
                currentCoroutineContext().ensureActive()
                val known = records[f.path]
                if (known == null || known.modified != f.modified || known.size != f.size) {
                    if (known != null) backupFile(f.path).delete()
                    // The AI runs in its own helper program when installed, so an AI crash can't take the app down.
                    if (engine == null) engine = System.getProperty("jpackage.app-path")?.let { WorkerEngine(it) } ?: InProcessEngine()
                    // One photo at a time; a photo that can't be read or checked doesn't stop the rest.
                    val rec = try {
                        engine.check(File(f.path))?.let { a -> recFor(f, folder, a) }
                    } catch (e: AiCrashed) {
                        recFor(f, folder, AiAnswer(Photos.exifOrientation(File(f.path)), 0, 0f)) // goes to review
                    } catch (e: Exception) {
                        Log.e("couldn't check ${f.path}", e); null
                    }
                    if (rec != null) {
                        put(rec)
                        writeFile(rec)
                    }
                    analyzed++
                }
                changedSoon(i + 1, analyzed)
            }
            _status.update { it.copy(done = files.size, analyzed = analyzed) }
            finished = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.update { it.copy(message = "Something went wrong while checking: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            // Only closed once the photo being checked is finished (checks run on this same thread).
            runCatching { engine?.close() }
            _status.update {
                it.copy(running = false, lastRunMs = now() - started, lastRunAnalyzed = analyzed, lastRunTotal = total,
                    lastRunFinished = finished, lastRunEndedAt = System.currentTimeMillis())
            }
            saveSettings()
            changed()
        }
    }

    private fun recFor(f: PhotoFile, folder: String, a: AiAnswer): Rec {
        val method = when (f.ext) {
            "jpg", "jpeg" -> Method.EXIF
            "png", "bmp" -> Method.PIXELS
            else -> Method.APP
        }
        return Rec(f.path, folder, f.name, f.ext, f.modified, f.size, a.exif, a.fixCw, a.certainty, method = method)
    }

    // ---------------------------------------------------------------- writing the files

    /** Sets a JPEG's rotation tag without re-saving the picture itself (lossless). */
    fun writeJpegOrientation(file: File, tag: Int) {
        val meta = Imaging.getMetadata(file) as? JpegImageMetadata
        val out = meta?.exif?.outputSet ?: TiffOutputSet()
        val root = out.getOrCreateRootDirectory()
        root.removeField(TiffTagConstants.TIFF_TAG_ORIENTATION)
        root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, tag.toShort())
        val tmp = File(file.parentFile, ".${file.name}.mammyframe.tmp")
        BufferedOutputStream(tmp.outputStream()).use { ExifRewriter().updateExifMetadataLossless(file, it, out) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Makes the file match what it should be now. Always starts from the original, so it can be undone. */
    private fun writeFile(r0: Rec) {
        val want = desired(r0)
        if (want == r0.applied) return
        var r = r0
        val file = File(r.path)
        val keepDate = runCatching { Files.getLastModifiedTime(file.toPath()) }.getOrNull()
        val ok = runCatching {
            when (r.method) {
                Method.EXIF -> writeJpegOrientation(file, Orientation.turned(if (r.origExif in 1..8) r.origExif else 1, want))
                Method.PIXELS -> {
                    val backup = backupFile(r.path)
                    if (!backup.exists()) Files.copy(file.toPath(), backup.toPath())
                    val tmp = File(file.parentFile, ".${file.name}.mammyframe.tmp")
                    if (want == 0) {
                        Files.copy(backup.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        val img = Photos.decode(backup.path, Int.MAX_VALUE, want) ?: error("can't read")
                        check(ImageIO.write(img, if (r.ext == "bmp") "bmp" else "png", tmp)) { "can't save" }
                    }
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    if (want == 0) backup.delete()
                }
                Method.APP -> Unit
            }
        }.isSuccess
        if (!ok && r.method != Method.APP) {
            r = r.copy(method = Method.APP, applied = 0)
            put(r)
            writeFile(r)
            return
        }
        if (r.method != Method.APP && keepDate != null) runCatching { Files.setLastModifiedTime(file.toPath(), keepDate) }
        val mod = if (r.method == Method.APP) r.modified else file.lastModified()
        val size = if (r.method == Method.APP) r.size else file.length()
        put(r.copy(applied = want, modified = mod, size = size))
    }
}

