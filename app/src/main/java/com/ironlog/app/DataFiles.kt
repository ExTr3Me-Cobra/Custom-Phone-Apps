package com.ironlog.app

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows Loadout's data folder (workouts, routines, settings, pictures) in the phone's Files app, read-only.
 * The OpenAI key lives in a separate folder that isn't shown.
 */
class DataFiles : DocumentsProvider() {
    companion object {
        const val ROOT = "loadout"
        /** The top folder's document ID. It must not be empty: the Files app can't open a folder with an empty ID. */
        private const val TOP = "loadout"
        fun authority(c: Context) = "${c.packageName}.documents"

        /** Opens the folder in the Files app (or the system file browser if that can't open it directly). */
        fun open(c: Context) {
            val a = authority(c)
            val root = DocumentsContract.buildRootUri(a, ROOT)
            val direct = Intent(Intent.ACTION_VIEW).setDataAndType(root, "vnd.android.document/root")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val ok = runCatching { c.startActivity(direct) }.isSuccess
            if (!ok) runCatching {
                c.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(a, TOP))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { android.widget.Toast.makeText(c, "Couldn't open the Files app.", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    private val ROOT_COLS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON)
    private val DOC_COLS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun base() = context!!.filesDir
    /** IDs are "loadout" for the folder itself and "loadout/<path>" inside it. */
    private fun fileOf(id: String): File {
        if (id != TOP && !id.startsWith("$TOP/")) throw FileNotFoundException(id)
        val f = File(base(), id.removePrefix(TOP).trimStart('/')).canonicalFile
        if (!f.path.startsWith(base().canonicalPath)) throw FileNotFoundException(id)
        return f
    }
    private fun idOf(f: File) = f.canonicalFile.relativeTo(base().canonicalFile).path.let { if (it.isEmpty()) TOP else "$TOP/$it" }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLS).apply {
        newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_TITLE, "Loadout").add(Root.COLUMN_SUMMARY, "App data (read-only)")
            .add(Root.COLUMN_DOCUMENT_ID, TOP).add(Root.COLUMN_ICON, R.drawable.ic_launcher)
    }

    private fun MatrixCursor.addFile(f: File) {
        val dir = f.isDirectory
        val mime = if (dir) Document.MIME_TYPE_DIR
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: if (f.extension == "json") "application/json" else "application/octet-stream"
        newRow().add(Document.COLUMN_DOCUMENT_ID, idOf(f)).add(Document.COLUMN_DISPLAY_NAME, if (idOf(f) == TOP) "Loadout" else f.name)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, if (dir) null else f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLS).apply { addFile(fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOC_COLS).apply {
            fileOf(parentDocumentId).listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { addFile(it) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
}
