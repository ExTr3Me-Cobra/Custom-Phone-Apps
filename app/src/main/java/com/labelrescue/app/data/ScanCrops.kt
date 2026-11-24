package com.labelrescue.app.data

import android.content.Context
import android.graphics.Bitmap
import com.labelrescue.app.pipeline.Images
import com.labelrescue.core.barcode.ScanCropFinder
import com.labelrescue.core.image.GrayImage
import java.io.File
import java.io.FileOutputStream

/**
 * Full-resolution crops of every part of a scanned label that carries the tracking number, read
 * or not: the 1D barcodes, the printed number line under each, and the DataMatrix codes (see
 * [ScanCropFinder]). Developer Mode only, one set per scan, exported for training. Crops only: no
 * names or addresses.
 */
class ScanCrops(ctx: Context) {
    val dir = File(ctx.filesDir, "scan_crops").apply { mkdirs() }
    private val labels = File(dir, "scan_crops.csv")
    private val header = "file,scan,kind,truth,frame_width,frame_height,left,top,right,bottom"

    fun has(scan: String) = dir.listFiles { f -> f.name.startsWith("${scan}_") }?.isNotEmpty() == true

    /** Saves the crops of [frame] for [scan]; [truth] is the true number if known. */
    fun save(scan: String, frame: GrayImage, truth: String?): Int {
        val found = ScanCropFinder.find(frame)
        val rows = ArrayList<String>()
        for (c in found) {
            val name = "${scan}_${c.kind}.png"
            val bmp = Images.toBitmap(frame.crop(c.rect))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            rows += listOf(name, scan, c.kind, truth ?: "", frame.width, frame.height, c.rect.left, c.rect.top, c.rect.right, c.rect.bottom).joinToString(",")
        }
        if (rows.isNotEmpty()) {
            val old = if (labels.exists()) labels.readLines().drop(1).filter { it.isNotBlank() && it.split(',').getOrNull(1) != scan } else emptyList()
            labels.writeText(header + "\n" + (old + rows).joinToString("") { it + "\n" })
        }
        return rows.size
    }

    /** Sets the true number of [scan]'s crops (confirmed or pasted after they were saved). */
    fun setTruth(scan: String, truth: String) {
        if (!labels.exists()) return
        val lines = labels.readLines().drop(1).filter { it.isNotBlank() }.map { l ->
            val p = l.split(',').toMutableList()
            if (p.getOrNull(1) == scan && p.size > 3) { p[3] = truth; p.joinToString(",") } else l
        }
        labels.writeText(header + "\n" + lines.joinToString("") { it + "\n" })
    }

    fun count(): Int = dir.listFiles { f -> f.extension == "png" }?.size ?: 0
    fun files(): List<File> = dir.listFiles().orEmpty().toList()
    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
