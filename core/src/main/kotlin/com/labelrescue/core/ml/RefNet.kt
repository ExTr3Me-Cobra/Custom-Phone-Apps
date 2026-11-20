package com.labelrescue.core.ml

import com.labelrescue.core.verify.Json
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max

/**
 * Pure-Kotlin reference inference for the small sequential CNNs in /training, reading the
 * weights exported next to each .tflite (".lrw": "LRW1", JSON layer list, float32 blob).
 * The app runs the .tflite with LiteRT; this runs the same network on the JVM so the accuracy
 * benchmark can measure each model inside the real pipeline. A test checks it matches TFLite.
 *
 * Tensors are HWC float arrays. Supported layers: conv (same padding, any stride, HWIO
 * weights), maxpool (2x2), flatten, dense, and activations relu / sigmoid / softmax / linear.
 * `residual: true` adds the input to the output (restoration model).
 */
class RefNet private constructor(
    val inputShape: IntArray,
    private val layers: List<Layer>,
    private val residual: Boolean,
    val meta: Map<String, Any?>,
) {
    private sealed class Layer {
        class Conv(val k: Int, val stride: Int, val cin: Int, val cout: Int, val w: FloatArray, val b: FloatArray, val act: String) : Layer()
        data object Pool : Layer()
        data object Flatten : Layer()
        class Dense(val nin: Int, val nout: Int, val w: FloatArray, val b: FloatArray, val act: String) : Layer()
    }

    class Tensor(val h: Int, val w: Int, val c: Int, val data: FloatArray)

    /** As a [NetRunner] (single sample, any spatial size for fully convolutional nets). */
    fun runner(): NetRunner = NetRunner { input, h, w -> run(input, h, w).data }

    fun run(input: FloatArray, h: Int = inputShape[0], w: Int = inputShape[1]): Tensor {
        var t = Tensor(h, w, inputShape[2], input)
        for (l in layers) t = when (l) {
            is Layer.Conv -> conv(t, l)
            Layer.Pool -> pool(t)
            Layer.Flatten -> Tensor(1, 1, t.h * t.w * t.c, t.data)
            is Layer.Dense -> dense(t, l)
        }
        if (residual) {
            val out = FloatArray(t.data.size) { t.data[it] + input[it] }
            return Tensor(t.h, t.w, t.c, out)
        }
        return t
    }

    private fun conv(x: Tensor, l: Layer.Conv): Tensor {
        val s = l.stride; val k = l.k
        val oh = (x.h + s - 1) / s; val ow = (x.w + s - 1) / s
        val padH = max((oh - 1) * s + k - x.h, 0) / 2
        val padW = max((ow - 1) * s + k - x.w, 0) / 2
        val out = FloatArray(oh * ow * l.cout)
        val cin = l.cin; val cout = l.cout
        for (oy in 0 until oh) for (ox in 0 until ow) {
            val o = (oy * ow + ox) * cout
            for (co in 0 until cout) out[o + co] = l.b[co]
            for (ky in 0 until k) {
                val iy = oy * s + ky - padH
                if (iy < 0 || iy >= x.h) continue
                for (kx in 0 until k) {
                    val ix = ox * s + kx - padW
                    if (ix < 0 || ix >= x.w) continue
                    val ib = (iy * x.w + ix) * cin
                    val wb = (ky * k + kx) * cin * cout
                    for (ci in 0 until cin) {
                        val v = x.data[ib + ci]
                        if (v == 0f) continue
                        val wr = wb + ci * cout
                        for (co in 0 until cout) out[o + co] += v * l.w[wr + co]
                    }
                }
            }
        }
        activate(out, l.act, cout)
        return Tensor(oh, ow, cout, out)
    }

    private fun pool(x: Tensor): Tensor {
        val oh = x.h / 2; val ow = x.w / 2
        val out = FloatArray(oh * ow * x.c)
        for (y in 0 until oh) for (xx in 0 until ow) for (c in 0 until x.c) {
            var m = Float.NEGATIVE_INFINITY
            for (dy in 0..1) for (dx in 0..1) m = max(m, x.data[((2 * y + dy) * x.w + 2 * xx + dx) * x.c + c])
            out[(y * ow + xx) * x.c + c] = m
        }
        return Tensor(oh, ow, x.c, out)
    }

    private fun dense(x: Tensor, l: Layer.Dense): Tensor {
        val out = FloatArray(l.nout) { l.b[it] }
        for (i in 0 until l.nin) {
            val v = x.data[i]
            if (v == 0f) continue
            val r = i * l.nout
            for (o in 0 until l.nout) out[o] += v * l.w[r + o]
        }
        activate(out, l.act, l.nout)
        return Tensor(1, 1, l.nout, out)
    }

    private fun activate(a: FloatArray, act: String, channels: Int) {
        when (act) {
            "relu" -> for (i in a.indices) if (a[i] < 0f) a[i] = 0f
            "sigmoid" -> for (i in a.indices) a[i] = (1.0 / (1.0 + exp(-a[i].toDouble()))).toFloat()
            "softmax" -> {
                var i = 0
                while (i < a.size) {
                    var m = Float.NEGATIVE_INFINITY
                    for (k in 0 until channels) m = max(m, a[i + k])
                    var s = 0.0
                    for (k in 0 until channels) { val e = exp((a[i + k] - m).toDouble()); a[i + k] = e.toFloat(); s += e }
                    for (k in 0 until channels) a[i + k] = (a[i + k] / s).toFloat()
                    i += channels
                }
            }
            "linear", "" -> {}
            else -> error("unsupported activation $act")
        }
    }

    companion object {
        fun load(stream: InputStream): RefNet = load(stream.readBytes())

        fun load(bytes: ByteArray): RefNet {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { bb.get(it) }
            require(String(magic) == "LRW1") { "not an .lrw file" }
            val jl = bb.int
            val js = ByteArray(jl).also { bb.get(it) }
            @Suppress("UNCHECKED_CAST")
            val spec = Json.parse(String(js)) as Map<String, Any?>
            val blobStart = bb.position()
            val floats = ByteBuffer.wrap(bytes, blobStart, bytes.size - blobStart).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            fun arr(off: Int, n: Int) = FloatArray(n).also { floats.position(off); floats.get(it) }
            fun i(m: Map<*, *>, k: String) = (m[k] as Number).toInt()
            val layers = (spec["layers"] as List<*>).map { x ->
                val m = x as Map<*, *>
                when (m["type"]) {
                    "conv" -> {
                        val k = i(m, "k"); val cin = i(m, "in"); val cout = i(m, "out")
                        Layer.Conv(k, i(m, "stride"), cin, cout, arr(i(m, "w"), k * k * cin * cout), arr(i(m, "b"), cout), m["act"] as String)
                    }
                    "maxpool" -> Layer.Pool
                    "flatten" -> Layer.Flatten
                    "dense" -> { val a = i(m, "in"); val b = i(m, "out"); Layer.Dense(a, b, arr(i(m, "w"), a * b), arr(i(m, "b"), b), m["act"] as String) }
                    else -> error("unsupported layer ${m["type"]}")
                }
            }
            val shape = (spec["input"] as List<*>).map { (it as Number).toInt() }.toIntArray()
            return RefNet(shape, layers, spec["residual"] == true, spec)
        }
    }
}
