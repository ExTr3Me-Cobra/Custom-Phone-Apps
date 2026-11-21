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
 * - training_report.txt and report_photos/ (one entry and one redacted photo per scan)
 * - training_crops/ (digit crops + labels.csv) for the digit classifier
 * - barcode_examples/ (damaged barcodes + barcodes.csv with their true numbers)
 * - diagnostic_log.txt (the last scan, names and addresses hidden)
 * - confirmed_numbers.csv (numbers confirmed in the app: true numbers for the report entries)
 * Nothing goes to the gallery or the clipboard. The training report clears afterwards; crops and
 * barcode examples stay (they're the growing data set; re-exporting just includes them again).
 */
object TrainingExport {
    class Result(val name: String, val where: String, val entries: Int)

    fun write(
        ctx: Context,
        report: TrainingReportStore,
        crops: TrainingCrops,
        barcodes: BarcodeExamples,
        diagnosticLog: String?,
        confirmed: List<ConfirmedNumber>,
        appVersion: String,
    ): Result {
        val name = "LabelRescue-training-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        var entries = 0
        fun zipTo(os: OutputStream) = ZipOutputStream(os).use { zip ->
            fun put(path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry(); entries++ }
            fun putFile(path: String, f: File) { zip.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); entries++ }
            val reportText = report.text()
            val photos = report.photos()
            val cropFiles = crops.dir.listFiles().orEmpty().sortedBy { it.name }
            val barcodeFiles = barcodes.files().sortedBy { it.name }
            put(
                "README.txt",
                ("Label Rescue $appVersion training export, ${Date()}\n" +
                    "training report: ${report.count()} scans, ${photos.size} photos\n" +
                    "digit crops: ${crops.count()} · barcode examples: ${barcodes.count()} · confirmed numbers: ${confirmed.size}\n").toByteArray(),
            )
            if (reportText.isNotBlank()) put("training_report.txt", reportText.toByteArray())
            for (f in photos) putFile("report_photos/${f.name}", f)
            for (f in cropFiles) putFile("training_crops/${f.name}", f)
            for (f in barcodeFiles) putFile("barcode_examples/${f.name}", f)
            diagnosticLog?.takeIf { it.isNotBlank() }?.let { put("diagnostic_log.txt", it.toByteArray()) }
            if (confirmed.isNotEmpty()) {
                put("confirmed_numbers.csv", ("tracking,confirmed_at,source\n" + confirmed.joinToString("") { "${it.tracking},${it.confirmedAt},${it.source}\n" }).toByteArray())
            }
        }
        val where: String
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file in Downloads")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { zipTo(it) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null); throw e
            }
            where = "Downloads"
        } else {
            // Android 9 and older: the app's own folder (no storage permission needed).
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            File(dir, name).outputStream().use { zipTo(it) }
            where = dir.absolutePath
        }
        report.clear()
        return Result(name, where, entries)
    }
}
