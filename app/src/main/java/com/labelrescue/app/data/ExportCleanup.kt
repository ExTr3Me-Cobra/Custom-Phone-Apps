package com.labelrescue.app.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Training exports are deleted 10 minutes after they're saved: long enough to send, and they
 * don't pile up in Downloads (they hold unredacted label photos). A background job deletes each
 * one on time; every app start also sweeps any the job missed (phone off, app force-stopped).
 */
object ExportCleanup {
    const val LIFETIME_MS = 10 * 60 * 1000L
    private const val PREFIX = "LabelRescue-training-"

    /** Deletes the app's exports older than [LIFETIME_MS]. */
    fun sweep(ctx: Context): Int {
        val cutoff = System.currentTimeMillis() - LIFETIME_MS
        var n = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ?"
            val args = arrayOf("$PREFIX%", (cutoff / 1000).toString())
            runCatching {
                ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(coll, c.getLong(0))
                        // Only the app's own files can be deleted without asking; others are skipped.
                        if (runCatching { ctx.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0) n++
                    }
                }
            }
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            dir.listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() < cutoff }?.forEach { if (it.delete()) n++ }
        }
        return n
    }

    /** Schedules a sweep just after a new export expires. */
    fun schedule(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<Worker>().setInitialDelay(LIFETIME_MS + 15_000, TimeUnit.MILLISECONDS).build()
        // Each export pushes the sweep back to 10 minutes after it; the sweep deletes all that are due.
        WorkManager.getInstance(ctx).enqueueUniqueWork("export-cleanup", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result { sweep(applicationContext); return Result.success() }
    }
}
