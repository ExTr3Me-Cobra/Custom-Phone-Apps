package com.labelrescue.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.fusion.Source
import com.labelrescue.core.fusion.TrainingCropFilter
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.ml.CharCrop
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Optional (off by default): when the user confirms a number from a photo, save digit crops
 * with their now-known digit, for retraining the digit classifier
 * (training/train_digits.py --extra). Stays on the phone; exported only when the user asks.
 *
 * Safety:
 * - only positions where OCR located the character on the label;
 * - only crops where at least one reader agreed with the confirmed digit
 *   ([TrainingCropFilter]), which drops misaligned crops and most wrong confirmations;
 * - crops of a number USPS confirmed are marked trusted (training weighs them more);
 * - the user can review and delete crops before exporting.
 */
class TrainingCrops(ctx: Context) {
    private val dir = File(ctx.filesDir, "training_crops").apply { mkdirs() }
    private val labels = File(dir, "labels.csv")

    data class Item(val file: File, val digit: Char, val trusted: Boolean, val agreedBy: String, val tracking: String)

    data class SaveResult(val saved: Int, val skipped: Int)

    fun save(tracking: String, out: RecoveryOutput, frames: List<GrayImage>, trusted: Boolean): SaveResult {
        migrate()
        var saved = 0; var skipped = 0
        for ((pos, loc) in out.locations) {
            if (loc.source != Source.OCR || pos >= tracking.length) continue
            val digit = tracking[pos]
            val agree = TrainingCropFilter.agreeing(out.perSource, pos, digit)
            if (agree.isEmpty()) { skipped++; continue }
            val frame = frames.getOrNull(loc.frame) ?: continue
            val crop = CharCrop.extract(frame, loc.box)
            val px = IntArray(crop.size) { i -> val v = 255 - (crop[i] * 255).roundToInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            val bmp = Bitmap.createBitmap(px, CharCrop.SIZE, CharCrop.SIZE, Bitmap.Config.ARGB_8888)
            val name = "${tracking}_${pos}_$digit.png"
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            // Re-confirming the same label replaces its rows.
            val rows = readRows().filter { it[0] != name }
            writeRows(rows + listOf(listOf(name, digit.toString(), if (trusted) "1" else "0", agree.joinToString("+") { it.name }, tracking)))
            saved++
        }
        return SaveResult(saved, skipped)
    }

    /** Marks every crop of [tracking] as trusted (e.g. USPS confirmed it after it was saved). */
    fun markTrusted(tracking: String) {
        val rows = readRows()
        if (rows.none { it.getOrNull(4) == tracking }) return
        writeRows(rows.map { if (it.getOrNull(4) == tracking) it.toMutableList().also { r -> r[2] = "1" } else it })
    }

    fun items(): List<Item> = readRows().mapNotNull { r ->
        val f = File(dir, r[0])
        if (!f.exists()) null else Item(f, r[1].firstOrNull() ?: '?', r.getOrNull(2) == "1", r.getOrNull(3).orEmpty(), r.getOrNull(4).orEmpty())
    }

    fun thumbnail(item: Item): Bitmap? = BitmapFactory.decodeFile(item.file.path)

    fun delete(item: Item) {
        item.file.delete()
        writeRows(readRows().filter { it[0] != item.file.name })
    }

    fun deleteUntrusted() { for (i in items()) if (!i.trusted) delete(i) }

    fun count(): Int = items().size

    fun exportZip(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
            ZipOutputStream(os).use { zip ->
                for (f in dir.listFiles().orEmpty()) {
                    zip.putNextEntry(ZipEntry("training_crops/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private val header = "file,digit,trusted,agreed_by,tracking"

    private fun readRows(): List<List<String>> =
        if (!labels.exists()) emptyList() else labels.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }

    private fun writeRows(rows: List<List<String>>) {
        labels.writeText(header + "\n" + rows.joinToString("") { it.joinToString(",") + "\n" })
    }

    /** Older saves had only "file,digit": keep them, as untrusted with unknown agreement. */
    private fun migrate() {
        if (!labels.exists()) return
        val first = labels.readLines().firstOrNull() ?: return
        if (first == header) return
        val rows = labels.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(','); listOf(p[0], p.getOrElse(1) { "?" }, "0", "unknown", p[0].substringBefore('_'))
        }
        writeRows(rows)
    }
}
