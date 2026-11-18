/*
 * ImageToolbox is an image editor for android
 * Copyright (c) 2026 T8RIN (Malik Mukhametzyanov)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.t8rin.imagetoolbox.core.data.remote

import android.system.Os
import com.t8rin.imagetoolbox.core.data.utils.getWithProgress
import com.t8rin.imagetoolbox.core.domain.coroutines.DispatchersHolder
import com.t8rin.imagetoolbox.core.domain.remote.DownloadManager
import com.t8rin.imagetoolbox.core.domain.remote.DownloadProgress
import com.t8rin.imagetoolbox.core.domain.resource.ResourceManager
import com.t8rin.imagetoolbox.core.domain.saving.FailureNotifier
import com.t8rin.imagetoolbox.core.domain.saving.KeepAliveService
import com.t8rin.imagetoolbox.core.domain.saving.track
import com.t8rin.imagetoolbox.core.domain.saving.updateProgress
import com.t8rin.imagetoolbox.core.domain.utils.throttleLatest
import com.t8rin.imagetoolbox.core.resources.R
import com.t8rin.imagetoolbox.core.utils.appContext
import com.t8rin.imagetoolbox.core.utils.decodeEscaped
import com.t8rin.imagetoolbox.core.utils.isNetworkAvailable
import com.t8rin.imagetoolbox.core.utils.makeLog
import io.ktor.client.HttpClient
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.UnknownHostException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.math.roundToInt

internal class AndroidDownloadManager @Inject constructor(
    private val keepAliveService: KeepAliveService,
    private val client: HttpClient,
    private val failureNotifier: FailureNotifier,
    resourceManager: ResourceManager,
    dispatchersHolder: DispatchersHolder,
) : DownloadManager,
    ResourceManager by resourceManager,
    DispatchersHolder by dispatchersHolder {

    override suspend fun download(
        url: String,
        destinationPath: String,
        onStart: suspend () -> Unit,
        onProgress: suspend (DownloadProgress) -> Unit,
        onFinish: suspend (Throwable?) -> Unit
    ): Unit = withContext(defaultDispatcher) {
        // Viper Vision: every model download is noted in the diagnostic log.
        val name = url.substringBefore('?').substringAfterLast('/')
        val started = System.currentTimeMillis()
        DownloadLog.log("Downloading $name → $destinationPath")
        val finish: suspend (Throwable?) -> Unit = { error ->
            val seconds = (System.currentTimeMillis() - started) / 1000
            DownloadLog.log(
                if (error == null) "Got $name (${File(destinationPath).let { if (it.isDirectory) it.walkTopDown().filter(File::isFile).sumOf(File::length) else it.length() } / 1_000_000} MB) in ${seconds} s"
                else "Couldn't get $name after ${seconds} s: ${error.message ?: error.javaClass.simpleName}"
            )
            onFinish(error)
        }
        var temporaryFile: File? = null

        // Viper Vision: the all-in-one APK carries every AI model, so it's copied from there.
        if (copyBundled(url, destinationPath, onStart, onProgress, finish)) return@withContext

        if (!appContext.isNetworkAvailable()) {
            failureNotifier.sendNoConnection()
            finish(UnknownHostException())
            return@withContext
        }

        keepAliveService.track(
            initial = {
                updateOrStart(
                    title = getString(R.string.downloading)
                )
            },
            onCancel = {
                temporaryFile?.delete()
            },
            onFailure = finish
        ) {
            channelFlow {
                onStart()
                url.makeLog("Start Download")

                val destination = File(destinationPath)
                val tmp = destination.createTemporarySibling().also {
                    temporaryFile = it
                }

                try {
                    // Viper Vision: AI models come from this app's own permanent copy first (see
                    // mirrorUrl); the original site is only used if that copy isn't there.
                    val sources = listOfNotNull(mirrorUrl(url), url)
                    var lastError: Throwable? = null
                    for (source in sources) {
                        lastError = runCatching {
                    client.getWithProgress(
                        url = source,
                        onProgress = { bytesSentTotal, contentLength ->
                            val progress = contentLength
                                ?.takeIf { it > 0L }
                                ?.let { bytesSentTotal.toFloat() / it.toFloat() }
                                ?.coerceIn(0f, 1f)
                                ?: 0f

                            onProgress(
                                DownloadProgress(
                                    currentPercent = progress,
                                    currentTotalSize = contentLength ?: -1
                                ).also(::trySend)
                            )
                        },
                        onOpen = { response ->
                            tmp.outputStream().use { output ->
                                response.copyTo(output)
                            }
                        }
                    )
                        }.exceptionOrNull()
                        if (lastError == null) break
                        if (lastError is kotlinx.coroutines.CancellationException) throw lastError
                        "$source failed (${lastError.message}), trying the next source".also { DownloadLog.log(it) }.makeLog("Download")
                    }
                    lastError?.let { throw it }

                    tmp.replaceAtomically(destination)
                    finish(null)
                } finally {
                    tmp.delete()
                    temporaryFile = null
                }
                close()
            }.throttleLatest(50).collect {
                updateProgress(
                    title = getString(R.string.downloading),
                    done = (it.currentPercent * 100).roundToInt(),
                    total = 100
                )
            }
        }
    }

    override suspend fun downloadZip(
        url: String,
        destinationPath: String,
        onStart: suspend () -> Unit,
        onProgress: (DownloadProgress) -> Unit,
        onFailure: (Throwable) -> Unit,
        downloadOnlyNewData: Boolean
    ): Unit = withContext(defaultDispatcher) {
        // Viper Vision: every model download is noted in the diagnostic log.
        val name = url.substringBefore('?').substringAfterLast('/')
        val started = System.currentTimeMillis()
        DownloadLog.log("Downloading (zip) $name → $destinationPath")
        val failed: (Throwable) -> Unit = { error ->
            DownloadLog.log("Couldn't get $name after ${(System.currentTimeMillis() - started) / 1000} s: ${error.message ?: error.javaClass.simpleName}")
            onFailure(error)
        }
        if (!appContext.isNetworkAvailable()) {
            failureNotifier.sendNoConnection()
            failed(UnknownHostException())
            return@withContext
        }

        keepAliveService.track(
            initial = {
                updateOrStart(
                    title = getString(R.string.downloading)
                )
            },
            onFailure = failed
        ) {
            channelFlow {
                onStart()

                client.getWithProgress(
                    url = url,
                    onProgress = { bytesSentTotal, contentLength ->
                        val progress = contentLength
                            ?.takeIf { it > 0L }
                            ?.let { bytesSentTotal.toFloat() / it.toFloat() }
                            ?.coerceIn(0f, 1f)
                            ?: 0f

                        onProgress(
                            DownloadProgress(
                                currentPercent = progress,
                                currentTotalSize = contentLength ?: -1
                            ).also(::trySend)
                        )
                    },
                    onOpen = { response ->
                        val destination = File(destinationPath).apply {
                            check(mkdirs() || isDirectory)
                        }.canonicalFile

                        ZipInputStream(response.toInputStream()).use { zipIn ->
                            while (true) {
                                val entry = zipIn.nextEntry ?: break

                                try {
                                    val filename = entry.name?.decodeEscaped()
                                    if (filename.isNullOrBlank() || filename.startsWith("__")) {
                                        continue
                                    }

                                    val output = destination.resolveZipEntry(filename)
                                    if (entry.isDirectory) {
                                        check(output.mkdirs() || output.isDirectory)
                                        continue
                                    }
                                    if (downloadOnlyNewData && output.length() > 0L) continue

                                    val tmp = output.createTemporarySibling()
                                    try {
                                        FileOutputStream(tmp).use { stream ->
                                            zipIn.copyTo(stream)
                                        }
                                        tmp.replaceAtomically(output)
                                    } finally {
                                        tmp.delete()
                                    }
                                } finally {
                                    zipIn.closeEntry()
                                }
                            }
                        }
                    }
                )
                DownloadLog.log("Got $name in ${(System.currentTimeMillis() - started) / 1000} s")

                close()
            }.throttleLatest(50).collect {
                updateProgress(
                    title = getString(R.string.downloading),
                    done = (it.currentPercent * 100).roundToInt(),
                    total = 100
                )
            }
        }
    }

}

private fun File.createTemporarySibling(): File {
    val directory = requireNotNull(parentFile)
    check(directory.mkdirs() || directory.isDirectory)

    return File.createTempFile(".download-$name.", ".tmp", directory)
}

private fun File.replaceAtomically(destination: File) {
    Os.rename(absolutePath, destination.absolutePath)
}

private fun File.resolveZipEntry(filename: String): File {
    val output = File(this, filename).canonicalFile
    val destinationPrefix = canonicalPath + File.separator

    require(output.path.startsWith(destinationPrefix))

    return output
}

/**
 * Viper Vision: the all-in-one APK (built by the build workflow) carries every AI model in its
 * assets/vv_models folder, named like the model release (see [mirrorUrl]), with index.txt listing
 * each name and size. The normal APK has none of it.
 */
private val bundledModels: Map<String, Long> by lazy {
    runCatching {
        appContext.assets.open("vv_models/index.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size == 2) parts[0] to parts[1].toLong() else null
            }.toMap()
        }
    }.getOrDefault(emptyMap())
}

/** Copies [url]'s model out of the all-in-one APK; false if this APK doesn't have it. */
private suspend fun copyBundled(
    url: String,
    destinationPath: String,
    onStart: suspend () -> Unit,
    onProgress: suspend (DownloadProgress) -> Unit,
    onFinish: suspend (Throwable?) -> Unit
): Boolean {
    val name = (mirrorUrl(url) ?: url.substringBefore('?')).removePrefix(MIRROR_BASE)
    val size = bundledModels[name] ?: return false
    onStart()
    val destination = File(destinationPath)
    val tmp = destination.createTemporarySibling()
    val error = runCatching {
        appContext.assets.open("vv_models/$name").use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                var shown = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val percent = (copied * 100 / size).toInt()
                    if (percent != shown) {
                        shown = percent
                        onProgress(DownloadProgress(currentPercent = copied.toFloat() / size, currentTotalSize = size))
                    }
                }
            }
        }
        check(tmp.length() == size) { "$name wasn't copied completely" }
        tmp.replaceAtomically(destination)
    }.exceptionOrNull()
    tmp.delete()
    if (error == null) "Copied $name from the app".makeLog("Download")
    onFinish(error)
    return true
}

/**
 * Viper Vision: where this app keeps its own copy of a downloaded AI model, so models keep working
 * if the original site goes away. Every model is mirrored into the "vipervision-ai-models" release
 * of the app's repository (.github/workflows/mirror-ai-models.yml, which names files the same way:
 * the path inside a Hugging Face repository, or else the file name, with "/" as "__").
 * Null for anything that isn't an AI model.
 */
internal fun mirrorUrl(url: String): String? {
    val clean = url.substringBefore('?')
    val isModel = listOf(".onnx", ".ort", ".zip", "tokenizer.json").any { clean.endsWith(it) }
    if (!isModel || clean.startsWith(MIRROR_BASE)) return null
    val path = if ("/resolve/" in clean) {
        clean.substringAfter("/resolve/").substringAfter('/')
    } else {
        clean.substringAfterLast('/')
    }
    return MIRROR_BASE + path.replace("/", "__")
}

private const val MIRROR_BASE = "https://github.com/ExTr3Me-Cobra/Custom-Phone-Apps/releases/download/vipervision-ai-models/"

/** Viper Vision: where model downloads are reported (the app sends them to its diagnostic log). */
object DownloadLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(message: String) {
        runCatching { sink?.invoke(message) }
    }
}
