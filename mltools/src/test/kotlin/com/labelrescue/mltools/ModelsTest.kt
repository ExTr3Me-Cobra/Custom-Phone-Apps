package com.labelrescue.mltools

import com.labelrescue.core.ml.CharCrop
import com.labelrescue.core.ml.RefNet
import com.labelrescue.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

class ModelsTest {
    private val models = File("../models")

    /** The JVM reference implementation must give the same outputs as LiteRT for the same weights. */
    @Test fun refNetMatchesLiteRt() {
        val names = listOf("digit_classifier", "restore", "region_detector").filter { File(models, "$it.testvec.json").exists() }
        assumeTrue("no trained models yet", names.isNotEmpty())
        for (name in names) {
            val net = RefNet.load(File(models, "$name.lrw").readBytes())
            val tv = Json.parse(File(models, "$name.testvec.json").readText()) as Map<*, *>
            val shape = (tv["input_shape"] as List<*>).map { (it as Number).toInt() }
            val inputs = tv["inputs"] as List<*>; val outputs = tv["outputs"] as List<*>
            for (k in inputs.indices) {
                val x = (inputs[k] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
                val want = (outputs[k] as List<*>).map { (it as Number).toFloat() }
                val got = net.run(x, shape[0], shape[1]).data
                assertEquals(name, want.size, got.size)
                var maxErr = 0f
                for (i in want.indices) maxErr = maxOf(maxErr, abs(want[i] - got[i]))
                assertTrue("$name sample $k max error $maxErr", maxErr < 2e-3f)
            }
        }
    }

    @Test fun modelsAreSmall() {
        for (f in models.listFiles().orEmpty().filter { it.name.endsWith(".tflite") }) {
            assertTrue("${f.name} is ${f.length()} bytes", f.length() < 2_000_000)
        }
    }

    @Test fun generatorsProduceValidSamples() {
        val rnd = Random(1)
        repeat(20) {
            val (x, y) = Generators.digitSample(rnd)
            assertEquals(CharCrop.SIZE * CharCrop.SIZE, x.size)
            assertTrue(y in 0..9)
        }
        val (img, mask) = Generators.regionSample(rnd)
        assertEquals(256 * 256, img.size)
        // The barcode and the tracking number text must both be present in the mask.
        for (k in 0..1) assertTrue("kind $k", (mask.indices.filter { it % 4 == k }).any { mask[it] > 0 })
    }

    @Test fun simulatedOcrFailsWhereInkIsDamaged() {
        val c = Bench.makeCase(3)
        val page = c.ocr.recognize(c.frames[0])
        val number = page.lines.last()
        assertTrue(number.elements.sumOf { it.symbols.size } in 10..22)
        assertTrue(c.ocr.recognize(c.frames[0].copy()).lines.isEmpty())
    }
}
