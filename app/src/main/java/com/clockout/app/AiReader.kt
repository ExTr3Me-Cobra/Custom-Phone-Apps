package com.clockout.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the clock rings with the phone's own AI (Gemini Nano, through Android's AICore): everything stays on the
 * phone, nothing is sent anywhere. It looks at the table the way a person does, so a wrapped AM/PM, glare or an angle
 * matter much less than for plain text recognition. Phones without it (or before its model has downloaded) use the
 * text recognition instead.
 */
object AiReader {
    private val model by lazy { Generation.getClient() }

    /** Is the AI ready? Starts its download the first time, if the phone can run it. */
    suspend fun ready(): Boolean = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> { startDownload(); false }
            else -> false
        }
    }.getOrDefault(false)

    @Volatile private var downloading = false
    private suspend fun startDownload() {
        if (downloading) return
        downloading = true
        runCatching { model.download().collect { if (it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed) downloading = false } }
        downloading = false
    }

    /** Call at start-up: gets the model downloaded in the background so it's ready for the first photo. */
    suspend fun prepare() { runCatching { if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) startDownload() } }

    private fun prompt(decimal: Boolean) = """
        This photo shows a table of time-clock rings. Each row has a TR CODE (a number and two letters, like "013 IL")
        and a TR DATETIME (like "06-NOV-26 01.47.05 PM"${if (decimal) ", or a decimal time like 14.68" else ""}).
        Some rows' AM/PM may be on the line below the time. Ignore anything below the table (details about one ring,
        employee name, op code).
        List EVERY row of the table, one per line, exactly in this form and nothing else:
        CODE | DATETIME
        Use only the two letters for CODE. Copy the date and time exactly as written, including AM or PM.
        Example:
        IL | 06-NOV-26 01.47.05 PM
        OL | 06-NOV-26 12.16.05 PM
    """.trimIndent()

    /** The rings in [uris] (null if the AI isn't available or found nothing). The AI's answer goes in [raw]. */
    suspend fun read(context: Context, uris: List<Uri>, decimal: Boolean, raw: StringBuilder): List<Ring>? {
        if (!ready()) return null
        val all = mutableListOf<Ring>()
        for (uri in uris) {
            val bmp = withContext(Dispatchers.IO) { runCatching { load(context, uri) }.getOrNull() } ?: continue
            val answer = withTimeoutOrNull(60_000) {
                runCatching {
                    model.generateContent(generateContentRequest(ImagePart(bmp), TextPart(prompt(decimal))) {
                        temperature = 0f
                        maxOutputTokens = 400
                    }).candidates.firstOrNull()?.text
                }.getOrNull()
            } ?: continue
            raw.append(answer.trim()).append('\n')
            all += RingReader.fromRows(answer, decimal)
        }
        return all.distinctBy { it.time }.sortedBy { it.time }.ifEmpty { null }
    }

    private fun load(context: Context, uri: Uri): Bitmap {
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // About 1000 px on the long side: plenty for the text, and quick for the AI.
            val long = maxOf(info.size.width, info.size.height)
            if (long > 1024) d.setTargetSampleSize(long / 1024)
        }
        return b
    }
}
