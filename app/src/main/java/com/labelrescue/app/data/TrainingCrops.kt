package com.labelrescue.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.fusion.Source
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.ml.CharCrop
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Optional (off by default): when the user confirms a number from a photo, save each digit's
 * 32×32 crop with its now-known digit, for retraining the digit classifier
 * (training/train_digits.py --extra). Stays on the phone; exported only when the user asks.
 */
class TrainingCrops(ctx: Context) {
    private val dir = File(ctx.filesDir, "training_crops").apply { mkdirs() }
    private val labels = File(dir, "labels.csv")

    fun save(tracking: String, out: RecoveryOutput, frames: List<GrayImage>): Int {
        var n = 0
        for ((pos, loc) in out.locations) {
            if (loc.source != Source.OCR || pos >= tracking.length) continue
            val frame = frames.getOrNull(loc.frame) ?: continue
            val crop = CharCrop.extract(frame, loc.box)
            val px = IntArray(crop.size) { i -> val v = 255 - (crop[i] * 255).roundToInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            val bmp = Bitmap.createBitmap(px, CharCrop.SIZE, CharCrop.SIZE, Bitmap.Config.ARGB_8888)
            val name = "${tracking}_${pos}_${tracking[pos]}.png"
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (!labels.exists()) labels.writeText("file,digit\n")
            labels.appendText("$name,${tracking[pos]}\n")
            n++
        }
        return n
    }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0

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
}
