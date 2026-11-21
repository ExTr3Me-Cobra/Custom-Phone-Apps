package com.labelrescue.mltools

import com.labelrescue.core.image.Affine
import com.labelrescue.core.image.Filters
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ml.CharCrop
import com.labelrescue.core.ml.DetectedRegion
import com.labelrescue.core.ml.RegionModel
import com.labelrescue.core.synth.Synth
import com.labelrescue.core.util.Json
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Label damage beyond core Synth: partial strokes, creases, thermal streaks, pixelation. */
object Damage {
    /** White strokes across a box: rubbed-off or partially printed characters. */
    fun partialStrokes(img: GrayImage, box: IntRect, rnd: Random, count: Int): GrayImage {
        val out = img.copy()
        repeat(count) {
            val x0 = box.left + rnd.nextInt(max(1, box.width)); val y0 = box.top + rnd.nextInt(max(1, box.height))
            val ang = rnd.nextDouble() * Math.PI
            val len = box.height * (0.3 + rnd.nextDouble() * 0.6)
            val thick = 1 + rnd.nextInt(max(1, box.width / 5))
            val steps = len.toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val x = (x0 + Math.cos(ang) * (s - steps / 2)).toInt(); val y = (y0 + Math.sin(ang) * (s - steps / 2)).toInt()
                for (dy in -thick / 2..thick / 2) for (dx in -thick / 2..thick / 2) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until out.width && yy in 0 until out.height) out[xx, yy] = Synth.PAPER - rnd.nextInt(10)
                }
            }
        }
        return out
    }

    /** A fold line: brightness step plus a 1-2 px shift across the line. */
    fun crease(img: GrayImage, rnd: Random): GrayImage {
        val out = img.copy()
        val horizontal = rnd.nextBoolean()
        val pos = if (horizontal) rnd.nextInt(img.height) else rnd.nextInt(img.width)
        val shift = 1 + rnd.nextInt(2); val dark = rnd.nextInt(20, 60)
        if (horizontal) {
            for (y in pos until min(img.height, pos + 3)) for (x in 0 until img.width) out[x, y] = img[x, y] - dark
            for (y in pos + 3 until img.height) for (x in 0 until img.width) out[x, y] = img.getClamped(x - shift, y)
        } else {
            for (x in pos until min(img.width, pos + 3)) for (y in 0 until img.height) out[x, y] = img[x, y] - dark
            for (x in pos + 3 until img.width) for (y in 0 until img.height) out[x, y] = img.getClamped(x, y - shift)
        }
        return out
    }

    /** Thermal printhead defects: thin vertical lines with no ink, wide dead bands, uneven fading. */
    fun thermal(img: GrayImage, rnd: Random): GrayImage {
        val out = Synth.fade(img, rnd.nextDouble() * 0.5)
        repeat(1 + rnd.nextInt(4)) {
            val x = rnd.nextInt(img.width); val wdt = 1 + rnd.nextInt(2)
            for (xx in x until min(img.width, x + wdt)) for (y in 0 until img.height) out[xx, y] = max(out[xx, y], Synth.PAPER - 8)
        }
        // Sometimes a group of dead elements: wide blank bands that delete whole bars and half
        // digits (seen on a real Parcel Select label), with ragged, slightly gray edges.
        if (rnd.nextBoolean()) repeat(1 + rnd.nextInt(3)) {
            val wdt = max(3, (img.width * (0.01 + rnd.nextDouble() * 0.025)).toInt())
            val x = rnd.nextInt(max(1, img.width - wdt))
            for (xx in x until min(img.width, x + wdt)) {
                val edge = xx == x || xx == x + wdt - 1
                for (y in 0 until img.height) out[xx, y] = if (edge) max(out[xx, y], (out[xx, y] + Synth.PAPER) / 2) else max(out[xx, y], Synth.PAPER - 6)
            }
        }
        return out
    }

    /** Low-resolution print or camera: down then up with nearest neighbour. */
    fun pixelate(img: GrayImage, f: Double): GrayImage {
        val small = img.scaledBy(f)
        val out = GrayImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) out[x, y] = small.getClamped((x * f).toInt(), (y * f).toInt())
        return out
    }

    /** Photo-like degradation that a restoration model can undo (no geometry change). */
    fun photo(img: GrayImage, rnd: Random): GrayImage {
        var d = img
        if (rnd.nextInt(5) != 0) d = Synth.blur(d, 0.6 + rnd.nextDouble() * 1.8)
        if (rnd.nextInt(3) == 0) d = Filters.boxBlur(d, 1)
        if (rnd.nextInt(2) == 0) d = Synth.fade(d, rnd.nextDouble() * 0.55)
        if (rnd.nextInt(3) == 0) d = Synth.shade(d, rnd.nextInt(10, 50))
        return Synth.noise(d, 3.0 + rnd.nextDouble() * 12, rnd)
    }

    /** Everything, for frames and digit crops. */
    fun heavy(img: GrayImage, focus: IntRect?, rnd: Random, level: Int): GrayImage {
        var d = img
        repeat(level) {
            d = when (rnd.nextInt(8)) {
                0 -> focus?.let { partialStrokes(d, it, rnd, 1 + rnd.nextInt(3)) } ?: d
                1 -> Synth.inkDropout(d, rnd, 3 + rnd.nextInt(10), max(3, (focus?.height ?: 40) / 4))
                2 -> Synth.smudge(d, rnd, 1 + rnd.nextInt(3), max(4, (focus?.height ?: 40) / 3), darkness = rnd.nextInt(40, 140))
                3 -> crease(d, rnd)
                4 -> thermal(d, rnd)
                5 -> pixelate(d, 0.35 + rnd.nextDouble() * 0.4)
                6 -> Synth.fade(d, 0.3 + rnd.nextDouble() * 0.4)
                else -> Synth.blur(d, 0.8 + rnd.nextDouble() * 1.6)
            }
        }
        return Synth.noise(Synth.blur(d, rnd.nextDouble() * 0.9), 2.0 + rnd.nextDouble() * 8, rnd)
    }
}

/** Binary dataset: "LRD1", JSON header, inputs (uint8), then labels (uint8). */
class DatasetWriter(file: File, private val header: Map<String, Any?>) : AutoCloseable {
    private val xs = File(file.path + ".x.tmp")
    private val ys = File(file.path + ".y.tmp")
    private val xo = DataOutputStream(BufferedOutputStream(FileOutputStream(xs)))
    private val yo = DataOutputStream(BufferedOutputStream(FileOutputStream(ys)))
    private val target = file
    var count = 0; private set

    fun add(x: ByteArray, y: ByteArray) { xo.write(x); yo.write(y); count++ }

    override fun close() {
        xo.close(); yo.close()
        val js = Json.write(header + ("count" to count)).toByteArray()
        DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use { o ->
            o.write("LRD1".toByteArray())
            o.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(js.size).array())
            o.write(js)
            xs.inputStream().use { it.copyTo(o) }
            ys.inputStream().use { it.copyTo(o) }
        }
        xs.delete(); ys.delete()
    }
}

object Generators {
    /** One damaged digit crop, as the app would cut it around an OCR box. */
    fun digitSample(rnd: Random): Pair<ByteArray, Int> {
        val n = 3 + rnd.nextInt(3)
        val digits = (0 until n).map { rnd.nextInt(10) }
        val target = 1 + rnd.nextInt(n - 2)
        val font = LabelFonts.random(rnd, 26f + rnd.nextInt(34))
        val bi = BufferedImage(40 + n * 48, 110, BufferedImage.TYPE_BYTE_GRAY)
        val g = bi.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, if (rnd.nextInt(4) == 0) RenderingHints.VALUE_TEXT_ANTIALIAS_OFF else RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val paper = 215 + rnd.nextInt(35)
        g.color = Color(paper, paper, paper); g.fillRect(0, 0, bi.width, bi.height)
        val inkV = rnd.nextInt(0, 70); g.color = Color(inkV, inkV, inkV); g.font = font
        val s = digits.joinToString("")
        val x = 20; val y = 75
        g.drawString(s, x, y)
        val b = font.createGlyphVector(g.fontRenderContext, s).getGlyphPixelBounds(target, g.fontRenderContext, x.toFloat(), y.toFloat())
        g.dispose()
        var img = GrayImage(bi.width, bi.height, (bi.raster.getDataElements(0, 0, bi.width, bi.height, null) as ByteArray).copyOf())
        val box = IntRect(b.x, b.y, b.x + max(1, b.width), b.y + max(1, b.height))
        // 0..3 damage steps; some samples nearly destroyed so the model learns to be unsure.
        img = Damage.heavy(img, box, rnd, rnd.nextInt(4))
        if (rnd.nextInt(6) == 0) img = Damage.partialStrokes(img, box, rnd, 2 + rnd.nextInt(3))
        // OCR boxes are noisy; boxes of missed characters are estimates from the pitch.
        val jitter = if (rnd.nextInt(5) == 0) 0.3 else 0.12
        val h = box.height.toDouble()
        val cx = box.centerX + (rnd.nextDouble() * 2 - 1) * jitter * h
        val cy = box.centerY + (rnd.nextDouble() * 2 - 1) * jitter * h
        val sc = 1 + (rnd.nextDouble() * 2 - 1) * jitter
        val bw = box.width * sc; val bh = h * sc
        var jbox = IntRect((cx - bw / 2).roundToInt(), (cy - bh / 2).roundToInt(), (cx + bw / 2).roundToInt(), (cy + bh / 2).roundToInt())
        // A box shifted so far that the crop lands on a neighbor would teach the neighbor's digit
        // under this label: use the true box then (the crop is what the app cuts for a good box).
        val glyph = CharCrop.refine(img, jbox)
        if (glyph.centerX < box.left || glyph.centerX > box.right) jbox = box
        val crop = CharCrop.extract(img, jbox)
        return ByteArray(crop.size) { (crop[it] * 255).roundToInt().coerceIn(0, 255).toByte() } to digits[target]
    }

    /** Aligned (damaged, clean) patch pairs from rendered labels. */
    fun restorePatches(rnd: Random, size: Int, count: Int): List<Pair<ByteArray, ByteArray>> {
        val label = LabelRenderer.render(LabelRenderer.randomTracking(rnd), "%05d".format(rnd.nextInt(100000)), rnd)
        val clean = label.image
        val damaged = Damage.photo(clean, rnd)
        val focus = label.regions.values.flatten() + listOf(label.numberLine)
        return (0 until count).map {
            val r = if (rnd.nextInt(5) != 0) focus.random(rnd) else IntRect(0, 0, clean.width, clean.height)
            val x = (r.left + rnd.nextInt(max(1, r.width)) - size / 2).coerceIn(0, clean.width - size)
            val y = (r.top + rnd.nextInt(max(1, r.height)) - size / 2).coerceIn(0, clean.height - size)
            val a = damaged.crop(IntRect(x, y, x + size, y + size)).data.copyOf()
            val c = clean.crop(IntRect(x, y, x + size, y + size)).data.copyOf()
            a to c
        }
    }

    /** A photographed label (placed, rotated, damaged) letterboxed to the detector input, with masks. */
    fun regionSample(rnd: Random): Pair<ByteArray, ByteArray> {
        val label = LabelRenderer.render(LabelRenderer.randomTracking(rnd), "%05d".format(rnd.nextInt(100000)), rnd)
        val lw = label.image.width; val lh = label.image.height
        val cw = (lw * (1.05 + rnd.nextDouble() * 0.6)).toInt(); val ch = (lh * (1.05 + rnd.nextDouble() * 0.6)).toInt()
        val canvas = GrayImage.filled(cw, ch, rnd.nextInt(60, 200))
        val ox = rnd.nextInt(max(1, cw - lw)); val oy = rnd.nextInt(max(1, ch - lh))
        for (y in 0 until lh) for (x in 0 until lw) canvas[x + ox, y + oy] = label.image[x, y]
        var img = Synth.noise(canvas, 6.0, rnd)
        var toOld = Affine.IDENTITY
        val deg = (rnd.nextDouble() * 2 - 1) * 6
        if (rnd.nextBoolean()) { val (r, back) = img.rotated(deg, rnd.nextInt(60, 200)); img = r; toOld = back }
        img = Damage.heavy(img, null, rnd, rnd.nextInt(3))
        val toNew = invert(toOld)
        val s = min(RegionModel.INPUT.toDouble() / img.width, RegionModel.INPUT.toDouble() / img.height)
        val small = img.scaled(max(1, (img.width * s).roundToInt()), max(1, (img.height * s).roundToInt()))
        val x = ByteArray(RegionModel.INPUT * RegionModel.INPUT) { 255.toByte() }
        for (yy in 0 until small.height) for (xx in 0 until small.width) x[yy * RegionModel.INPUT + xx] = small[xx, yy].toByte()
        val g = RegionModel.INPUT / RegionModel.STRIDE
        val kinds = DetectedRegion.Kind.entries
        val mask = ByteArray(g * g * kinds.size)
        for ((k, kind) in kinds.withIndex()) for (r0 in label.regions[kind].orEmpty()) {
            val r = toNew.mapRect(IntRect(r0.left + ox, r0.top + oy, r0.right + ox, r0.bottom + oy))
            val cell = RegionModel.STRIDE / s
            // A cell is positive if the region covers at least a quarter of it (thin text lines count).
            for (cy in 0 until g) for (cx in 0 until g) {
                val ix = min(r.right.toDouble(), (cx + 1) * cell) - max(r.left.toDouble(), cx * cell)
                val iy = min(r.bottom.toDouble(), (cy + 1) * cell) - max(r.top.toDouble(), cy * cell)
                if (ix > 0 && iy > 0 && ix * iy >= 0.25 * cell * cell) mask[(cy * g + cx) * kinds.size + k] = 1
            }
        }
        return x to mask
    }

    fun invert(a: Affine): Affine {
        val det = a.a * a.e - a.b * a.d
        val ia = a.e / det; val ib = -a.b / det; val id = -a.d / det; val ie = a.a / det
        return Affine(ia, ib, -(ia * a.c + ib * a.f), id, ie, -(id * a.c + ie * a.f))
    }
}

/** Usage: datasets <outDir> [scale] */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/datasets" }).apply { mkdirs() }
    val scale = args.getOrElse(1) { "1.0" }.toDouble()
    val which = args.getOrElse(2) { "all" }.split(",").toSet()
    fun want(k: String) = "all" in which || k in which
    println("Fonts: ${LabelFonts.available}")
    fun digits(name: String, n: Int, seed: Int) {
        val rnd = Random(seed)
        DatasetWriter(File(out, name), mapOf("kind" to "digits", "h" to CharCrop.SIZE, "w" to CharCrop.SIZE, "c" to 1, "label" to listOf(1))).use { w ->
            repeat(n) { val (x, y) = Generators.digitSample(rnd); w.add(x, byteArrayOf(y.toByte())) }
            println("$name: ${w.count}")
        }
    }
    val jobs = ArrayList<Thread>()
    fun job(f: () -> Unit) { jobs += Thread(f).apply { start() } }
    if (want("digits")) {
        job { digits("digits_train.lrd", (60000 * scale).toInt(), 1) }
        job { digits("digits_val.lrd", (6000 * scale).toInt(), 2) }
    }

    fun restore(name: String, labels: Int, seed: Int) {
        val rnd = Random(seed); val size = 64
        DatasetWriter(File(out, name), mapOf("kind" to "restore", "h" to size, "w" to size, "c" to 1, "label" to listOf(size, size, 1))).use { w ->
            repeat(labels) { for ((a, c) in Generators.restorePatches(rnd, size, 16)) w.add(a, c) }
            println("$name: ${w.count}")
        }
    }
    if (want("restore")) {
        job { restore("restore_train.lrd", (1250 * scale).toInt(), 3) }
        job { restore("restore_val.lrd", (120 * scale).toInt(), 4) }
    }

    fun regions(name: String, n: Int, seed: Int) {
        val rnd = Random(seed); val g = RegionModel.INPUT / RegionModel.STRIDE
        DatasetWriter(File(out, name), mapOf("kind" to "regions", "h" to RegionModel.INPUT, "w" to RegionModel.INPUT, "c" to 1, "label" to listOf(g, g, RegionModel.KINDS))).use { w ->
            repeat(n) { val (x, y) = Generators.regionSample(rnd); w.add(x, y) }
            println("$name: ${w.count}")
        }
    }
    // The big one is split in parts so it doesn't finish last by a mile.
    if (want("regions")) {
        for (part in 0 until 3) job { regions("regions_train_$part.lrd", (5000 * scale / 3).toInt(), 50 + part) }
        job { regions("regions_val.lrd", (500 * scale).toInt(), 6) }
    }
    jobs.forEach { it.join() }
}
