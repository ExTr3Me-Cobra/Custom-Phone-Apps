package com.mammyframe.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * The AI that works out which way up a photo should be.
 *
 * Model: "deep-image-orientation-detection" (EfficientNetV2-S fine-tuned on ~190k photos, 98.8%
 * on its validation set), stored at half precision. It looks at a 384x384 picture and says which
 * of four turns had been applied to an upright photo: class c means the photo was turned c x 90°
 * anticlockwise, so turning it c x 90° clockwise fixes it.
 *
 * Every photo is checked four times (as it is and turned three ways) and the answers are
 * combined, which makes the "how sure" figure more trustworthy.
 */
class OrientationModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : Closeable {

    /** [fixCw]: degrees to turn clockwise (0, 90, 180, 270). [certainty]: 0..1. */
    data class Result(val fixCw: Int, val certainty: Float)

    fun predict(square: Bitmap): Result {
        val n = SIZE
        val px = IntArray(n * n)
        // Same as the model's training: squash to 416x416, then take the middle 384x384.
        square.getPixels(px, 0, n, (RESIZE - SIZE) / 2, (RESIZE - SIZE) / 2, n, n)

        val plane = n * n
        val input = FloatArray(VIEWS * 3 * plane)
        for (k in 0 until VIEWS) {
            val base = k * 3 * plane
            for (y in 0 until n) for (x in 0 until n) {
                // View k is the picture turned k x 90° anticlockwise.
                val src = when (k) {
                    0 -> y * n + x
                    1 -> x * n + (n - 1 - y)
                    2 -> (n - 1 - y) * n + (n - 1 - x)
                    else -> (n - 1 - x) * n + y
                }
                val c = px[src]
                val a = (c ushr 24) / 255f
                // See-through parts count as white, like in training.
                val r = ((c shr 16 and 0xFF) * a + 255f * (1 - a)) / 255f
                val g = ((c shr 8 and 0xFF) * a + 255f * (1 - a)) / 255f
                val b = ((c and 0xFF) * a + 255f * (1 - a)) / 255f
                val i = y * n + x
                input[base + i] = (r - MEAN[0]) / STD[0]
                input[base + plane + i] = (g - MEAN[1]) / STD[1]
                input[base + 2 * plane + i] = (b - MEAN[2]) / STD[2]
            }
        }

        val logits: Array<FloatArray> =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(VIEWS.toLong(), 3, n.toLong(), n.toLong()))
                .use { t ->
                    session.run(mapOf(session.inputNames.first() to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        (r[0].value as Array<FloatArray>).map { it.copyOf() }.toTypedArray()
                    }
                }
        val probs = logits.map { softmax(it) }

        // If the photo needs class c, view k (turned k more times) should look like class (c + k).
        val score = FloatArray(4) { c ->
            (0 until VIEWS).map { k -> ln(probs[k][(c + k) % 4] + 1e-9f) }.average().toFloat()
        }
        val combined = softmax(score)
        val best = combined.indices.maxBy { combined[it] }
        // The model was trained to say at most ~92.5% (label smoothing); rescale so "sure" reads 100%.
        val certainty = ((combined[best] - 0.25f) / (0.925f - 0.25f)).coerceIn(0f, 1f)
        return Result(best * 90, certainty)
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val SIZE = 384
        const val RESIZE = 416
        private const val VIEWS = 4
        private const val ASSET = "orientation.onnx"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private fun softmax(x: FloatArray): FloatArray {
            val m = x.max()
            val e = x.map { exp(it - m) }
            val s = e.sum()
            return FloatArray(x.size) { e[it] / s }
        }

        fun open(c: Context): OrientationModel {
            // Copied out of the app once so the AI library can load it straight from storage.
            val expected = c.assets.openFd(ASSET).use { it.length }
            val file = File(c.noBackupFilesDir, ASSET)
            if (file.length() != expected) {
                val tmp = File(c.noBackupFilesDir, "$ASSET.tmp")
                c.assets.open(ASSET).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(file)
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            return OrientationModel(env, env.createSession(file.absolutePath, opts))
        }
    }
}
