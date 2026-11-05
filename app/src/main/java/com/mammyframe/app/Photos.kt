package com.mammyframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Photos {
    /** Every picture in the chosen folder and its subfolders. */
    suspend fun list(c: Context, folderUri: String?): List<Uri> = withContext(Dispatchers.IO) {
        if (folderUri == null) return@withContext emptyList()
        val tree = Uri.parse(folderUri)
        val out = ArrayList<Uri>()
        runCatching {
            val pending = ArrayDeque<String>()
            pending.add(DocumentsContract.getTreeDocumentId(tree))
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            while (pending.isNotEmpty() && out.size < 50_000) {
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
                                out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
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
    suspend fun decode(c: Context, uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.isMutableRequired = false
            }
        }.getOrNull()
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
