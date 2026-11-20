package com.labelrescue.core.fusion

import com.labelrescue.core.barcode.BarcodeDecoder
import com.labelrescue.core.barcode.BarcodeLocator
import com.labelrescue.core.barcode.BarcodeRead
import com.labelrescue.core.barcode.Code128Partial
import com.labelrescue.core.barcode.PartialResult
import com.labelrescue.core.barcode.SymbolEvidence
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.image.Affine
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.image.Variants
import com.labelrescue.core.ocr.OcrInterpreter
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.ocr.TextRecognizer
import com.labelrescue.core.usps.ImpbParser
import com.labelrescue.core.usps.ParsedNumber
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class Progress(val phase: String, val done: Int, val total: Int)

class CancelledException : RuntimeException("cancelled")

/**
 * Runs every layer on every frame and combines the evidence:
 * full barcode decodes (all decoders × all variants), the partial Code 128 decoder, DataMatrix
 * retries on many crops, and OCR of the printed number; then multi-frame consensus and fusion.
 * A full decode with a valid check digit ends the search immediately.
 */
class RecoveryPipeline(
    private val decoders: List<BarcodeDecoder>,
    private val text: TextRecognizer?,
    private val partialDecoder: Code128Partial = Code128Partial(),
) {
    data class Options(
        val barcodeCrop: NormRect? = null,
        val numberCrop: NormRect? = null,
        /** Process at most this many frames fully (all are tried for a quick full decode). */
        val maxFrames: Int = 16,
    )

    private class LocatedEvidence(val ev: SymbolEvidence, val frame: Int, val toFrame: Affine)

    private class Found(val parsed: ParsedNumber, val by: String)

    /** What one frame contributed. */
    private class FrameWork(
        val observations: MutableList<Observation> = ArrayList(),
        val evidence: MutableList<LocatedEvidence> = ArrayList(),
        var labelText: String = "",
        var variants: Int = 0,
        var ocrReads: Int = 0,
        var barcodeDecodes: Int = 0,
        var measurements: Int = 0,
    )

    private class FoundException(val found: Found, val labelText: String) : RuntimeException()

    fun run(
        frames: List<GrayImage>,
        opt: Options = Options(),
        progress: (Progress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): RecoveryOutput {
        val t0 = System.currentTimeMillis()
        var stats = PipelineStats(frames = frames.size)
        val notes = ArrayList<String>()
        fun check() { if (cancelled()) throw CancelledException() }
        fun done(f: Found, label: String): RecoveryOutput {
            val p = f.parsed
            val fmt = p.format.trackingFormat
            val slots = p.tracking.mapIndexed { i, c -> SlotDist.known(c, if (fmt.isLetterPosition(i)) SlotDist.LETTERS else SlotDist.DIGITS) }
            notes += "Full decode by ${f.by}; check digit valid."
            return RecoveryOutput(fmt, slots, emptyMap(), p, f.by, p.zip, label, null, emptyMap(), stats.copy(millis = System.currentTimeMillis() - t0), notes)
        }

        // Phase 1: quick full decode on every frame as-is.
        for ((fi, frame) in frames.withIndex()) {
            check()
            progress(Progress("Quick barcode scan", fi, frames.size))
            fullDecode(frame.limited(2400), "frame ${fi + 1}")?.let { return done(it, "") }
            stats = stats.copy(barcodeDecodes = stats.barcodeDecodes + decoders.size)
        }

        // Phase 2: every layer on every frame, frames in parallel.
        val work = frames.take(opt.maxFrames)
        val total = work.size
        val finished = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(threads)
        val results: List<FrameWork>
        try {
            val futures = work.mapIndexed { fi, frame ->
                pool.submit<FrameWork> {
                    val r = processFrame(fi, frame, opt) { stop.get() || cancelled() }
                    progress(Progress("Read ${finished.incrementAndGet()} of $total frames", finished.get(), total))
                    r
                }
            }
            progress(Progress("Reading $total frames", 0, total))
            val out = ArrayList<FrameWork>()
            for (fu in futures) {
                try {
                    out += fu.get()
                } catch (e: ExecutionException) {
                    when (val c = e.cause) {
                        is FoundException -> { stop.set(true); return done(c.found, c.labelText) }
                        is CancelledException -> { stop.set(true); throw c }
                        else -> throw (c ?: e)
                    }
                }
            }
            results = out
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
        check()
        progress(Progress("Combining evidence", total, total))

        val observations = results.flatMap { it.observations }
        val evidence = results.flatMap { it.evidence }
        val labelText = results.firstOrNull { it.labelText.isNotBlank() }?.labelText.orEmpty()
        stats = stats.copy(
            variants = results.sumOf { it.variants }, ocrReads = results.sumOf { it.ocrReads },
            barcodeDecodes = stats.barcodeDecodes + results.sumOf { it.barcodeDecodes },
            barcodeMeasurements = results.sumOf { it.measurements },
        )

        val partial: PartialResult? = partialDecoder.decode(evidence.map { it.ev })
        val format = Fusion.chooseFormat(observations, partial)
        stats = stats.copy(ocrObservations = observations.size, millis = System.currentTimeMillis() - t0)
        if (format == null) {
            notes += "No tracking number found. Try cropping to the number, more light, or type the digits."
            return RecoveryOutput.empty(labelText, stats, notes)
        }
        val ocr = Fusion.consensus(observations, format)
        val slots = Fusion.fuse(format, ocr, partial)
        val perSource = HashMap<Source, List<SlotDist?>>()
        if (observations.any { it.format == format }) perSource[Source.OCR] = ocr
        if (partial != null && partial.structure.trackingFormat == format) {
            perSource[Source.BARCODE_PARTIAL] = partial.trackingDigits
            notes += "Damaged barcode read as ${partial.structure.name}; ${partial.recovered.size} digits filled in or checked by the barcode checksums."
        }
        val locs = Fusion.locations(format, observations, partial) { p, t -> barcodeLocation(p, t, evidence) }
        val zip = partial?.zipDigits?.takeIf { z -> z.isNotEmpty() && z.all { it.bestP > 0.9 } }?.joinToString("") { it.best.toString() }
        notes += "${observations.count { it.format == format }} printed-number reads, ${evidence.size} barcode measurements."
        return RecoveryOutput(format, slots, perSource, null, null, zip, labelText, partial, locs, stats, notes)
    }

    /** All layers on one frame. Throws [FoundException] on a verified full decode. */
    private fun processFrame(fi: Int, frame: GrayImage, opt: Options, stopped: () -> Boolean): FrameWork {
        val w = FrameWork()
        fun check() { if (stopped()) throw CancelledException() }
        fun full(img: GrayImage, where: String) {
            w.barcodeDecodes += decoders.size
            fullDecode(img, where)?.let { throw FoundException(it, w.labelText) }
        }

        // OCR: whole frame first, to find the number line and the label text.
        val numberRegion = opt.numberCrop?.toPixels(frame.width, frame.height)
        val lineBoxes = ArrayList<IntRect>()
        if (text != null) {
            val ocrBase = Variants.make(frame, numberRegion, Variants.Options(maxSide = 2000, rotations = emptyList(), includeThreshold = false))
                .filter { it.name == "gray" || it.name == "clahe" }
            for (v in ocrBase) {
                check()
                val page = text.recognize(v.image)
                w.ocrReads++; w.variants++
                if (v.name == "gray") w.labelText = page.text
                w.observations += OcrInterpreter.interpret(page, v.toFrame, fi, v.name)
                for (b in OcrInterpreter.numberLineBoxes(page)) lineBoxes += v.toFrame.mapRect(b)
            }
        }

        // Barcode regions: user crop, located barcodes, the area just above the number line, the whole frame.
        val regions = ArrayList<Pair<IntRect, Boolean>>()
        opt.barcodeCrop?.let { regions += it.toPixels(frame.width, frame.height) to false }
        if (regions.isEmpty()) {
            for (r in BarcodeLocator.locate(frame)) regions += r.rect to r.vertical
            lineBoxes.firstOrNull()?.let { lb ->
                val lw = lb.width
                regions += IntRect(lb.left - lw / 6, lb.top - (lw * 0.45).toInt(), lb.right + lw / 6, lb.top + lb.height / 3) to false
            }
        }
        regions += IntRect(0, 0, frame.width, frame.height) to false
        for ((rect, vertical) in dedupe(regions, frame)) {
            check()
            val (img, outer) = regionImage(frame, rect, vertical)
            val whole = rect.width == frame.width && rect.height == frame.height
            val vs = Variants.make(img, null, Variants.Options(maxSide = 2400, upscaleBelow = 900))
                .filter { !whole || it.name == "gray" || it.name == "clahe" }
            for (v in vs) {
                check()
                w.variants++
                full(v.image, "frame ${fi + 1} ${v.name}")
                for (ev in partialDecoder.measure(v.image)) w.evidence += LocatedEvidence(ev, fi, v.toFrame.then(outer))
                w.measurements++
            }
        }

        // DataMatrix (and any barcode) on many crops: Reed-Solomon survives heavy damage.
        for (crop in tiles(frame)) {
            check()
            val vs = Variants.make(frame, crop, Variants.Options(maxSide = 1600, rotations = emptyList()))
                .filter { it.name in setOf("gray", "clahe", "threshold", "upscale2x", "upscale3x") }
            for (v in vs) { w.variants++; full(v.image, "frame ${fi + 1} crop ${v.name}") }
        }

        // OCR again, zoomed onto the number line, with every variant.
        if (text != null) {
            val lines = (lineBoxes.map { it.inflate(it.width / 12 + 6, it.height + 4) } + listOfNotNull(numberRegion)).take(2)
            for (lr in lines) {
                val r = lr.intersect(IntRect(0, 0, frame.width, frame.height))
                if (r.isEmpty) continue
                for (v in Variants.make(frame, r, Variants.Options(maxSide = 2400, upscaleBelow = 1600, rotations = listOf(-2.0, 2.0)))) {
                    check()
                    val page = text.recognize(v.image)
                    w.ocrReads++; w.variants++
                    w.observations += OcrInterpreter.interpret(page, v.toFrame, fi, v.name)
                }
            }
        }
        return w
    }

    private fun fullDecode(img: GrayImage, where: String): Found? {
        for (d in decoders) {
            val reads: List<BarcodeRead> = try { d.decode(img) } catch (e: Exception) { emptyList() }
            for (r in reads) {
                val found = ImpbParser.extractFromPayload(r.text).firstOrNull { it.checkValid }
                if (found != null) return Found(found, "${r.decoder} ${r.kind.name.lowercase().replace('_', ' ')} ($where)")
            }
        }
        return null
    }

    private fun regionImage(frame: GrayImage, rect: IntRect, vertical: Boolean): Pair<GrayImage, Affine> {
        val crop = frame.crop(rect)
        val toFrame = Affine.translate(rect.left.toDouble(), rect.top.toDouble())
        if (!vertical) return crop to toFrame
        val (rot, back) = crop.rotated90(1)
        return rot to back.then(toFrame)
    }

    private fun dedupe(regions: List<Pair<IntRect, Boolean>>, frame: GrayImage): List<Pair<IntRect, Boolean>> {
        val bounds = IntRect(0, 0, frame.width, frame.height)
        val out = ArrayList<Pair<IntRect, Boolean>>()
        for ((r0, v) in regions) {
            val r = r0.intersect(bounds)
            if (r.width < 24 || r.height < 8) continue
            val dup = out.any { (o, ov) ->
                val i = o.intersect(r)
                ov == v && !i.isEmpty && i.width.toLong() * i.height > 0.7 * maxOf(o.width.toLong() * o.height, r.width.toLong() * r.height)
            }
            if (!dup) out += r to v
        }
        return out.take(5)
    }

    /** Overlapping crops for 2D codes: quadrants and center, so small codes get more pixels. */
    private fun tiles(frame: GrayImage): List<IntRect> {
        val w = frame.width; val h = frame.height
        val tw = (w * 0.6).toInt(); val th = (h * 0.6).toInt()
        return listOf(
            IntRect(0, 0, tw, th), IntRect(w - tw, 0, w, th), IntRect(0, h - th, tw, h), IntRect(w - tw, h - th, w, h),
            IntRect((w - tw) / 2, (h - th) / 2, (w + tw) / 2, (h + th) / 2),
        )
    }

    private fun barcodeLocation(p: PartialResult, t: Int, evidence: List<LocatedEvidence>): Location? {
        val le = evidence.firstOrNull { it.ev === p.evidence } ?: return null
        val slot = Fusion.slotForTrackingDigit(p.structure, t)
        if (slot < 0) return null
        val ev = le.ev
        val x = ev.slotX.getOrNull(slot + 1) ?: return null
        val r = IntRect((x - ev.moduleWidth).toInt(), ev.yTop - 4, (x + 12 * ev.moduleWidth).toInt(), ev.yBottom + 4)
        return Location(le.frame, le.toFrame.mapRect(r), Source.BARCODE_PARTIAL)
    }
}
