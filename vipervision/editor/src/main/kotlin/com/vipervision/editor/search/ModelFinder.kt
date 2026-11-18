package com.vipervision.editor.search

import android.content.Context
import android.os.Build
import android.os.Environment
import com.t8rin.neural_tools.ModelFileSpec
import com.t8rin.neural_tools.inpaint.LaMaProcessor
import com.vipervision.editor.ViperLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/**
 * Settings → AI models → Find models on this phone: looks through the whole phone (its own
 * storage, shared storage and any SD card) for copies of the AI model files. A model that's
 * missing from where the app keeps it is copied back from a copy found elsewhere. Copies that are
 * byte-for-byte the same as one the app already has are listed as duplicates, to delete if wanted.
 *
 * A file only counts as a model when its size is exactly right and either its SHA-256 matches, or
 * (for the few models without a known SHA-256) its name matches too.
 */
object ModelFinder {

    class Duplicate(val file: File, val model: String) {
        val size: Long get() = file.length()
    }

    class Result(
        val looked: Int,
        val restored: List<String>,
        val duplicates: List<Duplicate>,
        /** Models still missing, with why (as far as could be told). */
        val missing: List<String>,
        val skippedSharedStorage: Boolean
    )

    /** Shared storage can be searched (all-files access, or the older storage permission). */
    fun canSearchSharedStorage(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    suspend fun search(context: Context, onProgress: (String) -> Unit): Result {
        val items = ModelPack.items
        val specs = items.flatMap { item -> runCatching { item.files(context) }.getOrDefault(emptyList()).map { item to it } }
        val bySize = specs.groupBy { it.second.size }
        val targets = specs.map { it.second.file.absoluteFile }.toSet()
        val shared = canSearchSharedStorage(context)

        // Every file on the phone whose size is exactly a model file's size.
        val found = mutableListOf<File>()
        var looked = 0
        for (root in roots(context, shared)) {
            onProgress("Looking in ${root.path}…")
            root.walkTopDown()
                .onEnter { dir -> !skip(dir, context) }
                .onFail { _, _ -> }
                .forEach { f ->
                    if (!f.isFile) return@forEach
                    looked++
                    if (looked % 2000 == 0) onProgress("Looked at $looked files…")
                    if (f.length() in bySize && f.absoluteFile !in targets) found += f.absoluteFile
                }
            currentCoroutineContext().ensureActive()
        }
        val candidates = found.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
        ViperLog.i("MODELS", "Find: looked at $looked files, ${candidates.size} the size of a model file")

        val hashes = mutableMapOf<String, String?>()
        fun hash(f: File): String? = hashes.getOrPut(f.absolutePath) {
            onProgress("Checking ${f.name}…")
            runCatching { sha256(f) }.getOrNull()
        }

        val restored = mutableListOf<String>()
        val duplicates = mutableListOf<Duplicate>()
        val missing = mutableListOf<String>()
        val claimed = mutableSetOf<String>()
        for ((item, spec) in specs) {
            currentCoroutineContext().ensureActive()
            val same = candidates.filter { it.length() == spec.size }
            fun isThisModel(f: File) = if (spec.sha256 != null) hash(f) == spec.sha256 else f.name == spec.name
            var ok = isValid(spec, ::hash)
            if (!ok) {
                val why = when {
                    !spec.file.exists() -> "no file"
                    spec.file.length() != spec.size -> "wrong size (${spec.file.length()} of ${spec.size} bytes)"
                    else -> "contents don't match"
                }
                ViperLog.i("MODELS", "Find: ${spec.name} (${item.name}) isn't in place: $why")
                val copy = same.firstOrNull { it.absolutePath !in claimed && isThisModel(it) }
                if (copy != null) {
                    onProgress("Copying back ${spec.name}…")
                    ok = runCatching { restore(copy, spec) }
                        .onFailure { ViperLog.error("MODELS", "Find: couldn't copy back ${spec.name} from ${copy.path}", it) }
                        .getOrDefault(false)
                    if (ok) {
                        hashes.remove(spec.file.absolutePath)
                        restored += "${item.name} (from ${copy.parent})"
                        ViperLog.i("MODELS", "Find: copied ${spec.name} back from ${copy.path}")
                    }
                }
                if (!ok) missing += "${item.name}: ${spec.name}, $why"
            }
            if (!ok) continue
            // Copies identical to the one in place (checked byte for byte through their SHA-256).
            val kept = hash(spec.file) ?: continue
            for (f in same) {
                if (f.absolutePath in claimed) continue
                if (hash(f) == kept) {
                    claimed += f.absolutePath
                    duplicates += Duplicate(f, item.name)
                }
            }
        }
        if (restored.isNotEmpty()) {
            LaMaProcessor.recheck()
            runCatching { ModelPack.refreshState(context) }
        }
        ViperLog.i("MODELS", "Find: ${restored.size} copied back, ${duplicates.size} duplicates, ${missing.size} still missing")
        return Result(looked, restored, duplicates, missing.distinct(), skippedSharedStorage = !shared)
    }

    /** Deletes [duplicates] (only ones still identical in size); returns the bytes freed. */
    fun delete(duplicates: List<Duplicate>): Long {
        var freed = 0L
        for (d in duplicates) {
            val size = d.file.length()
            if (d.file.delete()) {
                freed += size
                ViperLog.i("MODELS", "Find: deleted duplicate ${d.file.path}")
            } else {
                ViperLog.i("MODELS", "Find: couldn't delete ${d.file.path}")
            }
        }
        return freed
    }

    private fun isValid(spec: ModelFileSpec, hash: (File) -> String?): Boolean {
        val f = spec.file
        if (!f.isFile || f.length() != spec.size) return false
        return spec.sha256 == null || hash(f) == spec.sha256
    }

    /** Copies [from] into place through a temporary file, checking it on the way. */
    private fun restore(from: File, spec: ModelFileSpec): Boolean {
        val dir = spec.file.parentFile ?: return false
        check(dir.mkdirs() || dir.isDirectory) { "can't make ${dir.path}" }
        check(dir.usableSpace > spec.size + 50_000_000) { "not enough free space" }
        val temporary = File(dir, spec.name + ".found")
        try {
            from.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(temporary.length() == spec.size) { "the copy came out the wrong size" }
            if (spec.sha256 != null) check(sha256(temporary) == spec.sha256) { "the copy doesn't match" }
            spec.file.delete()
            check(temporary.renameTo(spec.file)) { "couldn't put it in place" }
            return true
        } finally {
            temporary.delete()
        }
    }

    /** The app's own storage, then (when allowed) shared storage and SD cards. */
    @Suppress("DEPRECATION")
    private fun roots(context: Context, shared: Boolean): List<File> {
        val list = mutableListOf<File>()
        context.filesDir.parentFile?.let(list::add)
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            if (shared) {
                // …/Android/data/<app>/files → the root of that storage.
                val path = dir.absolutePath
                val cut = path.indexOf("/Android/data/")
                list += if (cut > 0) File(path.substring(0, cut)) else dir
            } else {
                dir.parentFile?.let(list::add)
            }
        }
        if (shared) list += Environment.getExternalStorageDirectory()
        // Without nesting the same place twice.
        val canonical = list.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinct()
        return canonical.filter { root -> canonical.none { other -> other != root && root.path.startsWith(other.path + "/") } }
    }

    /** Other apps' private folders can't be read anyway; the app's own is searched on its own. */
    private fun skip(dir: File, context: Context): Boolean {
        val path = dir.absolutePath
        val own = context.packageName
        for (part in listOf("/Android/data", "/Android/obb")) {
            val i = path.indexOf(part)
            if (i < 0) continue
            val rest = path.substring(i + part.length).trimStart('/')
            if (rest.isEmpty()) return false
            return rest.substringBefore('/') != own
        }
        return false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
