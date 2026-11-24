package com.labelrescue.mltools

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.labelrescue.core.ocr.PaddleLineRecognizer
import com.labelrescue.core.ocr.SequenceNet
import java.nio.FloatBuffer

/** The original PaddleOCR ONNX model on the JVM (for development tests; the app uses LiteRT). */
class OrtSequenceNet(model: ByteArray) : SequenceNet, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model, OrtSession.SessionOptions().apply { setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) })
    private val inputName = session.inputNames.first()

    override fun run(nhwc: FloatArray): Array<FloatArray> {
        val h = PaddleLineRecognizer.HEIGHT; val w = PaddleLineRecognizer.WIDTH
        val chw = FloatArray(3 * h * w)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) chw[c * h * w + y * w + x] = nhwc[(y * w + x) * 3 + c]
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r[0].value as Array<Array<FloatArray>>)[0]
            }
        }
    }

    override fun close() { session.close() }
}
