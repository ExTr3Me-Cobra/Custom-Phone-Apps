package com.t8rin.neural_tools.viper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.t8rin.neural_tools.ModelFiles
import com.t8rin.neural_tools.NeuralTool
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** Viper Vision's smaller AI models (faces, face parts, in-between video frames). */
private fun sessionOptions() = OrtSession.SessionOptions().apply {
    addConfigEntry("mlas.disable_kleidiai", "1")
    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
}

/** YuNet (OpenCV Zoo, MIT): finds faces and their eyes, nose and mouth corners. Run with OpenCV. */
object YuNet : NeuralTool() {
    private val models = ModelFiles(
        "yunet",
        ModelFiles.Model("face_detection_yunet_2023mar.onnx", 232_589L, "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
        urlTemplate = "https://huggingface.co/opencv/face_detection_yunet/resolve/main/*?download=true"
    )
    const val SIZE = 232_589L
    fun isDownloaded() = models.isDownloaded()
    suspend fun download(onProgress: suspend (Float) -> Unit) = models.download(onProgress)
    val path: String get() = models.file("face_detection_yunet_2023mar.onnx").absolutePath
}

/**
 * BiSeNet face parsing (ResNet-18, CelebAMask-HQ classes): which part of a face each pixel is.
 * Classes: 0 background, 1 skin, 2–3 brows, 4–5 eyes, 6 glasses, 7–9 ears, 10 nose, 11 inside
 * of the mouth, 12–13 lips, 14 neck, 15 necklace, 16 clothes, 17 hair, 18 hat.
 */
object FaceParser : NeuralTool() {
    private val models = ModelFiles(
        "face_parser",
        ModelFiles.Model("bisenet_resnet_18.onnx", 53_205_356L, "2218b6183c26ca5c83303232d682a536c670c13ea9695f716c777d1f244eefe9"),
        urlTemplate = "https://github.com/facefusion/facefusion-assets/releases/download/models-3.1.0/*"
    )
    const val SIZE = 53_205_356L
    const val SIDE = 512
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun isDownloaded() = models.isDownloaded()
    suspend fun download(onProgress: suspend (Float) -> Unit) = models.download(onProgress)

    /** A session for parsing several faces in a row. */
    fun open(): Session = Session()

    class Session internal constructor() : Closeable {
        private val environment = OrtEnvironment.getEnvironment()
        private val session = sessionOptions().use {
            environment.createSession(models.file("bisenet_resnet_18.onnx").absolutePath, it)
        }

        /** The class of each pixel of [face] (any size; worked out at 512×512), as 512×512 bytes. */
        fun parse(face: Bitmap): ByteArray {
            val resized = face.scale(SIDE, SIDE)
            val pixels = IntArray(SIDE * SIDE)
            resized.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            if (resized !== face) resized.recycle()
            val plane = SIDE * SIDE
            val buffer = FloatBuffer.allocate(plane * 3)
            for (i in 0 until plane) {
                val p = pixels[i]
                buffer.put(i, ((p shr 16 and 255) / 255f - mean[0]) / std[0])
                buffer.put(plane + i, ((p shr 8 and 255) / 255f - mean[1]) / std[1])
                buffer.put(2 * plane + i, ((p and 255) / 255f - mean[2]) / std[2])
            }
            return OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { input ->
                session.run(mapOf("input" to input)).use { result ->
                    val logits = (result[0] as OnnxTensor).floatBuffer
                    val classes = (logits.remaining() / plane).coerceAtLeast(1)
                    val out = ByteArray(plane)
                    val best = FloatArray(plane) { Float.NEGATIVE_INFINITY }
                    for (c in 0 until classes) {
                        val base = c * plane
                        for (i in 0 until plane) {
                            val v = logits.get(base + i)
                            if (v > best[i]) {
                                best[i] = v
                                out[i] = c.toByte()
                            }
                        }
                    }
                    out
                }
            }
        }

        override fun close() = session.close()
    }
}

/** RIFE 4.9 (Practical-RIFE, MIT): makes the frame between two video frames. */
object Rife : NeuralTool() {
    private val models = ModelFiles(
        "rife",
        ModelFiles.Model("rife49.onnx", 21_458_882L, "76e4cef9ab42fa7dd4e8f6e4aba47462051e3faa969e4bca6479784fbab0ac6f"),
        urlTemplate = "https://huggingface.co/edgetools/rife/resolve/main/*?download=true"
    )
    const val SIZE = 21_458_882L

    fun isDownloaded() = models.isDownloaded()
    suspend fun download(onProgress: suspend (Float) -> Unit) = models.download(onProgress)

    fun open(): Session = Session()

    class Session internal constructor() : Closeable {
        private val environment = OrtEnvironment.getEnvironment()
        private val session = sessionOptions().use {
            environment.createSession(models.file("rife49.onnx").absolutePath, it)
        }

        /** The frame [t] (0..1) of the way from [a] to [b] (same size). */
        fun between(a: Bitmap, b: Bitmap, t: Float): Bitmap {
            val w = a.width
            val h = a.height
            val plane = w * h
            fun tensor(bitmap: Bitmap): OnnxTensor {
                val px = IntArray(plane)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)
                val buffer = FloatBuffer.allocate(plane * 3)
                for (i in 0 until plane) {
                    val p = px[i]
                    buffer.put(i, (p shr 16 and 255) / 255f)
                    buffer.put(plane + i, (p shr 8 and 255) / 255f)
                    buffer.put(2 * plane + i, (p and 255) / 255f)
                }
                return OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, h.toLong(), w.toLong()))
            }
            val inputs = mapOf(
                "img0" to tensor(a),
                "img1" to tensor(b),
                "timestep" to OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1))
            )
            try {
                return session.run(inputs).use { result ->
                    val out = (result[0] as OnnxTensor).floatBuffer
                    val px = IntArray(plane)
                    for (i in 0 until plane) {
                        fun c(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
                        px[i] = (0xFF shl 24) or (c(out.get(i)) shl 16) or (c(out.get(plane + i)) shl 8) or c(out.get(2 * plane + i))
                    }
                    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                }
            } finally {
                inputs.values.forEach(OnnxTensor::close)
            }
        }

        override fun close() = session.close()
    }
}
