package com.labelrescue.app.data

import android.content.Context
import java.io.File

/**
 * The training report: one small text entry per scan (see core TrainingReport), plus optionally
 * one photo per scan with names and addresses whited out. Kept until the user exports it, then
 * cleared (see TrainingExport). Separate from the full diagnostic log.
 */
class TrainingReportStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "training_report").apply { mkdirs() }

    fun put(id: String, entry: String) { File(dir, "$id.txt").writeText(entry) }

    fun putPhoto(id: String, jpeg: ByteArray) { File(dir, "$id.jpg").writeBytes(jpeg) }

    fun hasPhoto(id: String) = File(dir, "$id.jpg").exists()

    fun count(): Int = files("txt").size

    fun photos(): List<File> = files("jpg")

    /** The report text (all entries), without clearing. */
    fun text(): String = files("txt").joinToString("\n") { it.readText() }

    fun clear() { dir.listFiles().orEmpty().forEach { it.delete() } }

    private fun files(ext: String) = dir.listFiles { f -> f.name.endsWith(".$ext") }.orEmpty().sortedBy { it.name }
}
