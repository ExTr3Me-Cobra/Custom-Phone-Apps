package com.labelrescue.app.pipeline

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Waits for an ML Kit task without abandoning it. The pipeline stops its frame workers by
 * interrupting them (early stop, cancel); a plain Tasks.await then returns at once while ML Kit's
 * native code keeps working on the image. Abandoned work is what crashed the app (native SIGSEGV
 * in the OCR and barcode libraries, and "excessive CPU" kills). So an interrupt is noted but the
 * wait goes on until the task is done (or [timeoutMs]), and the interrupt is restored after.
 */
fun <T> Task<T>.awaitFully(timeoutMs: Long): T? {
    val end = System.currentTimeMillis() + timeoutMs
    var interrupted = false
    try {
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            try {
                return Tasks.await(this, left, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                interrupted = true
            } catch (e: Exception) {
                return null
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
