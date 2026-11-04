package com.vipervision.editor.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.JsonReader
import com.t8rin.imagetoolbox.core.domain.remote.DownloadManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Google's SigLIP 2 (base, patch 16, 256 px; Apache-2.0), the AI search's picture model: it turns
 * a photo and a few words into points in the same space, so the photos nearest a search are the
 * ones that match it. Checked against the reference implementation: the word splitting matched
 * exactly on 340 test searches, and on sample photos each search's right photo came out on top
 * (a cosine of about 0.10–0.14 against ≤ 0.065 for photos without it).
 */
object Siglip {

    class ModelFile(val url: String, val name: String, val size: Long)

    private const val SOURCE = "https://huggingface.co/onnx-community/siglip2-base-patch16-256-ONNX/resolve/main"

    val files = listOf(
        ModelFile("$SOURCE/onnx/vision_model_quantized.onnx", "vision_model_quantized.onnx", 94_737_653),
        ModelFile("$SOURCE/onnx/text_model_quantized.onnx", "text_model_quantized.onnx", 283_438_275),
        // The word pieces of the model's tokenizer.json (34 MB) in the compact form the search
        // reads (3.5 MB), made by vipervision/models/make_vocab.py into the app's model release.
        ModelFile("$MIRROR/siglip2__vocab.bin", "vocab.bin", 3_472_160)
    )

    private const val MIRROR = "https://github.com/ExTr3Me-Cobra/Custom-Phone-Apps/releases/download/vipervision-ai-models"

    const val DIMENSION = 768
    private const val IMAGE_SIZE = 256
    private const val TEXT_LENGTH = 64
    private const val PAD = 0L
    private const val EOS = 1L

    fun folder(context: Context) = File(context.filesDir, "ai_models/siglip2")

    fun isDownloaded(context: Context) = files.all { File(folder(context), it.name).length() == it.size }

    /** Downloads what's missing (the app's own copy first, see AndroidDownloadManager.mirrorUrl). */
    suspend fun download(context: Context, downloads: DownloadManager, onProgress: suspend (Float) -> Unit) {
        val folder = folder(context).apply { mkdirs() }
        val total = files.sumOf { it.size }.toFloat()
        var done = 0L
        for (file in files) {
            val target = File(folder, file.name)
            if (target.length() != file.size) {
                var failure: Throwable? = null
                downloads.download(
                    url = file.url,
                    destinationPath = target.absolutePath,
                    onProgress = { onProgress((done + it.currentPercent * file.size) / total) },
                    onFinish = { failure = it }
                )
                failure?.let { throw it }
                check(target.length() == file.size) { "${file.name} didn't download completely" }
            }
            done += file.size
            onProgress(done / total)
        }
    }

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var vision: OrtSession? = null
    private var text: OrtSession? = null
    private var vocabulary: Map<String, Int>? = null

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        // Same as the editor's AI tools: less memory held between runs.
        setMemoryPatternOptimization(false)
        setCPUArenaAllocator(false)
    }

    /** The photo as a point in SigLIP's space (unit length), or null if the model isn't here. */
    @Synchronized
    fun embedImage(context: Context, bitmap: Bitmap): FloatArray {
        val session = vision ?: environment.createSession(File(folder(context), files[0].name).absolutePath, options()).also { vision = it }
        // SigLIP squeezes the whole photo to 256 × 256 (no crop), colours scaled to -1…1.
        val scaled = if (bitmap.width == IMAGE_SIZE && bitmap.height == IMAGE_SIZE) bitmap
        else Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled !== bitmap) scaled.recycle()
        val area = IMAGE_SIZE * IMAGE_SIZE
        val input = FloatBuffer.allocate(3 * area)
        for (i in 0 until area) {
            val c = pixels[i]
            input.put(i, ((c shr 16) and 0xFF) / 127.5f - 1f)
            input.put(area + i, ((c shr 8) and 0xFF) / 127.5f - 1f)
            input.put(2 * area + i, (c and 0xFF) / 127.5f - 1f)
        }
        OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())).use { tensor ->
            session.run(mapOf("pixel_values" to tensor), setOf("pooler_output")).use { result ->
                return normalized(firstRow(result[0].value))
            }
        }
    }

    /** The search words as a point in the same space (unit length). */
    @Synchronized
    fun embedText(context: Context, query: String): FloatArray {
        val session = text ?: environment.createSession(File(folder(context), files[1].name).absolutePath, options()).also { text = it }
        val ids = tokenize(context, query)
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, TEXT_LENGTH.toLong())).use { tensor ->
            session.run(mapOf("input_ids" to tensor), setOf("pooler_output")).use { result ->
                return normalized(firstRow(result[0].value))
            }
        }
    }

    /** Frees the models (about 400 MB) once searching or indexing is done. */
    @Synchronized
    fun close() {
        vision?.close(); vision = null
        text?.close(); text = null
        vocabulary = null
    }

    private fun firstRow(value: Any): FloatArray {
        @Suppress("UNCHECKED_CAST")
        return (value as Array<FloatArray>)[0]
    }

    private fun normalized(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v * v
        val length = sqrt(sum).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(vector.size) { vector[it] / length }
    }

    /**
     * Gemma's word pieces, as SigLIP 2 was trained: lower case, spaces as "▁", then the adjacent
     * pair whose joined piece comes first in the vocabulary is joined until none is left
     * (identical to the full 580,000-rule BPE on every test; see the class notes), bytes for
     * anything unknown, an end marker, padded to 64.
     */
    fun tokenize(context: Context, query: String): LongArray {
        val vocab = vocabulary(context)
        val text = query.trim().lowercase().replace(' ', '▁').take(200)
        val pieces = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val next = text.offsetByCodePoints(i, 1)
            pieces += text.substring(i, next)
            i = next
        }
        while (true) {
            var best = Int.MAX_VALUE
            var at = -1
            for (p in 0 until pieces.size - 1) {
                val id = vocab[pieces[p] + pieces[p + 1]] ?: continue
                if (id < best) {
                    best = id
                    at = p
                }
            }
            if (at < 0) break
            pieces[at] = pieces[at] + pieces[at + 1]
            pieces.removeAt(at + 1)
        }
        val ids = ArrayList<Long>()
        for (piece in pieces) {
            val id = vocab[piece]
            if (id != null) ids += id.toLong()
            else for (b in piece.toByteArray()) ids += (vocab["<0x%02X>".format(b.toInt() and 0xFF)] ?: 3).toLong()
        }
        val out = LongArray(TEXT_LENGTH) { PAD }
        val count = minOf(ids.size, TEXT_LENGTH - 1)
        for (k in 0 until count) out[k] = ids[k]
        out[count] = EOS
        return out
    }

    /** The 256,000 word pieces, from vocab.bin. */
    private fun vocabulary(context: Context): Map<String, Int> {
        vocabulary?.let { return it }
        val map = HashMap<String, Int>(300_000)
        DataInputStream(File(folder(context), files[2].name).inputStream().buffered()).use { input ->
            repeat(input.readInt()) { map[input.readUTF()] = input.readInt() }
        }
        return map.also { vocabulary = it }
    }

    /**
     * Earlier builds downloaded the whole tokenizer.json (34 MB) and kept a vocab.bin made from
     * it; makes that vocab.bin if it's missing, then deletes tokenizer.json.
     */
    fun replaceTokenizer(context: Context) {
        val json = File(folder(context), "tokenizer.json")
        if (!json.isFile) return
        val compact = File(folder(context), files[2].name)
        if (compact.length() != files[2].size) {
            val map = LinkedHashMap<String, Int>(300_000)
            JsonReader(json.reader().buffered()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "model") { reader.skipValue(); continue }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() != "vocab") { reader.skipValue(); continue }
                        reader.beginObject()
                        while (reader.hasNext()) map[reader.nextName()] = reader.nextInt()
                        reader.endObject()
                    }
                    reader.endObject()
                }
            }
            val tmp = File(compact.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(map.size)
                for ((piece, id) in map) { out.writeUTF(piece); out.writeInt(id) }
            }
            if (tmp.length() != files[2].size || !tmp.renameTo(compact)) {
                tmp.delete()
                return
            }
        }
        json.delete()
    }
}
