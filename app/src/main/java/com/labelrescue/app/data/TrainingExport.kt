package com.labelrescue.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything needed to improve the models, in one ZIP in the phone's Downloads folder:
 * - training_report.txt and report_photos/ (one entry and one photo per scan, not redacted)
 * - training_crops/ (digit crops + labels.csv) for the digit classifier
 * - barcode_examples/ (damaged barcodes + barcodes.csv with their true numbers)
 * - scan_crops/ (full-resolution barcodes, number lines and 2D codes of every scan, read or not)
 * - diagnostic_log.txt (the last scan, names and addresses hidden)
 * - confirmed_numbers.csv (numbers confirmed in the app: true numbers for the report entries)
 * Nothing goes to the gallery or the clipboard. The training report clears afterwards; crops and
 * barcode examples stay (they're the growing data set; re-exporting just includes them again).
 */
object TrainingExport {
    class Result(val name: String, val where: String, val entries: Int, val parts: Int = 1)

    /** Each file stays under the 30 MB upload limit, with room to spare. */
    const val PART_LIMIT = 27L * 1024 * 1024

    /** One file to put in the export, and how much it matters (lower goes first). */
    private class Entry(val path: String, val priority: Int, val size: Long, val bytes: ByteArray? = null, val file: File? = null)

    fun write(
        ctx: Context,
        report: TrainingReportStore,
        crops: TrainingCrops,
        barcodes: BarcodeExamples,
        diagnosticLog: String?,
        confirmed: List<ConfirmedNumber>,
        appVersion: String,
        /** Learned barcode positions (numbers only). */
        layout: File? = null,
        scanCrops: ScanCrops? = null,
    ): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // Digit crops and barcode examples are a growing set: only files not exported before go
        // (their label lists go in full, they're small). Clearing the crops starts over.
        val sentList = File(ctx.filesDir, "exported_files.txt")
        val sent = if (sentList.exists()) sentList.readLines().toHashSet() else HashSet()
        val entries = ArrayList<Entry>()
        fun text(path: String, t: String) { val b = t.toByteArray(); entries += Entry(path, 0, b.size.toLong(), bytes = b) }
        fun file(path: String, f: File, priority: Int) { if (f.exists()) entries += Entry(path, priority, f.length(), file = f) }
        val photos = report.photos()
        val reportText = report.text()
        val newCrops = crops.dir.listFiles().orEmpty().filter { it.extension == "png" && "training_crops/${it.name}" !in sent }
        val newBarcodes = barcodes.files().filter { it.extension == "png" && "barcode_examples/${it.name}" !in sent }
        text(
            "README.txt",
            "Label Rescue $appVersion training export, ${Date()}\n" +
                "training report: ${report.count()} scans, ${photos.size} photos\n" +
                "digit crops: ${crops.count()} (${newCrops.size} new in this export) · barcode examples: ${barcodes.count()} (${newBarcodes.size} new) · confirmed numbers: ${confirmed.size}\n" +
                "Only files not exported before are included; label lists are complete.\n",
        )
        if (reportText.isNotBlank()) text("training_report.txt", reportText)
        diagnosticLog?.takeIf { it.isNotBlank() }?.let { text("diagnostic_log.txt", it) }
        if (confirmed.isNotEmpty()) text("confirmed_numbers.csv", "tracking,confirmed_at,source\n" + confirmed.joinToString("") { "${it.tracking},${it.confirmedAt},${it.source}\n" })
        layout?.takeIf { it.exists() && it.length() > 0 }?.let { file("barcode_layout.txt", it, 0) }
        crops.dir.listFiles().orEmpty().filter { it.extension == "csv" }.forEach { file("training_crops/${it.name}", it, 0) }
        barcodes.files().filter { it.extension == "csv" }.forEach { file("barcode_examples/${it.name}", it, 0) }
        scanCrops?.files()?.filter { it.extension == "csv" }?.forEach { file("scan_crops/${it.name}", it, 0) }
        // By value: the codes of every scan (small, read or not), then photos of damaged labels
        // (full resolution), then new digit crops and barcode examples, then the rest.
        scanCrops?.files()?.filter { it.extension == "png" }?.forEach { file("scan_crops/${it.name}", it, 1) }
        for (f in photos) file("report_photos/${f.name}", f, if (f.length() > 600_000) 2 else 4)
        newBarcodes.forEach { file("barcode_examples/${it.name}", it, 3) }
        newCrops.forEach { file("training_crops/${it.name}", it, 3) }

        // Into parts under the limit, most valuable first; the small text files go in part 1.
        val ordered = entries.sortedWith(compareBy({ it.priority }, { it.path }))
        val parts = ArrayList<MutableList<Entry>>()
        var cur = ArrayList<Entry>(); var size = 0L
        for (e in ordered) {
            if (cur.isNotEmpty() && size + e.size + 4096 > PART_LIMIT) { parts += cur; cur = ArrayList(); size = 0 }
            cur += e; size += e.size + 200
        }
        if (cur.isNotEmpty()) parts += cur
        var where = ""; var count = 0
        val names = ArrayList<String>()
        for ((i, part) in parts.withIndex()) {
            val name = "LabelRescue-training-$stamp" + (if (parts.size > 1) "-part${i + 1}of${parts.size}" else "") + ".zip"
            where = save(ctx, name) { os ->
                ZipOutputStream(os).use { zip ->
                    for (e in part) {
                        zip.putNextEntry(ZipEntry(e.path))
                        if (e.bytes != null) zip.write(e.bytes) else e.file!!.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry(); count++
                    }
                }
            }
            names += name
        }
        // Remember what went out, so the next export only sends what's new.
        sentList.appendText(ordered.filter { it.priority == 3 }.joinToString("") { it.path + "\n" })
        report.clear()
        return Result(names.first(), where, count, parts.size)
    }

    /** Forgets what was exported (after the crops are deleted, so new ones aren't skipped). */
    fun forgetSent(ctx: Context) { File(ctx.filesDir, "exported_files.txt").delete() }

    private fun save(ctx: Context, name: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file in Downloads")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { write(it) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null); throw e
            }
            return "Downloads"
        }
        // Android 9 and older: the app's own folder (no storage permission needed).
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        File(dir, name).outputStream().use { write(it) }
        return dir.absolutePath
    }
}
