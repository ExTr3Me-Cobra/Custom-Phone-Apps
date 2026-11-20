package com.labelrescue.mltools

import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ml.DigitModel
import com.labelrescue.core.ml.Models
import com.labelrescue.core.ml.RefNet
import com.labelrescue.core.ml.RegionModel
import com.labelrescue.core.ml.RestoreModel
import com.labelrescue.core.ocr.OcrElement
import com.labelrescue.core.ocr.OcrLine
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.OcrSymbol
import com.labelrescue.core.ocr.TextRecognizer
import com.labelrescue.core.synth.Synth
import com.labelrescue.core.usps.TrackingFormat
import com.labelrescue.core.verify.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A benchmark case: a damaged label photographed a few times. */
class BenchCase(val truth: String, val frames: List<GrayImage>, val ocr: TextRecognizer)

/**
 * OCR stand-in for the JVM: reads the printed number at its true glyph boxes and makes mistakes
 * where the ink is actually damaged (dropped or confused characters, low confidence), so a model
 * that looks at the pixels can be measured fairly. Only answers for the full frame (identity
 * coordinates); other variants get nothing.
 */
class SimulatedOcr(
    private val frames: List<GrayImage>,
    private val label: RenderedLabel,
    private val damaged: GrayImage,
    seed: Int,
) : TextRecognizer {
    private val rnd = Random(seed)
    private val damage = label.digitBoxes.map { b -> damageScore(label.image, damaged, b) }

    override fun recognize(img: GrayImage): OcrPage {
        if (frames.none { it === img }) return OcrPage(emptyList())
        val confusions = mapOf('0' to "689", '1' to "74", '2' to "73", '3' to "85", '4' to "19", '5' to "63", '6' to "508", '7' to "12", '8' to "3609", '9' to "084")
        val elements = ArrayList<OcrElement>()
        var syms = ArrayList<OcrSymbol>()
        var i = 0
        for (g in TrackingFormat.groups(22)) {
            for (k in 0 until g) {
                val d = damage[i]
                val c = label.tracking[i]
                when {
                    rnd.nextDouble() < min(0.9, 0.02 + 1.3 * d) -> {} // dropped
                    rnd.nextDouble() < min(0.85, 0.02 + 1.1 * d) -> syms += OcrSymbol(confusions.getValue(c).random(rnd).toString(), (0.25 + 0.35 * rnd.nextDouble()).toFloat(), label.digitBoxes[i])
                    else -> syms += OcrSymbol(c.toString(), (0.97 - 0.6 * d).toFloat(), label.digitBoxes[i])
                }
                i++
            }
            if (syms.isNotEmpty()) elements += OcrElement(syms.joinToString("") { it.text }, 0.9f, syms.map { it.box!! }.reduce { a, b -> a.union(b) }, syms)
            syms = ArrayList()
        }
        val number = OcrLine(elements.joinToString(" ") { it.text }, 0.9f, label.numberLine, 0f, elements)
        return OcrPage(label.textLines.map { OcrLine(it, 0.9f, null, 0f, emptyList()) } + number)
    }

    companion object {
        /** Fraction of the glyph box whose ink changed (lost or smeared). */
        fun damageScore(clean: GrayImage, damaged: GrayImage, b: IntRect): Double {
            var changed = 0; var n = 0
            for (y in max(0, b.top) until min(clean.height, b.bottom)) for (x in max(0, b.left) until min(clean.width, b.right)) {
                n++
                if (abs(clean[x, y] - damaged[x, y]) > 70) changed++
            }
            return if (n == 0) 0.0 else (changed.toDouble() / n * 2.5).coerceIn(0.0, 1.0)
        }
    }
}

object Bench {
    /** Physical damage to the label (same in every frame), then per-frame photo degradation. */
    fun makeCase(index: Int, frames: Int = 2): BenchCase {
        val rnd = Random(10_000 + index)
        val tracking = LabelRenderer.randomTracking(rnd)
        val label = LabelRenderer.render(tracking, "%05d".format(rnd.nextInt(100000)), rnd)
        var img = label.image
        // Several printed digits damaged.
        for (b in label.digitBoxes.shuffled(rnd).take(1 + rnd.nextInt(5))) {
            img = when (rnd.nextInt(3)) {
                0 -> Damage.partialStrokes(img, b, rnd, 2 + rnd.nextInt(3))
                1 -> local(img, b) { Synth.inkDropout(it, rnd, 4, max(3, b.height / 3)) }
                else -> local(img, b) { Synth.smudge(it, rnd, 2, max(4, b.height / 2), rnd.nextInt(60, 140)) }
            }
        }
        // The barcode too: lost symbols, smears, or a tear across it.
        val bc = label.regions.getValue(com.labelrescue.core.ml.DetectedRegion.Kind.BARCODE).first()
        when (rnd.nextInt(4)) {
            0 -> repeat(2 + rnd.nextInt(3)) { img = Synth.verticalLoss(img, bc.left + rnd.nextInt(bc.width - 30), 15 + rnd.nextInt(30)) }
            1 -> img = Synth.tear(img, IntRect(bc.left + bc.width / 3, bc.top - 5, bc.left + bc.width / 3 + bc.width / 4 + rnd.nextInt(bc.width / 4), bc.bottom + 5))
            2 -> img = Synth.smudge(img, rnd, 6, 40)
            else -> img = Damage.thermal(img, rnd)
        }
        if (rnd.nextInt(3) == 0) img = Damage.crease(img, rnd)
        val damaged = img
        val fs = (0 until frames).map { f -> Damage.photo(damaged, Random(index * 31 + f)) }
        return BenchCase(tracking, fs, SimulatedOcr(fs, label, damaged, index))
    }

    /** Applies [f] to the area around glyph box [b] only. */
    private fun local(img: GrayImage, b: IntRect, f: (GrayImage) -> GrayImage): GrayImage {
        val r = b.inflate(b.width / 3, b.height / 4).intersect(IntRect(0, 0, img.width, img.height))
        val patch = f(img.crop(r))
        val out = img.copy()
        for (y in 0 until r.height) for (x in 0 until r.width) out[r.left + x, r.top + y] = patch[x, y]
        return out
    }

    class Result(val top1: BooleanArray, val top3: BooleanArray, val millis: Long) {
        val top1Count get() = top1.count { it }
        val top3Count get() = top3.count { it }
    }

    fun run(cases: List<BenchCase>, models: Models): Result {
        val engine = CandidateEngine()
        val t0 = System.currentTimeMillis()
        val top1 = BooleanArray(cases.size); val top3 = BooleanArray(cases.size)
        for ((i, c) in cases.withIndex()) {
            val out = RecoveryPipeline(listOf(ZxingDecoder()), c.ocr, models = models).run(c.frames, RecoveryPipeline.Options(maxFrames = c.frames.size))
            val nums = when {
                out.fullDecode != null -> listOf(out.fullDecode!!.tracking)
                out.format != null -> engine.rank(out.format!!, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence()).candidates.map { it.tracking }
                else -> emptyList()
            }
            top1[i] = nums.firstOrNull() == c.truth
            top3[i] = c.truth in nums.take(3)
        }
        return Result(top1, top3, System.currentTimeMillis() - t0)
    }

    fun loadModels(dir: File): Map<String, Models> {
        fun net(name: String) = File(dir, "$name.lrw").takeIf { it.exists() }?.let { RefNet.load(it.readBytes()) }
        val out = LinkedHashMap<String, Models>()
        net(Models.DIGITS)?.let { out[Models.DIGITS] = Models(digits = DigitModel(it.runner())) }
        net(Models.RESTORE)?.let { out[Models.RESTORE] = Models(restore = RestoreModel(it.runner(), tile = (it.meta["meta"] as? Map<*, *>)?.get("tile")?.let { t -> (t as Number).toInt() } ?: 96)) }
        net(Models.REGIONS)?.let { out[Models.REGIONS] = Models(regions = RegionModel(it.runner())) }
        return out
    }
}

/**
 * Usage: benchmark <modelsDir> [cases]
 * Runs every case without models, with each model alone, and with all kept models; keeps a model
 * only if it fixes more cases than it breaks (net gain ≥ max(1, 2% of cases)) and doesn't lower
 * top-1. Writes manifest.json (read by the app) and BENCHMARK.md.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "models" })
    val n = args.getOrElse(1) { "48" }.toInt()
    val cases = (0 until n).map { Bench.makeCase(it) }
    val available = Bench.loadModels(dir)
    println("Benchmark: $n cases, models found: ${available.keys}")
    val base = Bench.run(cases, Models.NONE)
    println("baseline: top1 ${base.top1Count}/$n top3 ${base.top3Count}/$n (${base.millis / 1000}s)")
    val rows = ArrayList<Map<String, Any?>>()
    val kept = LinkedHashMap<String, Boolean>()
    val minGain = max(1, (n * 0.02).toInt())
    for ((name, m) in available) {
        val r = Bench.run(cases, m)
        val gains = (0 until n).count { !base.top1[it] && r.top1[it] }
        val losses = (0 until n).count { base.top1[it] && !r.top1[it] }
        val keep = gains - losses >= minGain && r.top1Count >= base.top1Count
        kept[name] = keep
        println("$name: top1 ${r.top1Count}/$n top3 ${r.top3Count}/$n fixed $gains broke $losses -> ${if (keep) "KEEP" else "drop"} (${r.millis / 1000}s)")
        rows += mapOf("model" to name, "top1" to r.top1Count, "top3" to r.top3Count, "fixed" to gains, "broke" to losses, "enabled" to keep, "seconds" to r.millis / 1000)
    }
    val all = Models(
        digits = available[Models.DIGITS]?.digits?.takeIf { kept[Models.DIGITS] == true },
        restore = available[Models.RESTORE]?.restore?.takeIf { kept[Models.RESTORE] == true },
        regions = available[Models.REGIONS]?.regions?.takeIf { kept[Models.REGIONS] == true },
    )
    val combined = if (all.names.isNotEmpty()) Bench.run(cases, all) else base
    println("kept ${all.names}: top1 ${combined.top1Count}/$n top3 ${combined.top3Count}/$n")
    val manifest = mapOf(
        "cases" to n,
        "rule" to "keep if (cases fixed - cases broken) >= $minGain and top-1 not lower than baseline",
        "baseline" to mapOf("top1" to base.top1Count, "top3" to base.top3Count),
        "combined" to mapOf("models" to all.names, "top1" to combined.top1Count, "top3" to combined.top3Count),
        "models" to rows.associate { it["model"] as String to it - "model" },
    )
    File(dir, "manifest.json").writeText(Json.write(manifest))
    val md = StringBuilder("# Model benchmark\n\nSynthetic damaged labels (rendered, physically damaged, photographed twice), ")
        .append("ZXing + partial Code 128 decoder + simulated OCR that fails where the ink is damaged. $n cases, top-1 = best candidate is the true number.\n\n")
        .append("| Configuration | Top-1 | Top-3 | Fixed | Broke | Kept |\n|---|---|---|---|---|---|\n")
        .append("| No models | ${base.top1Count}/$n | ${base.top3Count}/$n | | | |\n")
    for (r in rows) md.append("| + ${r["model"]} | ${r["top1"]}/$n | ${r["top3"]}/$n | ${r["fixed"]} | ${r["broke"]} | ${if (r["enabled"] == true) "yes" else "no"} |\n")
    md.append("| Kept models together | ${combined.top1Count}/$n | ${combined.top3Count}/$n | | | |\n\nRule: ${manifest["rule"]}.\n")
    File(dir, "BENCHMARK.md").writeText(md.toString())
    println(md)
}
