package com.labelrescue.app.data

import android.content.Context
import android.graphics.Bitmap
import com.labelrescue.app.pipeline.Images
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Damaged 1D barcodes with their true number: when the number is known (a 2D code read it, or the
 * user confirmed or pasted it) but the barcode itself didn't decode, a crop of the barcode is kept
 * with the number. From the number, the exact bar pattern it should have is known, so every
 * damaged symbol becomes a labeled example of real damage, to test and later train the damaged-
 * barcode decoder. Local only; exported with the training crops.
 */
class BarcodeExamples(ctx: Context) {
    val dir = File(ctx.filesDir, "barcode_examples").apply { mkdirs() }
    private val labels = File(dir, "barcodes.csv")
    private val header = "file,tracking,truth_from,frame_width,frame_height"

    /** Saves the barcode of [out] with [tracking] if it wasn't read in full. False if there was nothing to save. */
    fun save(tracking: String, out: RecoveryOutput, frames: List<GrayImage>, truthFrom: String): Boolean {
        if (out.barcodeRead) return false
        val loc = out.barcodeArea ?: return false
        val frame = frames.getOrNull(loc.frame) ?: return false
        val b = loc.box
        val l = min(b.left, b.right); val r = max(b.left, b.right); val t = min(b.top, b.bottom); val bt = max(b.top, b.bottom)
        // A margin around the bars: the quiet zones, and the printed number under them.
        val padX = (r - l) / 20 + 8; val padY = (bt - t) / 2 + 8
        val rect = IntRect(l - padX, t - padY, r + padX, bt + padY).intersect(IntRect(0, 0, frame.width, frame.height))
        if (rect.isEmpty || rect.width < 60) return false
        val crop = frame.crop(rect).let { if (it.width > 2000) it.scaledBy(2000.0 / it.width) else it }
        val name = "${tracking}_${truthFrom}.png"
        val bmp = Images.toBitmap(crop)
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val rows = readRows().filter { it.getOrNull(1) != tracking }
        writeRows(rows + listOf(listOf(name, tracking, truthFrom, frame.width.toString(), frame.height.toString())))
        // An earlier example of the same label with another source is replaced.
        dir.listFiles { f -> f.name.startsWith(tracking + "_") && f.name != name }?.forEach { it.delete() }
        return true
    }

    /** Forgets the example of [tracking]. */
    fun remove(tracking: String) {
        dir.listFiles { f -> f.name.startsWith(tracking + "_") }?.forEach { it.delete() }
        writeRows(readRows().filter { it.getOrNull(1) != tracking })
    }

    fun count(): Int = readRows().size

    fun files(): List<File> = dir.listFiles().orEmpty().toList()

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun readRows(): List<List<String>> =
        if (!labels.exists()) emptyList() else labels.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }

    private fun writeRows(rows: List<List<String>>) {
        labels.writeText(header + "\n" + rows.joinToString("") { it.joinToString(",") + "\n" })
    }
}
