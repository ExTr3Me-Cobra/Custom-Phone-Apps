package com.labelrescue.app.pipeline

import android.content.Context
import android.util.Log
import com.labelrescue.core.ml.DigitModel
import com.labelrescue.core.ml.Models
import com.labelrescue.core.ml.NetRunner
import com.labelrescue.core.ml.RegionModel
import com.labelrescue.core.ml.RestoreModel
import com.labelrescue.core.verify.Json
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * One bundled .tflite model run with the LiteRT interpreter: GPU delegate when this device
 * supports it, otherwise (or if the delegate fails) the CPU with several threads.
 */
class LiteRtRunner(ctx: Context, asset: String) : NetRunner, AutoCloseable {
    private val gpu: GpuDelegate?
    private val interpreter: Interpreter
    val usesGpu: Boolean

    init {
        val model = map(ctx, asset)
        var delegate: GpuDelegate? = null
        var it: Interpreter? = null
        try {
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                it = Interpreter(model, Interpreter.Options().addDelegate(delegate))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate unavailable for $asset, using CPU: ${e.message}")
            delegate?.close(); delegate = null; it = null
        }
        gpu = delegate
        usesGpu = it != null
        interpreter = it ?: Interpreter(model, Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)))
        Log.i(TAG, "$asset on ${if (usesGpu) "GPU" else "CPU"}")
    }

    private val inShape = interpreter.getInputTensor(0).shape()
    private val outCount = interpreter.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
    private val inBuf = ByteBuffer.allocateDirect(4 * inShape.fold(1) { a, b -> a * b }).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(4 * outCount).order(ByteOrder.nativeOrder())

    /** Inputs must match the model's fixed shape (the core wrappers tile/resize to it). */
    @Synchronized
    override fun run(input: FloatArray, h: Int, w: Int): FloatArray {
        require(h == inShape[1] && w == inShape[2]) { "model expects ${inShape[1]}x${inShape[2]}, got ${h}x$w" }
        inBuf.rewind(); inBuf.asFloatBuffer().put(input)
        outBuf.rewind()
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        return FloatArray(outCount).also { outBuf.asFloatBuffer().get(it) }
    }

    override fun close() { interpreter.close(); gpu?.close() }

    companion object {
        private const val TAG = "LabelRescueModels"

        fun map(ctx: Context, asset: String): MappedByteBuffer {
            val fd = ctx.assets.openFd(asset)
            return FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
    }
}

/**
 * Loads the bundled models that the accuracy benchmark kept (assets/models/manifest.json says
 * which). A model that's missing or failed to load is simply not used.
 */
object OnDeviceModels {
    @Volatile private var cached: Models? = null

    fun load(ctx: Context): Models = cached ?: synchronized(this) {
        cached ?: build(ctx.applicationContext).also { cached = it }
    }

    /** Names and whether each runs on the GPU, for the settings screen. */
    @Volatile var status: String = "not loaded"
        private set

    private fun enabled(ctx: Context): Set<String> = try {
        val text = ctx.assets.open("models/manifest.json").bufferedReader().use { it.readText() }
        val m = (Json.parse(text) as Map<*, *>)["models"] as? Map<*, *> ?: emptyMap<Any, Any>()
        m.filter { (_, v) -> (v as? Map<*, *>)?.get("enabled") == true }.keys.map { it.toString() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** For the on-device benchmark: load the named models whatever the manifest says. */
    fun loadExactly(ctx: Context, names: Set<String>): Models = build(ctx.applicationContext, names)

    private fun build(ctx: Context, only: Set<String>? = null): Models {
        val on = only ?: enabled(ctx)
        val parts = ArrayList<String>()
        fun runner(name: String): LiteRtRunner? = if (name !in on) null else try {
            LiteRtRunner(ctx, "models/$name.tflite").also { parts += "$name (${if (it.usesGpu) "GPU" else "CPU"})" }
        } catch (e: Throwable) {
            Log.w("LabelRescueModels", "Couldn't load $name: ${e.message}")
            null
        }
        val models = Models(
            digits = runner(Models.DIGITS)?.let { DigitModel(it) },
            restore = runner(Models.RESTORE)?.let { RestoreModel(it) },
            regions = runner(Models.REGIONS)?.let { RegionModel(it) },
        )
        status = if (parts.isEmpty()) "none (no model passed the accuracy benchmark)" else parts.joinToString()
        return models
    }
}
