package com.labelrescue.app.data

import android.content.Context
import java.io.File

/**
 * The training report: one small text entry per scan (see core TrainingReport), kept until the
 * user exports it, then cleared. Separate from the full diagnostic log.
 */
class TrainingReportStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "training_report").apply { mkdirs() }

    fun put(id: String, entry: String) { File(dir, "$id.txt").writeText(entry) }

    fun count(): Int = files().size

    fun text(): String = files().joinToString("\n") { it.readText() }

    fun clear() { files().forEach { it.delete() } }

    private fun files() = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
}
