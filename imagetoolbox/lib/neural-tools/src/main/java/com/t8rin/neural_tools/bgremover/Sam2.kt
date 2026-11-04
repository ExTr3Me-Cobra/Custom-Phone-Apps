package com.t8rin.neural_tools.bgremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.t8rin.neural_tools.ModelFiles
import com.t8rin.neural_tools.NeuralTool
import com.t8rin.neural_tools.runWithOptions
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Viper Vision's Smart select: Meta's SAM 2.1 (Hiera-tiny), the newest Segment Anything, small
 * enough for phones. Prompts are points and a box, as with [MobileSAM]. Models are the
 * image-mode ONNX export from cstr/sam2.1-hiera-tiny-ONNX (Apache-2.0).
 */
object Sam2 : NeuralTool() {
    private val models = ModelFiles(
        "sam2",
        ModelFiles.Model(
            name = "encoder.onnx",
            size = 109_473_919L,
            sha256 = "4fda6e68561e27808cedffa8689749a622bba5f11ba66f4ca4e3a8a9f6360db5"
        ),
        ModelFiles.Model(
            name = "decoder.onnx",
            size = 20_655_795L,
            sha256 = "0ae944e08e55814c19afa4d143ef07b1a3dbad2decd77c15ec3ab220b9f57247"
        ),
        urlTemplate = "https://huggingface.co/cstr/sam2.1-hiera-tiny-ONNX/resolve/main/*?download=true"
    )

    /** Download size of both files. */
    const val SIZE = 109_473_919L + 20_655_795L

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val SIDE = 1024
    private const val MASK = 256

    fun isDownloaded() = models.isDownloaded()
    suspend fun download(onProgress: suspend (Float) -> Unit) = models.download(onProgress)
    fun deleteModels() = models.delete()

    fun openSession(image: Bitmap, runOptions: OrtSession.RunOptions? = null): Session {
        check(isDownloaded()) { "Download the Smart select models first" }
        return Session(image, runOptions)
    }

    class Session internal constructor(
        image: Bitmap,
        runOptions: OrtSession.RunOptions?
    ) : Closeable {
        private val environment = OrtEnvironment.getEnvironment()
        private val features: Map<String, OnnxTensor>
        private val decoder: OrtSession

        init {
            // SAM 2 squashes the photo to 1024×1024 (no padding); coordinates are scaled the same way.
            val resized = image.scale(SIDE, SIDE)
            val pixels = IntArray(SIDE * SIDE)
            resized.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            if (resized !== image) resized.recycle()
            val plane = SIDE * SIDE
            val buffer = FloatBuffer.allocate(plane * 3)
            for (i in 0 until plane) {
                val pixel = pixels[i]
                buffer.put(i, ((pixel shr 16 and 255) / 255f - mean[0]) / std[0])
                buffer.put(plane + i, ((pixel shr 8 and 255) / 255f - mean[1]) / std[1])
                buffer.put(2 * plane + i, ((pixel and 255) / 255f - mean[2]) / std[2])
            }
            features = options().use { options ->
                environment.createSession(models.file("encoder.onnx").absolutePath, options).use { encoder ->
                    OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { input ->
                        encoder.runWithOptions(mapOf("image" to input), runOptions).use { result ->
                            listOf("image_embed", "high_res_0", "high_res_1").associateWith { name ->
                                val tensor = result.get(name).get() as OnnxTensor
                                val values = tensor.floatBuffer
                                val copy = FloatArray(values.remaining()).also(values::get)
                                OnnxTensor.createTensor(environment, FloatBuffer.wrap(copy), tensor.info.shape)
                            }
                        }
                    }
                }
            }
            decoder = runCatching {
                options().use {
                    environment.createSession(models.file("decoder.onnx").absolutePath, it)
                }
            }.onFailure {
                features.values.forEach(OnnxTensor::close)
            }.getOrThrow()
        }

        /** [outputImage] with everything outside the object made transparent. */
        fun segment(
            points: List<MobileSAM.Point>,
            runOptions: OrtSession.RunOptions? = null,
            box: RectF? = null,
            outputImage: Bitmap
        ): Bitmap {
            require(points.any { it.positive } || box != null) { "Select a foreground point first" }
            val outputWidth = outputImage.width
            val outputHeight = outputImage.height
            val count = points.size + if (box != null) 2 else 0
            val coords = FloatArray(count * 2)
            val labels = LongArray(count)
            // The box's corners go first (labels 2 and 3), then the taps (1 object, 0 not).
            var index = 0
            if (box != null) {
                coords[0] = box.left.coerceIn(0f, 1f) * SIDE
                coords[1] = box.top.coerceIn(0f, 1f) * SIDE
                coords[2] = box.right.coerceIn(0f, 1f) * SIDE
                coords[3] = box.bottom.coerceIn(0f, 1f) * SIDE
                labels[0] = 2
                labels[1] = 3
                index = 2
            }
            points.forEach { point ->
                coords[index * 2] = point.x.coerceIn(0f, 1f) * SIDE
                coords[index * 2 + 1] = point.y.coerceIn(0f, 1f) * SIDE
                labels[index] = if (point.positive) 1 else 0
                index++
            }
            val tensors = mutableMapOf<String, OnnxTensor>()
            try {
                tensors["point_coords"] = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(coords), longArrayOf(1, count.toLong(), 2)
                )
                tensors["point_labels"] = OnnxTensor.createTensor(
                    environment, LongBuffer.wrap(labels), longArrayOf(1, count.toLong())
                )
                val inputs = tensors + features
                return decoder.runWithOptions(inputs, runOptions).use { output ->
                    // Mask 0 answers the whole prompt; 1–3 are guesses for one ambiguous tap
                    // (part, object, whole), of which the best scored is taken.
                    val scores = (output.get("iou").get() as OnnxTensor).floatBuffer
                    val best = if (count == 1) (1..3).maxBy { scores.get(it) } else 0
                    val masks = (output.get("mask_logits").get() as OnnxTensor).floatBuffer
                    val logits = FloatArray(MASK * MASK)
                    masks.position(best * MASK * MASK)
                    masks.get(logits)
                    val pixels = IntArray(outputWidth * outputHeight)
                    outputImage.getPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
                    val last = MASK - 1f
                    for (oy in 0 until outputHeight) {
                        val v = ((oy + 0.5f) * MASK / outputHeight - 0.5f).coerceIn(0f, last)
                        val v0 = floor(v).toInt()
                        val v1 = minOf(v0 + 1, MASK - 1)
                        val fv = v - v0
                        for (ox in 0 until outputWidth) {
                            val u = ((ox + 0.5f) * MASK / outputWidth - 0.5f).coerceIn(0f, last)
                            val u0 = floor(u).toInt()
                            val u1 = minOf(u0 + 1, MASK - 1)
                            val fu = u - u0
                            val top = logits[v0 * MASK + u0] * (1 - fu) + logits[v0 * MASK + u1] * fu
                            val bottom = logits[v1 * MASK + u0] * (1 - fu) + logits[v1 * MASK + u1] * fu
                            val logit = top * (1 - fv) + bottom * fv
                            val opacity = 1f / (1f + exp(-logit.coerceIn(-20f, 20f)))
                            val i = oy * outputWidth + ox
                            val alpha = ((pixels[i] ushr 24) * opacity).roundToInt().coerceIn(0, 255)
                            pixels[i] = (alpha shl 24) or (pixels[i] and 0xFFFFFF)
                        }
                    }
                    createBitmap(outputWidth, outputHeight).apply {
                        setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
                        setHasAlpha(true)
                    }
                }
            } finally {
                tensors.values.forEach(OnnxTensor::close)
            }
        }

        override fun close() {
            try {
                decoder.close()
            } finally {
                features.values.forEach(OnnxTensor::close)
            }
        }

        private fun options() = OrtSession.SessionOptions().apply {
            addConfigEntry("mlas.disable_kleidiai", "1")
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
    }
}
