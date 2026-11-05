package com.mammyframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One picture in the folder, with what's needed to notice if it changes. */
data class PhotoFile(val uri: Uri, val name: String, val mime: String, val modified: Long, val size: Long)

object Photos {
    /** Every picture in the chosen folder and its subfolders (a fresh look; also saved for next time). */
    suspend fun list(c: Context, folderUri: String?): List<Uri> {
        val uris = files(c, folderUri).map { it.uri }
        if (folderUri != null) remember(c, folderUri, uris)
        return uris
    }

    // ------------------------------------------------ the saved list of photos
    // Looking through tens of thousands of photos takes a while, so the last list is kept and
    // used straight away; a fresh look only happens when the saved one is getting old.

    private const val FRESH_MS = 60 * 60 * 1000L
    private val saved = HashMap<String, Pair<Long, List<Uri>>>()
    private val _counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    /** How many photos each folder had last time it was looked through. */
    val counts: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _counts

    private fun listFile(c: Context, folder: String): java.io.File {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(folder.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(c.filesDir, "photolists").apply { mkdirs() }, "$h.txt")
    }

    @Synchronized
    fun remember(c: Context, folder: String, uris: List<Uri>) {
        saved[folder] = System.currentTimeMillis() to uris
        _counts.value = _counts.value + (folder to uris.size)
        runCatching {
            val f = listFile(c, folder)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.bufferedWriter().use { w -> uris.forEach { w.write(it.toString()); w.write("\n") } }
            tmp.renameTo(f)
        }
    }

    /** The saved list and when it was made, or null if this folder was never looked through. */
    @Synchronized
    private fun savedList(c: Context, folder: String): Pair<Long, List<Uri>>? {
        saved[folder]?.let { return it }
        val f = listFile(c, folder)
        if (!f.exists()) return null
        val uris = runCatching { f.readLines().filter { it.isNotBlank() }.map(Uri::parse) }.getOrNull() ?: return null
        val entry = f.lastModified() to uris
        saved[folder] = entry
        _counts.value = _counts.value + (folder to uris.size)
        return entry
    }

    /**
     * The photos to show: the saved list straight away if there is one ([refresh] is then true
     * when it's over an hour old and worth looking again), otherwise a fresh look.
     */
    suspend fun listQuick(c: Context, folderUri: String?): Pair<List<Uri>, Boolean> = withContext(Dispatchers.IO) {
        if (folderUri == null) return@withContext emptyList<Uri>() to false
        val s = savedList(c, folderUri)
        if (s == null || s.second.isEmpty()) list(c, folderUri) to false
        else s.second to (System.currentTimeMillis() - s.first > FRESH_MS)
    }

    /** Makes sure [counts] has this folder, looking through it only if it was never looked through. */
    suspend fun ensureCount(c: Context, folderUri: String?) = withContext(Dispatchers.IO) {
        if (folderUri != null && savedList(c, folderUri) == null) list(c, folderUri)
    }

    suspend fun files(c: Context, folderUri: String?): List<PhotoFile> = withContext(Dispatchers.IO) {
        if (folderUri == null) return@withContext emptyList()
        val tree = Uri.parse(folderUri)
        val out = ArrayList<PhotoFile>()
        runCatching {
            val pending = ArrayDeque<String>()
            pending.add(DocumentsContract.getTreeDocumentId(tree))
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            while (pending.isNotEmpty()) {
                val parent = pending.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                c.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0) ?: continue
                        val mime = cur.getString(1) ?: ""
                        val name = cur.getString(2) ?: ""
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                                if (!name.startsWith(".")) pending.add(id)
                            mime.startsWith("image/") && mime != "image/svg+xml" && !name.startsWith(".") ->
                                out.add(PhotoFile(
                                    DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime,
                                    if (cur.isNull(3)) 0 else cur.getLong(3),
                                    if (cur.isNull(4)) 0 else cur.getLong(4),
                                ))
                        }
                    }
                }
            }
        }
        out
    }

    /**
     * Loads a photo turned the right way up (the camera's rotation tag is applied), shrunk
     * to about the screen's size so big camera photos don't use too much memory.
     */
    suspend fun decode(c: Context, uri: Uri, maxSide: Int, turnCw: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.isMutableRequired = false
                if (turnCw != 0) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (turnCw == 0) bmp else turned(bmp, turnCw)
        }.getOrNull()
    }

    fun turned(bmp: Bitmap, degreesCw: Int): Bitmap {
        if (degreesCw % 360 == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(degreesCw.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

/**
 * Shuffled order with memory, so "back" goes to the photo shown before. Every photo is shown
 * once before any repeats; then it reshuffles (never repeating the last photo straight away).
 */
class Playlist(private val all: List<Uri>) {
    private val history = ArrayList<Uri>()
    private val bag = ArrayList<Uri>()
    var position = -1
        private set

    val isEmpty get() = all.isEmpty()
    val current: Uri? get() = history.getOrNull(position)
    val canGoBack get() = position > 0

    private fun draw(): Uri {
        if (bag.isEmpty()) {
            bag.addAll(all.shuffled())
            val last = history.lastOrNull()
            if (bag.size > 1 && bag.last() == last) bag.add(0, bag.removeAt(bag.size - 1))
        }
        return bag.removeAt(bag.size - 1)
    }

    /** The photo "forward" will go to (chosen now, so it can be loaded ahead of time). */
    fun peekNext(): Uri? {
        if (all.isEmpty()) return null
        if (position + 1 >= history.size) history.add(draw())
        return history[position + 1]
    }

    fun peekPrevious(): Uri? = history.getOrNull(position - 1)

    fun forward(): Uri? {
        peekNext() ?: return null
        position++
        // Keep the "back" memory to a sensible length.
        if (position > 300) {
            history.subList(0, 100).clear()
            position -= 100
        }
        return current
    }

    fun back(): Uri? {
        if (position > 0) position--
        return current
    }
}
