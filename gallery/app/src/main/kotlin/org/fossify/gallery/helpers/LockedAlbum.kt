package org.fossify.gallery.helpers

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.media.MediaScannerConnection
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.widget.Toast
import org.fossify.gallery.models.Directory
import java.io.File

/**
 * Viper Vision's Locked album: a folder (Pictures/Locked) with a .nomedia marker, so other apps,
 * Android's media index and the all-photos grid don't see what's in it. It's shown as its own
 * album on the albums page and opens only after the phone's fingerprint, face or screen lock.
 * Photos moved in remember where they came from, for moving them back out.
 */
object LockedAlbum {

    private val dir: File
        get() = File(Environment.getExternalStorageDirectory(), "Pictures/Locked").apply {
            mkdirs()
            runCatching { File(this, ".nomedia").createNewFile() }
        }

    val path: String get() = dir.absolutePath

    fun isLocked(folder: String?): Boolean {
        if (folder.isNullOrEmpty()) return false
        val p = path
        return folder.trimEnd('/') == p || folder.startsWith("$p/")
    }

    private val mediaExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng", "avif",
        "mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v"
    )

    private fun media(): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in mediaExtensions }
            .orEmpty().sortedByDescending { it.lastModified() }

    /** The album's tile for the albums page (shown even when empty, so it can be found). */
    fun directory(name: String): Directory {
        val files = media()
        val newest = files.firstOrNull()
        val types = files.fold(0) { acc, f ->
            acc or if (f.extension.lowercase() in setOf("mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v")) TYPE_VIDEOS else TYPE_IMAGES
        }
        return Directory(
            null, path, newest?.absolutePath.orEmpty(), name, files.size,
            newest?.lastModified() ?: 0L, newest?.lastModified() ?: 0L, files.sumOf { it.length() },
            LOCATION_INTERNAL, if (types == 0) TYPE_IMAGES else types, ""
        )
    }

    /** Asks for the fingerprint, face or screen lock, then runs [onUnlocked]. */
    fun unlock(activity: Activity, onUnlocked: () -> Unit) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            Toast.makeText(activity, "Set a screen lock (Settings → Lock screen) to protect the Locked album", Toast.LENGTH_LONG).show()
            onUnlocked()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onUnlocked()
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onUnlocked()
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Locked album")
            .setSubtitle("Unlock to see it")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), activity.mainExecutor, callback)
    }

    /** Moves [paths] into the Locked album (off the main thread), then [done] with how many moved. */
    fun moveIn(context: Context, paths: List<String>, done: (Int) -> Unit) = move(context, paths, into = true, done)

    /** Moves [paths] out of the Locked album, back to where each came from. */
    fun moveOut(context: Context, paths: List<String>, done: (Int) -> Unit) = move(context, paths, into = false, done)

    private fun move(context: Context, paths: List<String>, into: Boolean, done: (Int) -> Unit) {
        val main = android.os.Handler(context.mainLooper)
        Thread {
            val origins = File(dir, ".origins")
            val known = runCatching { origins.readLines() }.getOrDefault(emptyList())
                .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap().toMutableMap()
            val changed = mutableListOf<String>()
            var moved = 0
            for (p in paths) {
                val file = File(p)
                if (!file.isFile) continue
                val targetDir = if (into) dir else File(known[file.name] ?: File(Environment.getExternalStorageDirectory(), "Pictures").absolutePath).apply { mkdirs() }
                val target = unique(File(targetDir, file.name))
                val ok = file.renameTo(target) || runCatching {
                    file.copyTo(target)
                    file.delete()
                }.getOrDefault(false)
                if (!ok) continue
                target.setLastModified(file.lastModified().takeIf { it > 0 } ?: target.lastModified())
                if (into) known[target.name] = file.parent.orEmpty() else known.remove(file.name)
                changed += p
                changed += target.absolutePath
                moved++
            }
            runCatching { origins.writeText(known.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
            // Android's media index forgets the old spots (and learns the new ones outside the album).
            if (changed.isNotEmpty()) MediaScannerConnection.scanFile(context, changed.toTypedArray(), null, null)
            main.post { done(moved) }
        }.start()
    }

    private fun unique(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val f = File(file.parentFile, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
