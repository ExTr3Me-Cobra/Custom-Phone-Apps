package com.mammyframe.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * AI rotate: checks every photo in the folder, turns the ones the AI is sure about, and keeps
 * the rest "for review".
 *
 * How a photo is turned (always so it can be undone):
 *  - JPEG: only its rotation tag is changed, so the picture itself is never re-saved and loses
 *    no quality. Undo puts the original tag back.
 *  - PNG / WebP: the picture is saved turned (without any quality loss), after keeping an exact
 *    copy of the original inside the app. Undo puts that copy back.
 *  - Other kinds (HEIC, GIF, ...) can't be saved by Android, so they're turned inside Mammy
 *    Frame only.
 *
 * Everything to do with the records runs on one background thread ([store]), one step at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AiRotate {
    enum class Method { EXIF, PIXELS, APP }
    enum class Kind { UPRIGHT, AUTO, REVIEW, MANUAL }

    /** What we know about one photo. Angles are degrees clockwise from the original photo. */
    data class Rec(
        val uri: String,
        val folder: String,
        val name: String,
        val mime: String,
        val modified: Long,
        val size: Long,
        /** The camera's own rotation tag when first checked (0 = none). */
        val origExif: Int,
        /** The AI's suggested turn, and how sure it is (0..1). */
        val fix: Int,
        val certainty: Float,
        /** Your choice from the review list (-1 = none yet). */
        val decision: Int = -1,
        /** The turn currently saved to the file (or shown in the app, for [Method.APP]). */
        val applied: Int = 0,
        val method: Method,
    ) {
        /** The camera recorded a turn, and the AI disagrees with it: always ask. */
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
        val message: String? = null,
        /** Goes up every time anything changes, so screens know to refresh. */
        val revision: Int = 0,
    )

    private val store = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + store)
    private val records = LinkedHashMap<String, Rec>()
    private var scanJob: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    // ---------------------------------------------------------------- the rules

    fun kind(r: Rec, threshold: Float): Kind = when {
        r.decision >= 0 -> Kind.MANUAL
        r.origExif in 2..8 && r.fix == 0 -> Kind.UPRIGHT // the camera and the AI agree
        r.cameraDisagrees -> Kind.REVIEW
        r.certainty >= threshold -> if (r.fix == 0) Kind.UPRIGHT else Kind.AUTO
        else -> Kind.REVIEW
    }

    fun target(r: Rec, threshold: Float): Int = when (kind(r, threshold)) {
        Kind.MANUAL -> r.decision
        Kind.AUTO -> r.fix
        else -> 0
    }

    private fun desired(r: Rec): Int {
        val s = _status.value
        return if (s.rotationsOn) target(r, s.threshold) else 0
    }

    // ---------------------------------------------------------------- saving the list

    private fun dir(c: Context) = File(c.filesDir, "airotate").apply { mkdirs() }
    private fun backupFile(c: Context, uri: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dir(c), "originals").apply { mkdirs() }, h)
    }

    private fun ensureLoaded(c: Context) {
        if (_status.value.loaded) return
        val f = File(dir(c), "state.json")
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("records")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val r = Rec(
                    uri = j.getString("uri"), folder = j.getString("folder"), name = j.optString("name"),
                    mime = j.optString("mime"), modified = j.getLong("modified"), size = j.getLong("size"),
                    origExif = j.getInt("origExif"), fix = j.getInt("fix"), certainty = j.getDouble("certainty").toFloat(),
                    decision = j.getInt("decision"), applied = j.getInt("applied"),
                    method = Method.valueOf(j.getString("method")),
                )
                records[r.uri] = r
            }
            _status.update { it.copy(threshold = o.getDouble("threshold").toFloat(), rotationsOn = o.getBoolean("rotationsOn")) }
        }
        _status.update { it.copy(loaded = true) }
    }

    private fun save(c: Context) {
        val arr = JSONArray()
        records.values.forEach { r ->
            arr.put(JSONObject().apply {
                put("uri", r.uri); put("folder", r.folder); put("name", r.name); put("mime", r.mime)
                put("modified", r.modified); put("size", r.size); put("origExif", r.origExif)
                put("fix", r.fix); put("certainty", r.certainty.toDouble()); put("decision", r.decision)
                put("applied", r.applied); put("method", r.method.name)
            })
        }
        val s = _status.value
        val o = JSONObject().put("threshold", s.threshold.toDouble()).put("rotationsOn", s.rotationsOn).put("records", arr)
        val tmp = File(dir(c), "state.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir(c), "state.json"))
    }

    private fun changed() = _status.update { it.copy(revision = it.revision + 1) }

    // ---------------------------------------------------------------- reading, for the screens

    suspend fun recordsIn(c: Context, folder: String?): List<Rec> = withContext(store) {
        ensureLoaded(c)
        records.values.filter { it.folder == folder }
    }

    /** Turns that only exist inside the app (photo kinds Android can't save), for the slideshow. */
    suspend fun appTurns(c: Context): Map<String, Int> = withContext(store) {
        ensureLoaded(c)
        records.values.filter { it.method == Method.APP && it.applied != 0 }.associate { it.uri to it.applied }
    }

    fun load(c: Context) {
        scope.launch { ensureLoaded(c.applicationContext); changed() }
    }

    fun clearMessage() = _status.update { it.copy(message = null) }

    // ---------------------------------------------------------------- changing things

    fun setThreshold(c: Context, t: Float) = scope.launch {
        ensureLoaded(c)
        _status.update { it.copy(threshold = t) }
        syncAll(c.applicationContext)
    }

    fun setRotationsOn(c: Context, on: Boolean) = scope.launch {
        ensureLoaded(c)
        _status.update { it.copy(rotationsOn = on) }
        syncAll(c.applicationContext)
    }

    /** Your choice for one photo (degrees clockwise from the original; 0 = leave it as it is). */
    fun decide(c: Context, uris: Collection<String>, choose: (Rec) -> Int) = scope.launch {
        val app = c.applicationContext
        ensureLoaded(app)
        for (u in uris) {
            val r = records[u] ?: continue
            records[u] = r.copy(decision = ((choose(r) % 360) + 360) % 360)
            writeFile(app, records[u]!!)
        }
        save(app)
        changed()
    }

    private suspend fun syncAll(c: Context) {
        _status.update { it.copy(saving = true) }
        try {
            for (u in records.keys.toList()) {
                val r = records[u] ?: continue
                if (desired(r) != r.applied) writeFile(c, r)
            }
        } finally {
            save(c)
            _status.update { it.copy(saving = false) }
            changed()
        }
    }

    // ---------------------------------------------------------------- checking the folder

    fun start(c: Context, folder: String?) {
        if (folder == null || scanJob?.isActive == true) return
        val app = c.applicationContext
        val tree = Uri.parse(folder)
        val canWrite = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!canWrite) {
            _status.update {
                it.copy(message = "Mammy Frame isn't allowed to save changes in this folder yet. " +
                    "Tap the Photos folder card and choose the same folder again, then try once more.")
            }
            return
        }
        // Marked as running before the service starts, so the service can simply stop when it isn't.
        _status.update { it.copy(running = true, done = 0, total = 0, message = null) }
        scanJob = scope.launch { scan(app, folder) }
        ContextCompat.startForegroundService(app, Intent(app, RotateService::class.java))
    }

    fun stop() {
        scanJob?.cancel()
    }

    val isRunning get() = scanJob?.isActive == true

    private suspend fun scan(c: Context, folder: String) {
        ensureLoaded(c)
        var model: OrientationModel? = null
        try {
            val files = Photos.files(c, folder)
            _status.update { it.copy(total = files.size) }
            // Forget photos that are no longer in the folder.
            val present = files.map { it.uri.toString() }.toSet()
            records.values.filter { it.folder == folder && it.uri !in present }.forEach { records.remove(it.uri) }

            var sinceSave = 0
            for ((i, f) in files.withIndex()) {
                currentCoroutineContext().ensureActive()
                val key = f.uri.toString()
                val known = records[key]
                if (known == null || known.modified != f.modified || known.size != f.size) {
                    // New, or changed by something else since: check it from scratch.
                    if (known != null) backupFile(c, key).delete()
                    if (model == null) model = withContext(Dispatchers.Default) { OrientationModel.open(c) }
                    val rec = analyze(c, model, f, folder)
                    if (rec != null) {
                        records[key] = rec
                        writeFile(c, rec)
                        changed()
                    }
                    if (++sinceSave >= 10) { save(c); sinceSave = 0 }
                }
                _status.update { it.copy(done = i + 1) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.update { it.copy(message = "Something went wrong while checking: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            model?.close()
            save(c)
            _status.update { it.copy(running = false) }
            changed()
        }
    }

    private suspend fun analyze(c: Context, model: OrientationModel, f: PhotoFile, folder: String): Rec? {
        val cr = c.contentResolver
        val exif = runCatching {
            cr.openInputStream(f.uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) } ?: 0
        }.getOrDefault(0)
        val square = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, f.uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.setTargetSize(OrientationModel.RESIZE, OrientationModel.RESIZE)
            }
        }.getOrNull() ?: return null
        val result = withContext(Dispatchers.Default) {
            try { model.predict(square) } finally { square.recycle() }
        }
        val method = when (f.mime) {
            "image/jpeg" -> Method.EXIF
            "image/png", "image/webp" -> Method.PIXELS
            else -> Method.APP
        }
        return Rec(
            uri = f.uri.toString(), folder = folder, name = f.name, mime = f.mime,
            modified = f.modified, size = f.size, origExif = exif,
            fix = result.fixCw, certainty = result.certainty, method = method,
        )
    }

    // ---------------------------------------------------------------- writing the files

    /** Makes the file match what it should be now. Always starts from the original, so it can be undone. */
    private fun writeFile(c: Context, r0: Rec) {
        val want = desired(r0)
        if (want == r0.applied) return
        var r = r0
        val uri = Uri.parse(r.uri)
        val ok = runCatching {
            when (r.method) {
                Method.EXIF -> c.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    val ex = ExifInterface(pfd.fileDescriptor)
                    ex.setAttribute(ExifInterface.TAG_ORIENTATION, (if (r.origExif in 1..8) r.origExif else 1).toString())
                    if (want != 0) ex.rotate(want)
                    ex.saveAttributes()
                }
                Method.PIXELS -> {
                    val backup = backupFile(c, r.uri)
                    if (!backup.exists()) {
                        val tmp = File(backup.path + ".tmp")
                        c.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        tmp.renameTo(backup)
                    }
                    c.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        if (want == 0) {
                            backup.inputStream().use { it.copyTo(out) }
                        } else {
                            val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(backup)) { d, _, _ ->
                                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                            val turned = Photos.turned(full, want)
                            val format = if (r.mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSLESS
                            turned.compress(format, 100, out)
                            if (turned !== full) turned.recycle()
                            full.recycle()
                        }
                    }
                    if (want == 0) backup.delete()
                }
                Method.APP -> Unit
            }
        }.isSuccess
        if (!ok && r.method != Method.APP) {
            // Couldn't save to this file (some storage won't allow it): turn it inside the app instead.
            // First make sure the file is back as it was, if part of a change got through.
            r = r.copy(method = Method.APP, applied = 0)
            records[r.uri] = r
            writeFile(c, r)
            return
        }
        // Remember the file's new date and size, so it isn't mistaken for a changed photo.
        val (mod, size) = stamp(c, uri) ?: (r.modified to r.size)
        records[r.uri] = r.copy(applied = want, modified = if (r.method == Method.APP) r.modified else mod,
            size = if (r.method == Method.APP) r.size else size)
    }

    private fun stamp(c: Context, uri: Uri): Pair<Long, Long>? = runCatching {
        c.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null
        )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
    }.getOrNull()
}
