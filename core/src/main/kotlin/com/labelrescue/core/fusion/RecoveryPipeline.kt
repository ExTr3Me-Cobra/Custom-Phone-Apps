package com.labelrescue.core.fusion

import com.labelrescue.core.privacy.Redactor
import com.labelrescue.core.ups.UpsNumber

import com.labelrescue.core.barcode.BarcodeDecoder
import com.labelrescue.core.barcode.BarcodeBounds
import com.labelrescue.core.barcode.BarcodeLocator
import com.labelrescue.core.image.DeadLines
import com.labelrescue.core.barcode.BarcodeRead
import com.labelrescue.core.barcode.Code128Partial
import com.labelrescue.core.barcode.DataMatrixRescue
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
import kotlin.math.abs
import kotlin.math.max
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
        /** Always use the lighter mode (tests: what it costs in accuracy). */
        val forceLite: Boolean = false,
        /** Barcode positions learned from intact labels (null: the built-in layout rules only). */
        val layout: com.labelrescue.core.usps.LearnedLayout? = null,
    )

    companion object {
        /**
         * Read dead print-head columns as unknown in the barcode. Off: on the real labels it gained
         * nothing over the gap-closing passes (and a mask wider than the line itself cost the one
         * label it fired on its answer). Kept for comparison runs (LR_DEAD_MASK=1).
         */
        @JvmStatic var DEAD_MASK = System.getenv("LR_DEAD_MASK") != null
        /** Pixels of blur masked either side of a dead line in the barcode. */
        @JvmStatic var MASK_BLUR = System.getenv("LR_MASK_BLUR")?.toDouble() ?: 0.0
        /** Discount printed digits a dead print-head line crosses (a switch for comparison runs). */
        @JvmStatic var DEAD_DIGITS = System.getenv("LR_NO_DEAD_DIGITS") == null
        /** Score damaged digits against the label's own clear digits (a switch for comparison runs). */
        @JvmStatic var SELF_TEMPLATES = System.getenv("LR_NO_SELF") == null
        /** Printed 1/7 reads need the digit model's agreement (a switch for comparison runs). */
        @JvmStatic var STROKE_CHECK = System.getenv("LR_NO_STROKE") == null
        /** Re-read DataMatrix codes ZXing couldn't, with damaged codewords as erasures (a switch for comparison runs). */
        @JvmStatic var DATAMATRIX_RESCUE = System.getenv("LR_NO_DM_RESCUE") == null && System.getenv("LR_NO_FULL") == null
        /** Tests only: never take a full decode, so intact labels exercise the recovery too. */
        @JvmStatic var NO_FULL_DECODE = System.getenv("LR_NO_FULL") != null
        /** Frames processed before the first checkpoint. */
        const val MIN_FRAMES = 4
        /** A scan projected to take longer than this (a slow phone) switches to the lighter mode. */
        const val SLOW_BUDGET_MS = 25_000L
        /** 2D-code retries on crops: only the sharpest few frames (a code that won't decode there won't on blurrier ones). */
        const val TILE_FRAMES = 3
        /** Image variants kept in the lighter mode. */
        val LITE_VARIANTS = setOf("gray", "clahe", "threshold", "upscale2x", "upscale3x")
        /** Stop when two checkpoints in a row agree on the top answer and it has at least this confidence. */
        const val STOP_CONFIDENCE = 0.8
        /** Image variants of the quick read of the printed number. */
        val QUICK_VARIANTS = setOf("gray", "clahe", "sharpen", "upscale2x", "upscale3x")
        /** The quick read stops the scan when at least this many reads agree exactly. */
        const val QUICK_READS = 5
        /** Each digit's probability after a clear quick read. */
        const val QUICK_DIGIT = 0.99
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
        /** Not read: past the adaptive frame limit on a slow phone. */
        val skipped: Boolean = false,
        val pages: MutableList<PageRec> = ArrayList(),
        /** Frame-0 full-page OCR lines in frame coordinates, for whiting out names and addresses. */
        val privacyLines: MutableList<Pair<com.labelrescue.core.ocr.OcrLine, IntRect?>> = ArrayList(),
        val observations: MutableList<Observation> = ArrayList(),
        val evidence: MutableList<LocatedEvidence> = ArrayList(),
        var labelText: String = "",
        /** Dead print-head columns found at the barcode (frame coordinates), for the printed digits too. */
        var deadLines: List<DeadLines.Line> = emptyList(),
        /** The barcode's bars (left, right) where [deadLines] were found: lines are compared across frames relative to them. */
        var deadBars: Pair<Int, Int>? = null,
        /** True once dead columns were looked for (found at the barcode or from the number line). */
        @Volatile var deadChecked: Boolean = false,
        var variants: Int = 0,
        var ocrReads: Int = 0,
        var barcodeDecodes: Int = 0,
        var measurements: Int = 0,
        /** Where the UPS and the USPS barcode sit on this frame, from the printed text. */
        var zones: com.labelrescue.core.usps.LabelZones = com.labelrescue.core.usps.LabelZones.NONE,
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
            val area = frame?.let { mainBarcode(it)?.let { r -> Location(fi, r, Source.BARCODE) } }
            val oneD = f.by.contains("code 128")
            fun mark(o: RecoveryOutput) = o.also { it.barcodeArea = area; it.barcodeRead = oneD }
            if (opt.learnFromIntact && text != null && frame != null && fmt != TrackingFormat.S10) {
                // Intact label: the barcode proved the number, so the printed digits are labeled
                // training examples. Read and line them up against the known number.
                progress(Progress("Reading the printed digits for training", 0, 1))
                val l = learnIntact(fi, frame, fmt, p.tracking)
                notes += "Learn mode: ${l.locations.size} of ${fmt.length} printed digits located for training."
                log?.line("learn from intact label: ${l.locations.size} digits located; reads: " + l.reads.joinToString(" | "))
                val layout = learnLayout(frame, p, l)
                return mark(withUps(RecoveryOutput(
                    fmt, slots, mapOf(Source.OCR to l.ocr), p, f.by, p.zip, listOf(label, l.labelText).filter { it.isNotBlank() }.joinToString("\n"),
                    null, l.locations, stats.copy(millis = System.currentTimeMillis() - t0), notes, log, l.reads, l.privacy, fi,
                ))).also { it.layoutSamples = layout }
            }
            return mark(withUps(RecoveryOutput(fmt, slots, emptyMap(), p, f.by, p.zip, label, null, emptyMap(), stats.copy(millis = System.currentTimeMillis() - t0), notes, log)))
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
        // Only a frame clearly bigger than the rest (the burst's full-resolution photo) counts.
        val sizes = work.map { it.width.toLong() * it.height }.sorted()
        largestPixels = if (sizes.isNotEmpty() && sizes.last() > 1.5 * sizes[sizes.size / 2]) sizes.last() else 0L
        val total = work.size
        val order = work.indices.sortedByDescending { com.labelrescue.core.image.Filters.sharpness(work[it]) }
        log?.line("processing order (sharpest first): " + order.joinToString { "f$it" })
        // Quick read: a clearly printed number needs no barcode work. The printed number alone, on
        // the two sharpest frames; if enough reads agree exactly (and the check digit holds), done.
        if (opt.earlyStop && text != null && opt.barcodeCrop == null) {
            progress(Progress("Reading the printed number", 0, 1))
            quickRead(work, order.take(2), opt, stats, t0, notes)?.let { return withUps(it) }
        }

        val finished = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(threads)
        val results = ArrayList<FrameWork>()
        val checkpoints = ArrayList<Checkpoint>()
        var stoppedEarly = false
        // Adaptive: after the first frame, estimate the whole scan's time. On a slow phone, the
        // remaining frames use the lighter mode and fewer of them are read.
        val lite = AtomicBoolean(opt.forceLite)
        val frameLimit = AtomicInteger(total)
        val phase2Start = System.currentTimeMillis()
        try {
            val futures = order.mapIndexed { rank, fi ->
                pool.submit<FrameWork> {
                    if (rank >= frameLimit.get()) return@submit FrameWork(fi, skipped = true)
                    val r = processFrame(fi, work[fi], opt, collectPrivacy = fi == order[0], rank = rank, lite = lite.get()) { stop.get() || cancelled() }
                    progress(Progress("Read ${finished.incrementAndGet()} of ${frameLimit.get()} frames", finished.get(), frameLimit.get()))
                    r
                }
            }
            progress(Progress("Reading $total frames", 0, total))
            for (fu in futures) {
                try {
                    val got = fu.get()
                    if (got.skipped) continue
                    results += got
                    if (results.size == 1 && total > 1) {
                        val first = System.currentTimeMillis() - phase2Start
                        val projected = first * ((total + threads - 1) / threads)
                        if (projected > SLOW_BUDGET_MS) {
                            lite.set(true)
                            // The lighter mode takes about half as long per frame.
                            val affordable = (SLOW_BUDGET_MS / (first * 0.5) * threads).toInt()
                            frameLimit.set(affordable.coerceIn(minOf(total, MIN_FRAMES), total))
                            log?.line("adaptive: first frame took ${first} ms (whole scan ~${projected / 1000} s): lighter mode, ${frameLimit.get()} of $total frames")
                        } else log?.line("adaptive: first frame took ${first} ms (whole scan ~${projected / 1000} s): full mode")
                    }
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
        // The damaged barcode the partial decoder read (else the widest barcode-shaped area found).
        out.barcodeArea = out.partial?.let { p -> results.flatMap { it.evidence }.firstOrNull { it.ev === p.evidence } }?.let { le ->
            val ev = le.ev
            val x0 = ev.slotX.firstOrNull() ?: 0.0; val x1 = ev.slotX.lastOrNull() ?: 0.0
            val r = IntRect((x0 - 12 * ev.moduleWidth).toInt(), ev.yTop - 6, (x1 + 26 * ev.moduleWidth).toInt(), ev.yBottom + 6)
            Location(le.frame, le.toFrame.mapRect(r), Source.BARCODE_PARTIAL)
        } ?: results.firstOrNull()?.let { w -> mainBarcode(work[w.frame], w.zones)?.let { Location(w.frame, it, Source.BARCODE) } }
        out.framesStoppedEarly = stoppedEarly
        out.checkpoints = checkpoints
        log?.line("checkpoints: " + checkpoints.joinToString { "${it.frames}f=${it.top} ${(it.confidence * 100).toInt()}%" })
        return out
    }

    private class Learned(
        val locations: Map<Int, Location>, val ocr: List<SlotDist?>, val reads: List<String>,
        val labelText: String, val privacy: List<IntRect>?,
        val lines: List<Pair<com.labelrescue.core.ocr.OcrLine, IntRect?>> = emptyList(),
        val numberLines: List<IntRect> = emptyList(),
    )

    /**
     * Learn mode: finds exactly which barcode on the intact label holds the USPS number (and which
     * the UPS 1Z number), by decoding each barcode-shaped area on its own, and measures where they
     * sit relative to the printed text. Those positions guide the search on damaged labels.
     */
    private fun learnLayout(frame: GrayImage, p: ParsedNumber, l: Learned): List<com.labelrescue.core.usps.LayoutSample> {
        val anchors = com.labelrescue.core.usps.LabelZones.anchors(l.lines, l.numberLines)
        if (anchors.number == null && anchors.uspsLine == null && anchors.upsLine == null) {
            log?.line("layout: no printed anchors found, nothing learned"); return emptyList()
        }
        val bounds = IntRect(0, 0, frame.width, frame.height)
        var uspsBar: IntRect? = null; var upsBar: IntRect? = null
        for (r in BarcodeLocator.locate(frame, 8).filter { !it.vertical }) {
            if (uspsBar != null && (upsBar != null || anchors.upsLine == null)) break
            val box = r.rect.inflate(r.rect.width / 10 + 6, r.rect.height / 6 + 4).intersect(bounds)
            if (box.isEmpty) continue
            val crop = frame.crop(box)
            val texts = decoders.flatMap { d -> try { d.decode(crop) } catch (e: Exception) { emptyList() } }.map { it.text }
            if (uspsBar == null && texts.any { t -> ImpbParser.extractFromPayload(t).any { it.tracking == p.tracking } }) uspsBar = r.rect
            else if (upsBar == null && texts.any { t -> UpsNumber.find(t, "").isNotEmpty() }) upsBar = r.rect
        }
        val samples = com.labelrescue.core.usps.LayoutSample.fromLabel(anchors, uspsBar, upsBar, p.mailerId ?: "")
        log?.line("layout: USPS barcode ${uspsBar ?: "not located"}, UPS barcode ${upsBar ?: "none"}; anchors $anchors; learned ${samples.size} position(s)")
        return samples
    }

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
            Redactor.blankBoxes(privacyLines, frame.width, frame.height), privacyLines, lineBoxes)
    }

    /** The widest barcode-shaped horizontal area in [frame] (padded a little), or null. */
    private fun mainBarcode(frame: GrayImage, zones: com.labelrescue.core.usps.LabelZones = com.labelrescue.core.usps.LabelZones.NONE): IntRect? {
        val area = frame.width.toDouble() * frame.height
        zones.usps?.let { u -> BarcodeLocator.locate(frame.crop(u), 6).filter { !it.vertical }.maxByOrNull { it.rect.width }?.let { return IntRect(u.left + it.rect.left, u.top + it.rect.top, u.left + it.rect.right, u.top + it.rect.bottom) } }
        return BarcodeLocator.locate(frame, 6).filter { r ->
            !zones.inUps(r.rect) && !r.vertical && r.rect.width * r.rect.height.toDouble() <= 0.25 * area && r.rect.width >= 1.4 * r.rect.height
        }.maxByOrNull { it.rect.width }?.rect
    }

    /**
     * The quick read (see [run]): the result if the printed number is clear, else null. Clear:
     * at least [QUICK_READS] OCR reads give exactly the same complete number, from 2+ frames, none
     * gives another complete number, the check digit holds, and the ranking agrees at 98%+.
     */
    private fun quickRead(work: List<GrayImage>, frames: List<Int>, opt: Options, stats: PipelineStats, t0: Long, notes: MutableList<String>): RecoveryOutput? {
        val t = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(frames.size.coerceAtLeast(1))
        val results = try {
            frames.map { fi -> pool.submit<FrameWork> { processFrame(fi, work[fi], opt, collectPrivacy = fi == frames.first(), ocrOnly = true) { false } } }
                .map { it.get() }
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            pool.shutdownNow()
        }
        val complete = results.flatMap { it.observations }.filter { it.source == Source.OCR && it.slots.all { s -> s != null && s.bestP >= 0.5 } }
        val byNumber = complete.groupBy { o -> o.format to o.slots.joinToString("") { it!!.best.toString() } }
        val (key, agreeing) = byNumber.maxByOrNull { it.value.size } ?: run { log?.line("quick read: no complete number read (${System.currentTimeMillis() - t} ms)"); return null }
        val (format, number) = key
        val parsed = ImpbParser.parse(number)
        val frameCount = agreeing.map { it.frame }.distinct().size
        val others = complete.size - agreeing.size
        val why = when {
            agreeing.size < QUICK_READS -> "only ${agreeing.size} reads agree"
            frameCount < minOf(2, frames.size) -> "reads from one frame only"
            others > 0 -> "$others reads give another number"
            parsed == null || !parsed.checkValid -> "check digit fails"
            format != TrackingFormat.S10 && parsed.channel !in ImpbParser.CHANNELS && format.trackingFormat != TrackingFormat.IMPB_20 -> "doesn't start like a USPS number"
            else -> null
        }
        if (why != null) {
            log?.line("quick read: $number not clear enough ($why), reading everything (${System.currentTimeMillis() - t} ms)")
            return null
        }
        val out = combine(results, work, stats, t0, log, notes)
        val ranked = if (out.format != null) rankQuick(out) else null
        // A dead print-head line through a printed digit makes every read of it wrong the same
        // way: agreement isn't enough there. The digit model (trained on dead lines) must agree.
        val unconfirmed = out.deadCrossed.filter { i -> out.perSource[Source.DIGIT_MODEL]?.getOrNull(i)?.let { it.best != number[i] || it.bestP < 0.6 } ?: true }
        if (unconfirmed.isNotEmpty()) {
            log?.line("quick read: $number, but dead print-head lines cross digits ${unconfirmed.joinToString { "${it + 1}" }} and the digit model doesn't confirm them: reading everything")
            return null
        }
        if (ranked?.best?.tracking != number || out.format != format) {
            log?.line("quick read: $number, but the ranking says ${ranked?.best?.tracking} ${((ranked?.best?.confidence ?: 0.0) * 100).toInt()}%: reading everything")
            return null
        }
        // Five or more identical complete reads from two frames, with the check digit holding:
        // each digit is at least 99% sure (a misread would have to repeat in every read and still
        // pass the check digit).
        out.slots = out.slots.mapIndexed { i, sd -> val c = number[i]; if (sd.p(c) >= QUICK_DIGIT) sd else SlotDist.likely(c, QUICK_DIGIT, sd.alphabet) }
        log?.line("quick read: printed number clear, ${agreeing.size} reads from $frameCount frames agree: $number (${System.currentTimeMillis() - t} ms); barcodes not needed")
        notes += "Printed number read clearly (${agreeing.size} reads agree); the barcode wasn't needed."
        out.framesUsed = results.size
        out.framesStoppedEarly = true
        out.checkpoints = listOf(Checkpoint(results.size, number, rankQuick(out)?.best?.confidence ?: 0.0))
        out.barcodeArea = results.firstOrNull()?.let { w -> mainBarcode(work[w.frame], w.zones)?.let { Location(w.frame, it, Source.BARCODE) } }
        return out
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
            out.prior(com.labelrescue.core.candidates.HistoryStats.EMPTY),
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
        val groundSaver = Fusion.GROUND_SAVER.containsMatchIn(labelText) || results.any { it.zones.ups != null }
        val format = Fusion.chooseFormat(observations, partials, groundSaver)
        log?.formatVote(Fusion.formatScores(observations, partials, groundSaver), format)
        val partial: PartialResult? = format?.let { Fusion.partialFor(it, partials) }
        stats = stats.copy(ocrObservations = observations.size, millis = System.currentTimeMillis() - t0)
        if (format == null) {
            notes += "No tracking number found. Try cropping to the number, more light, or type the digits."
            return RecoveryOutput.empty(labelText, stats, notes, log)
        }
        var ocrObs = observations.filter { it.source == Source.OCR }
        var ocr2Obs = observations.filter { it.source == Source.OCR2 }
        // Printed digits a dead print-head line runs through: every read of them is wrong the same
        // way, so agreement there proves nothing (see [crossedDigits]).
        val crossed = crossedDigits(results, work, ocrObs, format)
        if (crossed.isNotEmpty()) log?.line("dead print-head lines cross printed digits at positions ${crossed.sorted().joinToString { "${it + 1}" }}: their reads count for less")
        fun clearSet(obs: List<Observation>) = Fusion.unanimous(obs, format) - crossed
        var ocr = soften(Fusion.consensus(ocrObs, format), crossed)
        var ocr2 = if (ocr2Obs.any { it.format == format }) soften(Fusion.consensus(ocr2Obs, format), crossed) else emptyList()
        var digitModel: List<SlotDist?> = emptyList()
        var slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, clearSet(ocrObs + ocr2Obs))
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
            ocr = soften(Fusion.consensus(ocrObs, format), crossed)
            ocr2 = if (ocr2Obs.isNotEmpty()) soften(Fusion.consensus(ocr2Obs, format), crossed) else emptyList()
            slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, clearSet(ocrObs + ocr2Obs))
            finalReads = again.map { "${it.source}/${it.variant}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } }
            log?.line("re-alignment pass $pass: " + finalReads.joinToString(" | "))
        }
        // Digit classifier (optional), on the re-aligned ML Kit glyph boxes only (first-pass boxes
        // can be shifted by a dropped digit), and only boxes fully inside the frame: a cut-off
        // digit at the edge gets classified as something confidently wrong.
        var strokes: Set<Int> = emptySet()
        val digits = models.digits
        if (digits != null && format != TrackingFormat.S10) {
            val modelObs = ocrObs.filter { it.format == format }.groupBy { it.frame }.mapNotNull { (fi, os) ->
                val frame = work.getOrNull(fi) ?: return@mapNotNull null
                val o = os.maxBy { o -> o.weight * o.boxes.count { it != null } }
                val m = 2
                val boxes = o.boxes.map { b -> b?.takeIf { !it.isEmpty && it.left >= m && it.top >= m && it.right <= frame.width - m && it.bottom <= frame.height - m } }
                val found = boxes.map { b -> b?.let { com.labelrescue.core.ml.CharCrop.glyphAt(frame, it) } }
                val glyphs = distinctGlyphs(boxes, found)
                // A position whose glyph went to its neighbour has no glyph of its own to read: no vote.
                val ds = boxes.mapIndexed { i, b -> b?.takeIf { found[i] == null || glyphs[i] != null || System.getenv("LR_DEDUPE_RAW") != null }?.let { SlotDist(SlotDist.DIGITS, digits.classify(frame, it, glyphs[i])) } }
                // A frame whose reads mostly contradict clearly printed digits is reading noise
                // (a very faded photo comes out as runs of 4s and 9s): left out.
                val clear = ds.indices.filter { i -> ocr.getOrNull(i)?.let { it.bestP >= 0.9 } == true && ds[i] != null }
                val agree = clear.count { i -> ds[i]!!.best == ocr[i]!!.best }
                if (System.getenv("LR_NO_FRAMEFILTER") == null && clear.size >= 4 && agree < 0.5 * clear.size) { log?.line("digit model: f$fi left out (agrees with $agree of ${clear.size} clear printed digits)"); return@mapNotNull null }
                if (ds.count { it != null } >= format.length / 2) Observation(Source.DIGIT_MODEL, format, ds, 0.5, fi, "digit-model", o.boxes, o.boxQuality) else null
            }
            if (modelObs.isNotEmpty()) {
                digitModel = Fusion.consensus(modelObs, format)
                // A faded or half-erased 6, 8 or 9 leaves a vertical stroke that OCR reads as "1" (or
                // "7"), the same way in every read, so their agreement proves nothing. Where the digit
                // model (trained on damaged digits) doesn't see that stroke digit too, the OCR read
                // counts for less and the barcode and check digit decide.
                strokes = if (!STROKE_CHECK) emptySet() else ocr.indices.filter { i ->
                    val o = ocr[i] ?: return@filter false
                    val m = digitModel.getOrNull(i) ?: return@filter false
                    (o.best == '1' || o.best == '7') && m.best != o.best && i !in crossed
                }.toSet()
                if (strokes.isNotEmpty()) {
                    log?.line("printed 1/7 the digit model doesn't confirm at positions ${strokes.sorted().joinToString { "${it + 1}" }}: OCR counts for less there")
                    ocr = soften(ocr, strokes); ocr2 = soften(ocr2, strokes)
                }
                slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, clearSet(ocrObs + ocr2Obs) - strokes)
                log?.line("digit model: " + modelObs.joinToString(" | ") { "f${it.frame}=" + it.slots.joinToString("") { s -> s?.best?.toString() ?: "?" } })
            }
        }
        // Damaged digits against the clear digits on the same label (fading, half-erased strokes).
        var selfT: List<SlotDist?> = emptyList()
        if (SELF_TEMPLATES && format != TrackingFormat.S10) {
            val fusedNow = slots
            val anchorOf = { i: Int -> fusedNow[i].takeIf { it.bestP >= 0.97 && i !in crossed && i !in strokes && ocr.getOrNull(i)?.best == it.best }?.best }
            val perFrame = ocrObs.filter { it.format == format }.groupBy { it.frame }.mapNotNull { (fi, os) ->
                val frame = work.getOrNull(fi) ?: return@mapNotNull null
                val o = os.maxBy { o -> o.weight * o.boxes.count { it != null } }
                try { SelfTemplates.read(frame, format, o.boxes, anchorOf) } catch (e: Exception) { null }
            }
            if (perFrame.isNotEmpty()) {
                selfT = List(format.length) { i ->
                    val rows = perFrame.mapNotNull { it.getOrNull(i) }
                    if (rows.isEmpty()) null else {
                        // Mean log probability over frames (one frame's view, not independent votes).
                        val l = DoubleArray(10) { d -> rows.sumOf { kotlin.math.ln(it[d].coerceAtLeast(1e-6)) } / rows.size }
                        val m = l.max(); SlotDist(SlotDist.DIGITS, DoubleArray(10) { kotlin.math.exp(l[it] - m) })
                    }
                }
                if (selfT.any { it != null }) {
                    slots = Fusion.fuse(format, ocr, partial, digitModel, ocr2, clearSet(ocrObs + ocr2Obs) - strokes, selfT)
                    log?.line("same-label templates: " + selfT.mapIndexed { i, s -> s?.let { "#${i + 1}=${it.best}:${(it.bestP * 100).toInt()}" } }.filterNotNull().joinToString(" "))
                }
            }
        }
        val perSource = HashMap<Source, List<SlotDist?>>()
        if (selfT.any { it != null }) perSource[Source.SELF_TEMPLATE] = selfT
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
            .also { it.deadCrossed = crossed; it.suggestions = if (System.getenv("LR_NO_SUGGEST") == null) suggestions(format, partial, ocr, groundSaver) else emptyList() }
    }

    /**
     * Positions of the printed number crossed by a dead print-head line, in any frame. The lines
     * come from the barcode stage (the ruled lines around the barcode) or, if that didn't run
     * (the quick read), from the barcode just above the number line. A dead column runs the whole
     * label, so it's extended down through the number.
     */
    private fun crossedDigits(results: List<FrameWork>, work: List<GrayImage>, ocrObs: List<Observation>, format: TrackingFormat): Set<Int> {
        val out = HashSet<Int>()
        if (!DEAD_DIGITS) return out
        for (w in results) {
            val frame = work.getOrNull(w.frame) ?: continue
            val obs = ocrObs.filter { it.frame == w.frame && it.format == format }
            if (obs.isEmpty()) continue
            if (!w.deadChecked) {
                synchronized(w) {
                    if (!w.deadChecked) {
                        if (w.deadLines.isEmpty()) {
                            val line = obs.maxBy { o -> o.boxes.count { it != null } }.boxes.filterNotNull().filter { !it.isEmpty }.reduceOrNull { a, b -> a.union(b) }
                            if (line != null) deadLinesAbove(frame, line)?.let { (lines, bars) -> w.deadLines = lines; w.deadBars = bars }
                        }
                        w.deadChecked = true
                    }
                }
            }
            // A dead element is on the label, so it's in every photo at the same place on the barcode;
            // a gap one photo shows alone is noise (blur, a crop edge).
            val dead = confirmed(w, results)
            if (dead.isEmpty()) continue
            for (o in obs) for ((i, b) in o.boxes.withIndex()) {
                if (b == null || b.isEmpty) continue
                val cy = (b.top + b.bottom) / 2
                val inset = b.width * 0.12
                if (dead.any { ln -> val x = ln.xAt(cy); x + ln.width / 2.0 >= b.left + inset && x - ln.width / 2.0 <= b.right - inset }) out += i
            }
        }
        return out
    }

    /**
     * Whole numbers always worth ranking, whatever the fused digits say: the barcode's own best
     * reading, that reading with any one symbol (digit pair) left open, and the printed number as
     * OCR read it. On a faded label the barcode was right at 20 of 22 digits but the fused digits
     * (outvoted by misread print) never put its number in the list.
     */
    private fun suggestions(format: TrackingFormat, partial: PartialResult?, ocr: List<SlotDist?>, groundSaver: Boolean = false): List<Map<Int, Char>> {
        val out = ArrayList<Map<Int, Char>>()
        // UPS Ground Saver: every known USPS number on one starts 9261290 (UPS's USPS mailer
        // prefix), and its start is often the part that's lost. Tried with the rest as read.
        if (groundSaver && format == TrackingFormat.IMPB_26) {
            val pre = "9261290".withIndex().associate { (i, c) -> i to c }
            out += pre
            val read = ocr.mapIndexedNotNull { i, s2 -> s2?.takeIf { i >= 7 && it.bestP >= 0.5 }?.let { i to it.best } }.toMap()
            out += pre + read
        }
        val p = partial?.takeIf { it.structure.trackingFormat == format }
        if (p != null) {
            val best = p.trackingDigits.mapIndexed { i, s -> i to s.best }.toMap()
            out += best
            val bySlot = best.keys.groupBy { Fusion.slotForTrackingDigit(p.structure, it) }
            for ((_, pos) in bySlot) out += best - pos.toSet()
        }
        val read = ocr.mapIndexedNotNull { i, s -> s?.takeIf { it.bestP >= 0.5 }?.let { i to it.best } }.toMap()
        if (read.size >= format.length - 4) out += read
        return out
    }

    /**
     * The glyph each position's box points at, with no glyph used twice: a faded digit's box
     * snaps onto its neighbour's ink, and the neighbour was then read for both positions. Where
     * two positions land on the same glyph, the one whose box is centered nearer keeps it; the
     * other is read from its own box.
     */
    private fun distinctGlyphs(boxes: List<IntRect?>, found: List<IntRect?>): List<IntRect?> {
        val g = found.toMutableList()
        // Off: on the real labels the dedupe cost more than the duplicates did (both ways of
        // handling the losing position ranked the true number lower). Kept for comparison runs.
        if (System.getenv("LR_DEDUPE") == null) return g
        for (i in g.indices) for (j in i + 1 until g.size) {
            val a = g[i] ?: continue; val b = g[j] ?: continue
            val ov = a.intersect(b)
            if (ov.isEmpty || ov.width < 0.5 * minOf(a.width, b.width)) continue
            val di = abs(boxes[i]!!.centerX - a.centerX); val dj = abs(boxes[j]!!.centerX - b.centerX)
            if (di <= dj) g[j] = null else g[i] = null
        }
        return g
    }

    /** Dead columns from the barcode above a number line [line] (frame coordinates), with the bars' extent. */
    private fun deadLinesAbove(frame: GrayImage, line: IntRect): Pair<List<DeadLines.Line>, Pair<Int, Int>>? {
        val lw = line.width
        val area = IntRect(line.left - lw / 6, line.top - (lw * 0.45).toInt(), line.right + lw / 6, line.top).intersect(IntRect(0, 0, frame.width, frame.height))
        if (area.isEmpty) return null
        val b = BarcodeBounds.find(frame, area) ?: return null
        return DeadLines.find(frame, IntRect(b.barsLeft, b.rect.top, b.barsRight, b.rect.bottom)).lines to (b.barsLeft to b.barsRight)
    }

    /** [w]'s dead lines that another frame also shows, at the same place relative to the bars. */
    private fun confirmed(w: FrameWork, results: List<FrameWork>): List<DeadLines.Line> {
        val bars = w.deadBars ?: return emptyList()
        fun rel(ln: DeadLines.Line, b: Pair<Int, Int>) = (ln.x0 - b.first) / (b.second - b.first).coerceAtLeast(1)
        val others = results.filter { it !== w && it.deadBars != null && it.deadLines.isNotEmpty() }
        val tol = max(0.004, 2.5 / (bars.second - bars.first).coerceAtLeast(1))
        return w.deadLines.filter { ln -> others.any { o -> o.deadLines.any { abs(rel(it, o.deadBars!!) - rel(ln, bars)) <= tol } } }
    }

    /**
     * Flattens OCR distributions at [crossed] positions (square root of each probability): the read
     * stays the favourite, but no longer outweighs the barcode or the digit model.
     */
    private fun soften(slots: List<SlotDist?>, crossed: Set<Int>): List<SlotDist?> {
        if (crossed.isEmpty()) return slots
        return slots.mapIndexed { i, sd -> if (sd == null || i !in crossed) sd else SlotDist(sd.alphabet, DoubleArray(sd.probs.size) { kotlin.math.sqrt(sd.probs[it]) }) }
    }

    private val restorer: ((GrayImage) -> GrayImage)? = models.restore?.let { m -> { img: GrayImage -> m.restore(img) } }

    /** All layers on one frame. Throws [FoundException] on a verified full decode. */
    /**
     * [rank] is the frame's place in the sharpest-first order. [lite]: the lighter mode for slow
     * phones (no tilted variants, fewer image variants, the second OCR on the two main ones only).
     */
    private fun processFrame(
        fi: Int, frame: GrayImage, opt: Options, collectPrivacy: Boolean, rank: Int = 0, lite: Boolean = false,
        /** The quick read: the printed number only (no barcodes, no second OCR, a few image variants). */
        ocrOnly: Boolean = false,
        stopped: () -> Boolean,
    ): FrameWork {
        val w = FrameWork(fi)
        fun check() { if (stopped()) throw CancelledException() }
        fun full(img: GrayImage, where: String) {
            w.barcodeDecodes += decoders.size
            fullDecode(img, where)?.let { throw FoundException(it, w.labelText, fi) }
        }

        // OCR: whole frame first, to find the number line and the label text.
        val numberRegion = opt.numberCrop?.toPixels(frame.width, frame.height)
        val lineBoxes = ArrayList<IntRect>()
        val grayLines = ArrayList<Pair<com.labelrescue.core.ocr.OcrLine, IntRect?>>()
        if (text != null) {
            val ocrBase = Variants.make(frame, numberRegion, Variants.Options(maxSide = 2000, rotations = emptyList(), includeThreshold = false))
                .filter { it.name == "gray" || it.name == "clahe" }
            for (v in ocrBase) {
                check()
                val page = text.recognize(v.image)
                w.ocrReads++; w.variants++
                if (v.name == "gray") {
                    w.labelText = page.text; log?.line("f$fi label text:\n" + page.text)
                    for (l in page.lines) grayLines += l to l.box?.let { v.toFrame.mapRect(it) }
                }
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

        if (ocrOnly) {
            val z = com.labelrescue.core.usps.LabelZones.find(grayLines, lineBoxes, frame.width, frame.height, opt.layout)
            lineBoxes.removeAll { z.centerInUps(it) }
        }
        if (!ocrOnly) barcodeWork(fi, frame, opt, w, grayLines, lineBoxes, detected, rank, lite, ::check, ::full)

        // OCR again, zoomed onto the number line, with every variant (and the second engine).
        if (text != null || text2 != null) {
            val lines = (lineBoxes.map { it.inflate(it.width / 12 + 6, it.height + 4) } + listOfNotNull(numberRegion)).take(2)
            for (lr in lines) {
                val r = lr.intersect(IntRect(0, 0, frame.width, frame.height))
                if (r.isEmpty) continue
                val lineVariants = Variants.make(frame, r, Variants.Options(maxSide = 2400, upscaleBelow = 1600, rotations = if (lite || ocrOnly) emptyList() else listOf(-2.0, 2.0), restore = if (lite || ocrOnly) null else restorer))
                    .filter { !lite || it.name in LITE_VARIANTS }
                    .filter { !ocrOnly || it.name in QUICK_VARIANTS }
                for (v in lineVariants) {
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
                    // The second OCR (the slower one): every variant normally (on a close call each
                    // one counts: a real label flipped when some were dropped), only the two main
                    // ones in the lighter mode for slow phones, none in the quick read.
                    if (text2 != null && !ocrOnly && (!lite || v.name == "gray" || v.name == "clahe")) {
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

    /** The barcode layers of [processFrame]: located areas, partial measurements, 2D codes. */
    private fun barcodeWork(
        fi: Int, frame: GrayImage, opt: Options, w: FrameWork,
        grayLines: List<Pair<com.labelrescue.core.ocr.OcrLine, IntRect?>>, lineBoxes: MutableList<IntRect>,
        detected: List<DetectedRegion>, rank: Int, lite: Boolean,
        check: () -> Unit, full: (GrayImage, String) -> Unit,
    ) {
        // Where the barcodes are, from the printed text: the USPS barcode between "USPS TRACKING #"
        // and the number, the UPS one under "TRACKING #: 1Z…" (left out: it isn't the USPS number).
        val zones = if (opt.barcodeCrop == null) com.labelrescue.core.usps.LabelZones.find(grayLines, lineBoxes, frame.width, frame.height, opt.layout)
        else com.labelrescue.core.usps.LabelZones.NONE
        w.zones = zones
        // The UPS "TRACKING #: 1Z…" line has enough digits to pass for the number line: drop it.
        lineBoxes.removeAll { zones.centerInUps(it) }
        if (zones.ups != null || zones.usps != null || zones.fedex != null) log?.line("f$fi label layout${if (zones.learned) " (learned)" else ""}: USPS barcode area ${zones.usps ?: "?"}, UPS barcode area ${zones.ups ?: "none"}" + (zones.fedex?.let { ", FedEx block $it (left out)" } ?: ""))

        // Barcode regions: user crop, the USPS barcode area, detected/located barcodes, the area above
        // the number line, the whole frame.
        val regions = ArrayList<Pair<IntRect, Boolean>>()
        opt.barcodeCrop?.let { regions += it.toPixels(frame.width, frame.height) to false }
        zones.usps?.let { regions += it to false }
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
        var droppedUps = 0
        fun read(img: GrayImage, outer: Affine, rect: IntRect, whole: Boolean, tag: String, dead: List<DeadLines.Line> = emptyList()) {
            val vs = Variants.make(img, null, Variants.Options(maxSide = 2400, upscaleBelow = 900, rotations = if (lite) emptyList() else listOf(-3.0, 3.0), restore = if (whole || lite) null else restorer))
                .filter { !whole || it.name == "gray" || it.name == "clahe" }
                .filter { !lite || it.name in LITE_VARIANTS }
            for (v in vs) {
                check()
                w.variants++
                full(v.image, "frame ${fi + 1} $tag${v.name}")
                val toFrame = v.toFrame.then(outer)
                // Dead print-head columns, if found, are read as unknown rather than white.
                val ms = if (dead.isEmpty()) partialDecoder.measure(v.image)
                else partialDecoder.measure(v.image) { x, y -> DeadLines.covers(dead, toFrame.mapX(x + 0.5, y + 0.5).toInt(), toFrame.mapY(x + 0.5, y + 0.5).toInt(), MASK_BLUR) }
                log?.measurements(fi, "$rect $tag${v.name}", ms)
                for (ev in ms) {
                    val t = toFrame
                    if (zones.ups != null) {
                        val x0 = ev.slotX.firstOrNull() ?: 0.0; val x1 = ev.slotX.lastOrNull() ?: 0.0
                        if (zones.centerInUps(t.mapRect(IntRect(x0.toInt(), ev.yTop, x1.toInt() + 1, ev.yBottom + 1)))) { droppedUps++; continue }
                    }
                    w.evidence += LocatedEvidence(ev, fi, t)
                }
                w.measurements++
            }
        }
        // First the barcodes' own extent, quiet zones cleaned: located areas often reach the box's
        // or label's edges, and the damaged-barcode decoder anchors on where the bars start and
        // stop (a real label with dead print-head lines read fully only from such a crop).
        val bars = ArrayList<BarcodeBounds.Bounds>()
        for ((rect, vertical) in chosenRegions) {
            if (vertical || zones.inUps(rect)) continue
            val b = BarcodeBounds.find(frame, rect) ?: continue
            // Only where the area is much bigger than the barcode (a whole label, box edges): an area
            // already tight around it gives the same reading, and a second one only double-counts.
            if (rect.width < b.rect.width * 1.2 && rect.height < b.rect.height * 1.5) continue
            if (zones.inUps(b.rect) || bars.any { o -> val i = o.rect.intersect(b.rect); !i.isEmpty && i.width.toLong() * i.height > 0.8 * b.rect.width.toLong() * b.rect.height }) continue
            bars += b
            if (bars.size >= 2) break
        }
        for (b in bars) {
            check()
            val dead = DeadLines.find(frame, IntRect(b.barsLeft, b.rect.top, b.barsRight, b.rect.bottom))
            log?.line("f$fi barcode bars ${b.barsLeft}..${b.barsRight} in ${b.rect}" + if (dead.lines.isNotEmpty()) "; ${dead.lines.size} dead print-head columns at x " + dead.lines.joinToString { "%.0f(%d px)".format(it.x0, it.width) } else "")
            if (dead.lines.isNotEmpty() && w.deadLines.isEmpty()) { w.deadLines = dead.lines; w.deadBars = b.barsLeft to b.barsRight }
            // Only lines seen in the ruled lines both above and below: one line alone can have breaks.
            read(BarcodeBounds.cleanCrop(frame, b), Affine.translate(b.rect.left.toDouble(), b.rect.top.toDouble()), b.rect, false, "bars ", if (DEAD_MASK && dead.bands == 2) dead.lines else emptyList())
        }
        for ((rect, vertical) in chosenRegions) {
            check()
            val (img, outer) = regionImage(frame, rect, vertical)
            val whole = rect.width == frame.width && rect.height == frame.height
            if (zones.inUps(rect)) {
                // The UPS barcode: read it whole (for the UPS number) but don't measure it as USPS.
                full(frame.crop(rect), "frame ${fi + 1} UPS area")
                log?.line("f$fi $rect is the UPS barcode area: not read as the USPS number")
                continue
            }
            read(img, outer, rect, whole, "")
        }

        if (droppedUps > 0) log?.line("f$fi left out $droppedUps barcode readings in the UPS barcode area")

        // DataMatrix (and any barcode) on many crops: Reed-Solomon survives heavy damage. Only on
        // the sharpest few frames (the sharpest one alone in the lighter mode).
        // The burst's full-resolution photo (one among preview-sized frames) is the only one where
        // the 2D codes' modules are big enough to read: it always gets the 2D-code pass.
        val tileCrops = if (rank < (if (lite) 1 else TILE_FRAMES) || isLargest(frame)) tiles(frame) else emptyList()
        for (crop in tileCrops) {
            check()
            val vs = Variants.make(frame, crop, Variants.Options(maxSide = 1600, rotations = emptyList()))
                .filter { it.name in setOf("gray", "clahe", "threshold", "upscale2x", "upscale3x") }
            for (v in vs) { w.variants++; full(v.image, "frame ${fi + 1} crop ${v.name}") }
            // ZXing found no valid code here: read the DataMatrix again with damaged codewords
            // marked (dead print-head columns, uncertain modules) so Reed-Solomon can fix more.
            if (DATAMATRIX_RESCUE) rescueDataMatrix(frame.crop(crop), crop, w)?.let { throw FoundException(Found(it, "DataMatrix, damaged codewords corrected (frame ${fi + 1})"), w.labelText, fi) }
        }
        if (tileCrops.isNotEmpty()) log?.line("f$fi 2D codes: full decoders found no valid tracking number in ${tileCrops.size} crops")
    }

    /** [DataMatrixRescue] on [img] (frame area [at]), with the frame's dead print-head columns as erasures. */
    private fun rescueDataMatrix(img: GrayImage, at: IntRect, w: FrameWork): ParsedNumber? {
        val dead = w.deadLines
        val mask: ((Int, Int) -> Boolean)? = if (dead.isEmpty()) null else { x, y -> DeadLines.covers(dead, x + at.left, y + at.top) }
        val r = try { DataMatrixRescue.decode(img, mask) } catch (e: Exception) { null } ?: return null
        val p = ImpbParser.extractFromPayload(r.text).firstOrNull { it.checkValid } ?: return null
        log?.line("DataMatrix rescue: \"${r.text.replace("\u001D", "<GS>")}\" (${r.erasures} codewords erased, ${r.errors} corrected)")
        return p
    }

    private fun fullDecode(img: GrayImage, where: String): Found? {
        if (NO_FULL_DECODE) return null
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
    @Volatile private var largestPixels = 0L
    private fun isLargest(frame: GrayImage) = frame.width.toLong() * frame.height >= largestPixels && largestPixels > 0

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
