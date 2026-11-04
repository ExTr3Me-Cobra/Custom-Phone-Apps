package com.t8rin.neural_tools.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.t8rin.neural_tools.ModelFiles
import com.t8rin.neural_tools.NeuralTool
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Viper Vision: Depth Anything V2 (Small), how near each part of a photo is. Model from
 * onnx-community/depth-anything-v2-small (Apache-2.0).
 */
object DepthAnything : NeuralTool() {
    private val models = ModelFiles(
        "depth_anything_v2",
        ModelFiles.Model(
            name = "model.onnx",
            size = 99_060_839L,
            sha256 = "afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c"
        ),
        urlTemplate = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/*?download=true"
    )

    const val SIZE = 99_060_839L

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun isDownloaded() = models.isDownloaded()
    suspend fun download(onProgress: suspend (Float) -> Unit) = models.download(onProgress)
    fun deleteModels() = models.delete()

    /** A depth map: [width]×[height] values, 0 farthest to 1 nearest. */
    class Depth(val width: Int, val height: Int, val values: FloatArray) {
        /** The value at fractions [fx], [fy] of the photo (bilinear). */
        fun at(fx: Float, fy: Float): Float {
            val x = (fx * width - 0.5f).coerceIn(0f, width - 1f)
            val y = (fy * height - 0.5f).coerceIn(0f, height - 1f)
            val x0 = x.toInt()
            val y0 = y.toInt()
            val x1 = minOf(x0 + 1, width - 1)
            val y1 = minOf(y0 + 1, height - 1)
            val ax = x - x0
            val ay = y - y0
            val top = values[y0 * width + x0] * (1 - ax) + values[y0 * width + x1] * ax
            val bottom = values[y1 * width + x0] * (1 - ax) + values[y1 * width + x1] * ax
            return top * (1 - ay) + bottom * ay
        }
    }

    /** Depth of [image], worked out at [side] px on its long side (a multiple of 14). */
    fun estimate(image: Bitmap, side: Int = 518): Depth {
        check(isDownloaded()) { "Download Depth Anything first" }
        val scale = side.toFloat() / max(image.width, image.height)
        val w = max(14, (image.width * scale / 14f).roundToInt() * 14)
        val h = max(14, (image.height * scale / 14f).roundToInt() * 14)
        val resized = image.scale(w, h)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        if (resized !== image) resized.recycle()
        val plane = w * h
        val buffer = FloatBuffer.allocate(plane * 3)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16 and 255) / 255f - mean[0]) / std[0])
            buffer.put(plane + i, ((p shr 8 and 255) / 255f - mean[1]) / std[1])
            buffer.put(2 * plane + i, ((p and 255) / 255f - mean[2]) / std[2])
        }
        val environment = OrtEnvironment.getEnvironment()
        val raw = OrtSession.SessionOptions().apply {
            addConfigEntry("mlas.disable_kleidiai", "1")
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }.use { options ->
            environment.createSession(models.file("model.onnx").absolutePath, options).use { session ->
                OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
                    session.run(mapOf("pixel_values" to input)).use { result ->
                        val values = (result[0] as OnnxTensor).floatBuffer
                        FloatArray(values.remaining()).also(values::get)
                    }
                }
            }
        }
        // Closeness, spread over 0..1 (the extreme 1% at each end clipped).
        val sorted = raw.copyOf().apply { sort() }
        val low = sorted[(sorted.size * 0.01f).toInt()]
        val high = sorted[((sorted.size - 1) * 0.99f).toInt()]
        val range = max(1e-6f, high - low)
        for (i in raw.indices) raw[i] = ((raw[i] - low) / range).coerceIn(0f, 1f)
        return Depth(w, h, raw)
    }
}
