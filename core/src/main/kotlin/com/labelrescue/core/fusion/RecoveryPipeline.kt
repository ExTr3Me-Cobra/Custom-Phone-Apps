package com.labelrescue.core.fusion

import com.labelrescue.core.privacy.Redactor
import com.labelrescue.core.ups.UpsNumber

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
        /** On a full decode, still read and locate the printed digits (training examples). */
        val learnFromIntact: Boolean = false,
        /** Skip the remaining frames once the ranked answer is settled (see [STOP_CONFIDENCE]). */
        val earlyStop: Boolean = true,
    )

    companion object {
        /** Frames processed before the first checkpoint. */
        const val MIN_FRAMES = 4
        /** Stop when two checkpoints in a row agree on the top answer and it has at least this confidence. */
        const val STOP_CONFIDENCE = 0.8
    }

    /** UPS 1Z numbers seen in this run (barcodes, MaxiCode, printed text). */
    private val upsFound = java.util.concurrent.ConcurrentHashMap<String, UpsNumber>()

    private class LocatedEvidence(val ev: SymbolEvidence, val frame: Int, val toFrame: Affine)

    private class Found(val parsed: ParsedNumber, val by: String)

    /** What one frame contributed. */
    /** An OCR page kept for the re-alignment pass. */
    private class PageRec(val page: OcrPage, val toFrame: Affine, val frame: Int, val variant: String, val source: Source)

    private class FrameWork(
        val frame: Int,
        val pages: MutableList<PageRec> = ArrayList(),
        /** Frame-0 full-page OCR lines in frame coordinates, for whiting out names and addresses. */
        val privacyLines: MutableList<Pair<com.labelrescue.core.ocr.OcrLine, IntRect?>> = ArrayList(),
        val observations: MutableList<Observation> = ArrayList(),
        val evidence: MutableList<LocatedEvidence> = ArrayList(),
        var labelText: String = "",
        var variants: Int = 0,
        var ocrReads: Int = 0,
        var barcodeDecodes: Int = 0,
        var measurements: Int = 0,
    )

    private class FoundException(val found: Found, val labelText: String, val frame: Int) : RuntimeException()

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
        upsFound.clear()
        fun withUps(o: RecoveryOutput): RecoveryOutput {
            val printed = UpsNumber.find(o.labelText, "printed text", allowCorrection = true)
            for (u in printed) upsFound.putIfAbsent(u.tracking, u)
            o.ups = upsFound.values.sortedBy { it.corrected }
            if (o.ups.isNotEmpty()) log?.line("UPS numbers: " + o.ups.joinToString { "${it.tracking} (${it.source}${if (it.corrected) ", corrected" else ""}${if (it.isUspsHandoff) ", Ground Saver/SurePost → USPS" else ""})" })
            return o
        }
        fun done(f: Found, label: String, fi: Int): RecoveryOutput {
            val p = f.parsed
            val fmt = p.format.trackingFormat
            val slots = p.tracking.mapIndexed { i, c -> SlotDist.known(c, if (fmt.isLetterPosition(i)) SlotDist.LETTERS else SlotDist.DIGITS) }
            notes += "Full decode by ${f.by}; check digit valid."
            log?.line("FULL DECODE ${p.raw} by ${f.by} (check digit valid) -> tracking ${p.tracking}")
            val frame = frames.getOrNull(fi)
            if (opt.learnFromIntact && text != null && frame != null && fmt != TrackingFormat.S10) {
                // Intact label: the barcode proved the number, so the printed digits are labeled
                // training examples. Read and line them up against the known number.
                progress(Progress("Reading the printed digits for training", 0, 1))
                val l = learnIntact(fi, frame, fmt, p.tracking)
                notes += "Learn mode: ${l.locations.size} of ${fmt.length} printed digits located for training."
                log?.line("learn from intact label: ${l.locations.size} digits located; reads: " + l.reads.joinToString(" | "))
                return withUps(RecoveryOutput(
                    fmt, slots, mapOf(Source.OCR to l.ocr), p, f.by, p.zip, listOf(label, l.labelText).filter { it.isNotBlank() }.joinToString("\n"),
                    null, l.locations, stats.copy(millis = System.currentTimeMillis() - t0), notes, log, l.reads, l.privacy, fi,
                ))
            }
            return withUps(RecoveryOutput(fmt, slots, emptyMap(), p, f.by, p.zip, label, null, emptyMap(), stats.copy(millis = System.currentTimeMillis() - t0), notes, log))
        }
        log?.section("Recovery")
        log?.line("frames: " + frames.joinToString { "${it.width}x${it.height}" })
        log?.line("options: barcodeCrop=${opt.barcodeCrop} numberCrop=${opt.numberCrop} maxFrames=${opt.maxFrames}")
        log?.line("decoders: ${decoders.size}, OCR: ${text != null}, 2nd OCR: ${text2 != null}, models: ${models.names}")

        // Phase 1: quick full decode on every frame as-is.
        for ((fi, frame) in frames.withIndex()) {
            check()
            progress(Progress("Quick barcode scan", fi, frames.size))
            fullDecode(frame.limited(2400), "frame ${fi + 1}")?.let { return done(it, "", fi) }
            stats = stats.copy(barcodeDecodes = stats.barcodeDecodes + decoders.size)
        }

        // Phase 2: every layer on every frame, sharpest first, in parallel. Checkpoints after every
        // couple of frames rank the evidence so far; once the answer is settled the rest are skipped.
        val work = frames.take(opt.maxFrames)
        val total = work.size
        val order = work.indices.sortedByDescending { com.labelrescue.core.image.Filters.sharpness(work[it]) }
        log?.line("processing order (sharpest first): " + order.joinToString { "f$it" })
        val finished = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(threads)
        val results = ArrayList<FrameWork>()
        val checkpoints = ArrayList<Checkpoint>()
        var stoppedEarly = false
        try {
            val futures = order.map { fi ->
                pool.submit<FrameWork> {
                    val r = processFrame(fi, work[fi], opt, collectPrivacy = fi == order[0]) { stop.get() || cancelled() }
                    progress(Progress("Read ${finished.incrementAndGet()} of $total frames", finished.get(), total))
                    r
                }
            }
            progress(Progress("Reading $total frames", 0, total))
            for (fu in futures) {
                try {
                    results += fu.get()
                } catch (e: ExecutionException) {
                    when (val c = e.cause) {
                        is FoundException -> { stop.set(true); return done(c.found, c.labelText, c.frame) }
                        is CancelledException -> { stop.set(true); throw c }
                        else -> throw (c ?: e)
                    }
                }
                val n = results.size
                if (n < total && n >= MIN_FRAMES && n % 2 == 0) {
                    val cp = checkpoint(n, results, work)
                    checkpoints += cp
                    val prev = checkpoints.getOrNull(checkpoints.size - 2)
                    if (opt.earlyStop && prev != null && cp.top != null && cp.top == prev.top && cp.confidence >= STOP_CONFIDENCE) {
                        stoppedEarly = true
                        stop.set(true)
                        log?.line("early stop after $n of $total frames: ${cp.top} ${(cp.confidence * 100).toInt()}% (same at ${prev.frames} frames)")
                        break
                    }
                }
            }
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
        check()
        progress(Progress("Combining evidence", total, total))
        stats = stats.copy(
            variants = results.sumOf { it.variants }, ocrReads = results.sumOf { it.ocrReads },
            barcodeDecodes = stats.barcodeDecodes + results.sumOf { it.barcodeDecodes },
            barcodeMeasurements = results.sumOf { it.measurements },
        )
        val out = withUps(combine(results, work, stats, t0, log, notes).also { o ->
            // The number-line reads too: a UPS 1Z line is often read there and not in the page text.
            val lineText = results.flatMap { it.pages }.joinToString("\n") { it.page.text }
            for (u in UpsNumber.find(lineText, "printed text", allowCorrection = true)) upsFound.putIfAbsent(u.tracking, u)
        })
        if (!stoppedEarly && out.format != null) {
            val r = rankQuick(out)
            checkpoints += Checkpoint(results.size, r?.best?.tracking, r?.best?.confidence ?: 0.0)
        }
        out.framesUsed = results.size
        out.framesStoppedEarly = stoppedEarly
        out.checkpoints = checkpoints
        log?.line("checkpoints: " + checkpoints.joinToString { "${it.frames}f=${it.top} ${(it.confidence * 100).toInt()}%" })
        return out
    }

    private class Learned(
        val locations: Map<Int, Location>, val ocr: List<SlotDist?>, val reads: List<String>,
        val labelText: String, val privacy: List<IntRect>?,
    )

    /**
     * Reads the printed number of a label whose barcode decoded, aligned to the known number, and
     * keeps for each position the clearest box where OCR read exactly the known digit.
     */
    private fun learnIntact(fi: Int, frame: GrayImage, fmt: TrackingFormat, tracking: String): Learned {
        val tr = text ?: return Learned(emptyMap(), emptyList(), emptyList(), "", null)
        val known = tracking.mapIndexed { i, c -> SlotDist.known(c, if (fmt.isLetterPosition(i)) SlotDist.LETTERS else SlotDist.DIGITS) }
        val ref = OcrInterpreter.Reference(fmt, known)
        val obs = ArrayList<Observation>()
        val privacyLines = ArrayList<Pair<com.labelrescue.core.ocr.OcrLine, IntRect?>>()
        val lineBoxes = ArrayList<IntRect>()
        var labelText = ""
        for (v in Variants.make(frame, null, Variants.Options(maxSide = 2000, rotations = emptyList(), includeThreshold = false)).filter { it.name == "gray" }) {
            val page = tr.recognize(v.image)
            labelText = page.text
            for (l in page.lines) privacyLines += l to l.box?.let { v.toFrame.mapRect(it) }
            obs += OcrInterpreter.interpret(page, v.toFrame, fi, v.name, Source.OCR, ref)
            for (b in OcrInterpreter.numberLineBoxes(page)) lineBoxes += v.toFrame.mapRect(b)
        }
        for (lb in lineBoxes.take(2)) {
            val r = lb.inflate(lb.width / 12 + 6, lb.height + 4).intersect(IntRect(0, 0, frame.width, frame.height))
            if (r.isEmpty) continue
            for (v in Variants.make(frame, r, Variants.Options(maxSide = 2400, upscaleBelow = 1600, rotations = emptyList(), includeThreshold = false))
                .filter { it.name == "gray" || it.name == "clahe" }) {
                obs += OcrInterpreter.interpret(tr.recognize(v.image), v.toFrame, fi, v.name, Source.OCR, ref)
            }
        }
        val same = obs.filter { it.format == fmt }
        val locations = HashMap<Int, Location>()
        for (i in tracking.indices) {
            var best: Observation? = null; var bestScore = 0.0
            for (o in same) {
                val b = o.boxes.getOrNull(i) ?: continue
                if (b.isEmpty || o.slots[i]?.best != tracking[i]) continue
                val sc = o.weight * o.boxQuality[i] * (o.slots[i]?.bestP ?: 0.0)
                if (sc > bestScore) { bestScore = sc; best = o }
            }
            if (best != null) locations[i] = Location(fi, best.boxes[i]!!, Source.OCR)
        }
        val reads = same.map { "${it.source}/${it.variant}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } }
        return Learned(locations, if (same.isNotEmpty()) Fusion.consensus(same, fmt) else emptyList(), reads, labelText,
            Redactor.blankBoxes(privacyLines, frame.width, frame.height))
    }

    /** Ranked answer after the first [frames] processed frames (for early stop and the training report). */
    class Checkpoint(val frames: Int, val top: String?, val confidence: Double)

    private fun checkpoint(n: Int, results: List<FrameWork>, work: List<GrayImage>): Checkpoint {
        val out = combine(results.toList(), work, PipelineStats(), System.currentTimeMillis(), null, ArrayList())
        val r = if (out.format != null) rankQuick(out) else null
        return Checkpoint(n, r?.best?.tracking, r?.best?.confidence ?: 0.0)
    }

    /** The app's ranking without the learned history prior (which the core doesn't have). */
    private fun rankQuick(out: RecoveryOutput) = runCatching {
        com.labelrescue.core.candidates.CandidateEngine(beam = 400, maxResults = 20).rank(
            out.format!!, out.slots,
            com.labelrescue.core.candidates.StructurePrior(out.serviceHints, listOfNotNull(out.zip) + com.labelrescue.core.usps.ServiceHints.zips(out.labelText)),
            out.exactEvidence(),
        )
    }.getOrNull()

    /** Combines the evidence of [results] into one output. [log] null for a quiet checkpoint. */
    private fun combine(
        results: List<FrameWork>, work: List<GrayImage>, stats0: PipelineStats, t0: Long,
        log: DiagnosticLog?, notes: MutableList<String>,
    ): RecoveryOutput {
        var stats = stats0
        val observations = results.flatMap { it.observations }
        val evidence = results.flatMap { it.evidence }
        // Label text from every frame (distinct lines): one frame often misses the service banner.
        val labelText = results.flatMap { it.labelText.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().joinToString("\n")

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
        var ocr = Fusion.consensus(ocrObs, format)
        var ocr2 = if (ocr2Obs.any { it.format == format }) Fusion.consensus(ocr2Obs, format) else emptyList()
        var digitModel: List<SlotDist?> = emptyList()
        var slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, Fusion.unanimous(ocrObs + ocr2Obs, format))
        // Re-align every OCR read against the fused evidence (twice): reads that dropped a digit
        // stop shifting the rest, and the consensus sharpens.
        val pages = results.flatMap { it.pages }
        var finalReads: List<String> = (ocrObs + ocr2Obs).filter { it.format == format }
            .map { "${it.source}/${it.variant}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } }
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
            slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, Fusion.unanimous(ocrObs + ocr2Obs, format))
            finalReads = again.map { "${it.source}/${it.variant}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } }
            log?.line("re-alignment pass $pass: " + finalReads.joinToString(" | "))
        }
        // Digit classifier (optional), on the re-aligned ML Kit glyph boxes only (first-pass boxes
        // can be shifted by a dropped digit), and only boxes fully inside the frame: a cut-off
        // digit at the edge gets classified as something confidently wrong.
        val digits = models.digits
        if (digits != null && format != TrackingFormat.S10) {
            val modelObs = ocrObs.filter { it.format == format }.groupBy { it.frame }.mapNotNull { (fi, os) ->
                val frame = work.getOrNull(fi) ?: return@mapNotNull null
                val o = os.maxBy { o -> o.weight * o.boxes.count { it != null } }
                val m = 2
                val ds = o.boxes.map { b ->
                    b?.takeIf { !it.isEmpty && it.left >= m && it.top >= m && it.right <= frame.width - m && it.bottom <= frame.height - m }
                        ?.let { SlotDist(SlotDist.DIGITS, digits.classify(frame, it)) }
                }
                if (ds.count { it != null } >= format.length / 2) Observation(Source.DIGIT_MODEL, format, ds, 0.5, fi, "digit-model", o.boxes, o.boxQuality) else null
            }
            if (modelObs.isNotEmpty()) {
                digitModel = Fusion.consensus(modelObs, format)
                slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, Fusion.unanimous(ocrObs + ocr2Obs, format))
                log?.line("digit model: " + modelObs.joinToString(" | ") { "f${it.frame}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } })
            }
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
        val zip = partial?.zipDigits?.takeIf { z -> z.isNotEmpty() && z.all { it.bestP >= 0.5 } }?.joinToString("") { it.best.toString() }
        notes += "${ocrObs.count { it.format == format }} printed-number reads, ${evidence.size} barcode measurements" +
            (if (models.names.isNotEmpty()) "; models: ${models.names.joinToString()}" else "") + "."
        log?.line("barcode layout used: ${partial?.structure?.name ?: "none"}; zip=$zip (zip digits " +
            partial?.zipDigits.orEmpty().joinToString(" ") { "${it.best}:${(it.bestP * 100).toInt()}" } + ")")
        log?.positions(format, perSource, slots)
        log?.line("label service: ${com.labelrescue.core.usps.ServiceHints.explain(labelText)}; stats: $stats")
        val pf = results.firstOrNull()?.frame ?: 0
        val privacy = results.firstOrNull()?.let { Redactor.blankBoxes(it.privacyLines, work[pf].width, work[pf].height) }
        log?.line("privacy: " + (privacy?.let { "${it.size} text areas whited out on frame ${pf + 1}" } ?: "no safe photo (no OCR line positions)"))
        return RecoveryOutput(format, slots, perSource, null, null, zip, labelText, partial, locs, stats, notes, log, finalReads, privacy, pf)
    }

    private val restorer: ((GrayImage) -> GrayImage)? = models.restore?.let { m -> { img: GrayImage -> m.restore(img) } }

    /** All layers on one frame. Throws [FoundException] on a verified full decode. */
    private fun processFrame(fi: Int, frame: GrayImage, opt: Options, collectPrivacy: Boolean, stopped: () -> Boolean): FrameWork {
        val w = FrameWork(fi)
        fun check() { if (stopped()) throw CancelledException() }
        fun full(img: GrayImage, where: String) {
            w.barcodeDecodes += decoders.size
            fullDecode(img, where)?.let { throw FoundException(it, w.labelText, fi) }
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
                if (collectPrivacy) for (l in page.lines) w.privacyLines += l to l.box?.let { v.toFrame.mapRect(it) }
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
        log?.line("f$fi 2D codes: full decoders found no valid tracking number in ${tiles(frame).size} crops")

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

        return w
    }

    private fun fullDecode(img: GrayImage, where: String): Found? {
        for (d in decoders) {
            val reads: List<BarcodeRead> = try { d.decode(img) } catch (e: Exception) { emptyList() }
            log?.let { l -> if (reads.isNotEmpty()) l.line("decode $where: " + reads.joinToString { "${it.decoder}/${it.kind}: \"${it.text.replace("\u001D", "<GS>")}\"" }) }
            for (r in reads) {
                for (u in UpsNumber.find(r.text, "${r.decoder} barcode") + UpsNumber.fromMaxiCode(r.text)) upsFound.putIfAbsent(u.tracking, u)
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
