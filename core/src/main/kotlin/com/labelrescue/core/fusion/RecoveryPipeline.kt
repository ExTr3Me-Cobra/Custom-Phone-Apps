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
import com.labelrescue.core.ml.DetectedRegion
import com.labelrescue.core.ml.Models
import com.labelrescue.core.usps.TrackingFormat
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
    /** Optional on-device models (digit classifier, restoration, region detector). */
    private val models: Models = Models.NONE,
    /** Optional second OCR engine for the number line (PaddleOCR); fused as its own source. */
    private val text2: TextRecognizer? = null,
    /** Optional detailed log for development. */
    private val log: DiagnosticLog? = null,
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
    /** An OCR page kept for the re-alignment pass. */
    private class PageRec(val page: OcrPage, val toFrame: Affine, val frame: Int, val variant: String, val source: Source)

    private class FrameWork(
        val pages: MutableList<PageRec> = ArrayList(),
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
            log?.line("FULL DECODE ${p.raw} by ${f.by} (check digit valid) -> tracking ${p.tracking}")
            return RecoveryOutput(fmt, slots, emptyMap(), p, f.by, p.zip, label, null, emptyMap(), stats.copy(millis = System.currentTimeMillis() - t0), notes, log)
        }
        log?.section("Recovery")
        log?.line("frames: " + frames.joinToString { "${it.width}x${it.height}" })
        log?.line("options: barcodeCrop=${opt.barcodeCrop} numberCrop=${opt.numberCrop} maxFrames=${opt.maxFrames}")
        log?.line("decoders: ${decoders.size}, OCR: ${text != null}, 2nd OCR: ${text2 != null}, models: ${models.names}")

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

        val partials = partialDecoder.decodeAll(evidence.map { it.ev })
        log?.partials(partials)
        val format = Fusion.chooseFormat(observations, partials)
        log?.formatVote(Fusion.formatScores(observations, partials), format)
        val partial: PartialResult? = format?.let { Fusion.partialFor(it, partials) }
        stats = stats.copy(ocrObservations = observations.size, millis = System.currentTimeMillis() - t0)
        if (format == null) {
            notes += "No tracking number found. Try cropping to the number, more light, or type the digits."
            return RecoveryOutput.empty(labelText, stats, notes, log)
        }
        var ocrObs = observations.filter { it.source == Source.OCR }
        var ocr2Obs = observations.filter { it.source == Source.OCR2 }
        val modelObs = observations.filter { it.source == Source.DIGIT_MODEL }
        var ocr = Fusion.consensus(ocrObs, format)
        var ocr2 = if (ocr2Obs.any { it.format == format }) Fusion.consensus(ocr2Obs, format) else emptyList()
        val digitModel = if (modelObs.any { it.format == format }) Fusion.consensus(modelObs, format) else emptyList()
        var slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2)
        // Re-align every OCR read against the fused evidence (twice): reads that dropped a digit
        // stop shifting the rest, and the consensus sharpens.
        val pages = results.flatMap { it.pages }
        // The barcode's digits can't be shifted, so when there is a barcode reading it alone is the
        // alignment reference on the first pass (the first fused result already contains the
        // shifted reads and would reinforce them).
        val barcodeRef = partial?.takeIf { it.structure.trackingFormat == format }?.trackingDigits
        if (pages.isNotEmpty()) for (pass in 1..2) {
            val refSlots = if (pass == 1 && barcodeRef != null) barcodeRef else slots
            val again = pages.flatMap { pr ->
                val ref = OcrInterpreter.Reference(format, refSlots, xWeight = if (pr.source == Source.OCR2) 0.35 else 1.0)
                OcrInterpreter.interpret(pr.page, pr.toFrame, pr.frame, pr.variant, pr.source, ref)
            }
            ocrObs = again.filter { it.source == Source.OCR }
            ocr2Obs = again.filter { it.source == Source.OCR2 }
            ocr = Fusion.consensus(ocrObs, format)
            ocr2 = if (ocr2Obs.isNotEmpty()) Fusion.consensus(ocr2Obs, format) else emptyList()
            slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2)
            log?.line("re-alignment pass $pass: " + again.joinToString(" | ") { "${it.source}/${it.variant}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } })
        }
        val perSource = HashMap<Source, List<SlotDist?>>()
        if (ocrObs.any { it.format == format }) perSource[Source.OCR] = ocr
        if (ocr2.isNotEmpty()) perSource[Source.OCR2] = ocr2
        if (digitModel.isNotEmpty()) perSource[Source.DIGIT_MODEL] = digitModel
        if (partial != null && partial.structure.trackingFormat == format) {
            perSource[Source.BARCODE_PARTIAL] = partial.trackingDigits
            notes += "Damaged barcode read as ${partial.structure.name}; ${partial.recovered.size} digits filled in or checked by the barcode checksums."
        }
        val locs = Fusion.locations(format, ocrObs + observations.filter { it.source == Source.OCR }, partial) { p, t -> barcodeLocation(p, t, evidence) }
        val zip = partial?.zipDigits?.takeIf { z -> z.isNotEmpty() && z.all { it.bestP > 0.9 } }?.joinToString("") { it.best.toString() }
        notes += "${ocrObs.count { it.format == format }} printed-number reads, ${evidence.size} barcode measurements" +
            (if (models.names.isNotEmpty()) "; models: ${models.names.joinToString()}" else "") + "."
        log?.line("barcode layout used: ${partial?.structure?.name ?: "none"}; zip=$zip")
        log?.positions(format, perSource, slots)
        log?.line("label service: ${com.labelrescue.core.usps.ServiceHints.explain(labelText)}; stats: $stats")
        return RecoveryOutput(format, slots, perSource, null, null, zip, labelText, partial, locs, stats, notes, log)
    }

    private val restorer: ((GrayImage) -> GrayImage)? = models.restore?.let { m -> { img: GrayImage -> m.restore(img) } }

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
                if (v.name == "gray") { w.labelText = page.text; log?.line("f$fi label text:\n" + page.text) }
                val obs = OcrInterpreter.interpret(page, v.toFrame, fi, v.name)
                w.observations += obs
                w.pages += PageRec(page, v.toFrame, fi, v.name, Source.OCR)
                log?.ocr(fi, "ML Kit full ${v.name}", page, obs)
                for (b in OcrInterpreter.numberLineBoxes(page)) lineBoxes += v.toFrame.mapRect(b)
            }
        }

        // Region detector (optional): barcode, number text, ZIPs and service banner.
        val detected = if (models.regions != null && opt.barcodeCrop == null) models.regions.detect(frame) else emptyList()
        for (d in detected.filter { it.kind == DetectedRegion.Kind.TRACKING_TEXT }.take(2)) lineBoxes += d.box
        if (text != null) {
            // Re-read the ZIP and service banner areas zoomed in, for the label context.
            val extra = StringBuilder()
            for (d in detected.filter { it.kind == DetectedRegion.Kind.ZIP || it.kind == DetectedRegion.Kind.SERVICE_BANNER }.take(4)) {
                val r = d.box.inflate(d.box.width / 10 + 4, d.box.height / 3 + 4).intersect(IntRect(0, 0, frame.width, frame.height))
                if (r.isEmpty) continue
                val crop = frame.crop(r).let { if (it.height < 60) it.scaledBy(2.0) else it }
                extra.append('\n').append(text.recognize(crop).text)
                w.ocrReads++
            }
            if (extra.isNotEmpty()) w.labelText += extra.toString()
        }

        // Barcode regions: user crop, detected/located barcodes, the area above the number line, the whole frame.
        val regions = ArrayList<Pair<IntRect, Boolean>>()
        opt.barcodeCrop?.let { regions += it.toPixels(frame.width, frame.height) to false }
        for (d in detected.filter { it.kind == DetectedRegion.Kind.BARCODE }.take(2)) {
            val b = d.box
            regions += b.inflate(b.width / 8 + 8, b.height / 4 + 4) to (b.height > b.width * 1.5)
        }
        if (opt.barcodeCrop == null) {
            for (r in BarcodeLocator.locate(frame)) regions += r.rect to r.vertical
            lineBoxes.firstOrNull()?.let { lb ->
                val lw = lb.width
                regions += IntRect(lb.left - lw / 6, lb.top - (lw * 0.45).toInt(), lb.right + lw / 6, lb.top + lb.height / 3) to false
            }
        }
        regions += IntRect(0, 0, frame.width, frame.height) to false
        val chosenRegions = dedupe(regions, frame)
        log?.line("f$fi barcode regions: " + chosenRegions.joinToString { "${it.first}${if (it.second) " vertical" else ""}" } +
            (if (detected.isNotEmpty()) "; detector: " + detected.joinToString { "${it.kind}@${it.box}" } else ""))
        for ((rect, vertical) in chosenRegions) {
            check()
            val (img, outer) = regionImage(frame, rect, vertical)
            val whole = rect.width == frame.width && rect.height == frame.height
            val vs = Variants.make(img, null, Variants.Options(maxSide = 2400, upscaleBelow = 900, restore = if (whole) null else restorer))
                .filter { !whole || it.name == "gray" || it.name == "clahe" }
            for (v in vs) {
                check()
                w.variants++
                full(v.image, "frame ${fi + 1} ${v.name}")
                val ms = partialDecoder.measure(v.image)
                log?.measurements(fi, "$rect ${v.name}", ms)
                for (ev in ms) w.evidence += LocatedEvidence(ev, fi, v.toFrame.then(outer))
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

        // OCR again, zoomed onto the number line, with every variant (and the second engine).
        if (text != null || text2 != null) {
            val lines = (lineBoxes.map { it.inflate(it.width / 12 + 6, it.height + 4) } + listOfNotNull(numberRegion)).take(2)
            for (lr in lines) {
                val r = lr.intersect(IntRect(0, 0, frame.width, frame.height))
                if (r.isEmpty) continue
                for (v in Variants.make(frame, r, Variants.Options(maxSide = 2400, upscaleBelow = 1600, rotations = listOf(-2.0, 2.0), restore = restorer))) {
                    check()
                    w.variants++
                    if (text != null) {
                        val page = text.recognize(v.image)
                        w.ocrReads++
                        val obs = OcrInterpreter.interpret(page, v.toFrame, fi, v.name)
                        w.observations += obs
                        w.pages += PageRec(page, v.toFrame, fi, v.name, Source.OCR)
                        log?.ocr(fi, "ML Kit line ${v.name}", page, obs)
                    }
                    if (text2 != null) {
                        val page = text2.recognize(v.image)
                        w.ocrReads++
                        val obs = OcrInterpreter.interpret(page, v.toFrame, fi, "paddle-${v.name}", Source.OCR2)
                        w.observations += obs
                        w.pages += PageRec(page, v.toFrame, fi, "paddle-${v.name}", Source.OCR2)
                        log?.ocr(fi, "PaddleOCR line ${v.name}", page, obs)
                    }
                }
            }
        }

        // Digit classifier (optional): classify every position's crop, including ones OCR missed
        // (their boxes are estimated from the pitch), using the best OCR read of each format.
        val digits = models.digits
        if (digits != null) {
            // Only ML Kit's glyph boxes: PaddleOCR's positions are CTC time spans, too rough to crop.
            val best = w.observations.filter { it.source == Source.OCR && it.format != TrackingFormat.S10 }
                .groupBy { it.format }.mapValues { (_, os) -> os.maxBy { o -> o.weight * o.boxes.count { it != null } } }
            for ((format, o) in best) {
                val slots = o.boxes.map { b -> b?.takeIf { !it.isEmpty }?.let { SlotDist(SlotDist.DIGITS, digits.classify(frame, it)) } }
                if (slots.count { it != null } >= format.length / 2) {
                    w.observations += Observation(Source.DIGIT_MODEL, format, slots, 0.7, fi, "digit-model", o.boxes, o.boxQuality)
                }
            }
        }
        return w
    }

    private fun fullDecode(img: GrayImage, where: String): Found? {
        for (d in decoders) {
            val reads: List<BarcodeRead> = try { d.decode(img) } catch (e: Exception) { emptyList() }
            log?.let { l -> if (reads.isNotEmpty()) l.line("decode $where: " + reads.joinToString { "${it.decoder}/${it.kind}: \"${it.text.replace("\u001D", "<GS>")}\"" }) }
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
