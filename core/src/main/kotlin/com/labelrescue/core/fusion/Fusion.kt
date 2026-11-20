package com.labelrescue.core.fusion

import com.labelrescue.core.barcode.Gs1Structure
import com.labelrescue.core.barcode.PartialResult
import com.labelrescue.core.barcode.RecoveredDigit
import com.labelrescue.core.candidates.CompositePrior
import com.labelrescue.core.candidates.HistoryPrior
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.candidates.RankingPrior
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.candidates.StructurePrior
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.usps.ParsedNumber
import com.labelrescue.core.usps.ServiceHints
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Where a tracking position can be seen: frame index and box in that frame. */
data class Location(val frame: Int, val box: IntRect, val source: Source)

data class PipelineStats(
    val frames: Int = 0,
    val variants: Int = 0,
    val barcodeDecodes: Int = 0,
    val ocrReads: Int = 0,
    val ocrObservations: Int = 0,
    val barcodeMeasurements: Int = 0,
    val millis: Long = 0,
)

/** Everything the pipeline concluded about one label. */
class RecoveryOutput(
    /** Tracking format (unrouted), or null if no number was found. */
    val format: TrackingFormat?,
    /** Fused distribution per tracking position. */
    val slots: List<SlotDist>,
    /** Per-source consensus, for display. */
    val perSource: Map<Source, List<SlotDist?>>,
    /** A full decode that passed the check digit, if any. */
    val fullDecode: ParsedNumber?,
    val fullDecodeBy: String?,
    val zip: String?,
    val labelText: String,
    val partial: PartialResult?,
    val locations: Map<Int, Location>,
    val stats: PipelineStats,
    val notes: List<String>,
    /** Detailed development log, if one was requested. */
    val log: DiagnosticLog? = null,
) {
    val recovered: List<RecoveredDigit> get() = partial?.recovered.orEmpty()
    val serviceHints: List<ServiceHints.Hint> get() = ServiceHints.serviceHints(labelText)

    fun prior(history: HistoryStats): RankingPrior = CompositePrior(
        listOf(StructurePrior(serviceHints, listOfNotNull(zip) + ServiceHints.zips(labelText)), HistoryPrior(history)),
    )

    /**
     * Exact barcode term: replaces the per-digit barcode factor with the barcode's joint
     * likelihood under its checksum (pairs of digits share a symbol). Null without barcode evidence.
     */
    fun exactEvidence(): ((String) -> Double)? {
        val p = partial ?: return null
        val f = format ?: return null
        if (p.structure.trackingFormat != f) return null
        val marg = p.trackingDigits
        val logZ = p.dataLogZ
        return { number ->
            val t = number.substring(0, f.length)
            val exact = p.logLikelihood(t) - logZ
            var approx = 0.0
            for (i in t.indices) approx += ln(Fusion.floored(marg[i].p(t[i]), Fusion.EPS_PARTIAL))
            // A tie-breaker, not an override: a smeared symbol can make the barcode's joint
            // likelihood confidently wrong, so the correction is damped and capped.
            if (exact.isFinite()) (0.5 * (exact - approx)).coerceIn(-4.0, 4.0) else -4.0
        }
    }

    companion object {
        fun empty(labelText: String, stats: PipelineStats, notes: List<String>, log: DiagnosticLog? = null) =
            RecoveryOutput(null, emptyList(), emptyMap(), null, null, null, labelText, null, emptyMap(), stats, notes, log)
    }
}

/** Multi-frame consensus per source, then fusion across sources, per digit position. */
object Fusion {
    const val EPS_OCR = 0.08
    const val EPS_PARTIAL = 0.03
    const val EPS_DIGIT_MODEL = 0.12
    const val EPS_OCR2 = 0.08

    fun floored(p: Double, eps: Double) = eps / 10 + (1 - eps) * p

    /**
     * Votes per position across observations (frames × variants). Agreement between many reads
     * sharpens the result; disagreement keeps the alternatives.
     */
    fun consensus(obs: List<Observation>, format: TrackingFormat): List<SlotDist?> {
        val same = obs.filter { it.format == format }
        return List(format.length) { i ->
            val alphabet = if (format.isLetterPosition(i)) SlotDist.LETTERS else SlotDist.DIGITS
            val acc = DoubleArray(alphabet.length)
            var w = 0.0
            val frames = HashSet<Int>()
            for (o in same) {
                val s = o.slots[i] ?: continue
                if (s.alphabet != alphabet) continue
                for (k in acc.indices) acc[k] += o.weight * s.probs[k]
                w += o.weight
                frames += o.frame
            }
            if (w <= 0) null else {
                val pseudo = 0.15
                val mixed = DoubleArray(acc.size) { (acc[it] + pseudo / acc.size) / (w + pseudo) }
                // Agreement sharpens only across separate frames: image variants of one frame
                // share the same smudge, so they aren't independent reads.
                val gamma = min(2.5, 1 + 0.35 * ln(frames.size.toDouble()))
                SlotDist(alphabet, DoubleArray(mixed.size) { mixed[it].pow(gamma) })
            }
        }
    }

    /**
     * Picks the tracking format the evidence agrees on. The barcode's vote is scaled by how sure
     * it is: a badly damaged barcode can fit the wrong layout with garbage digits, and must not
     * override clear OCR of the printed number.
     */
    fun chooseFormat(obs: List<Observation>, partials: List<PartialResult>): TrackingFormat? =
        formatScores(obs, partials).maxByOrNull { it.value }?.key

    fun formatScores(obs: List<Observation>, partials: List<PartialResult>): Map<TrackingFormat, Double> {
        val score = HashMap<TrackingFormat, Double>()
        // A printed number that aligned to a format is strong evidence of its length.
        for (o in obs) if (o.source != Source.DIGIT_MODEL) score.merge(o.format, 3.0 * o.weight, Double::plus)
        partials.firstOrNull()?.let { score.merge(it.structure.trackingFormat, 4.0 * it.confidence, Double::plus) }
        return score
    }

    /** The best barcode reading for [format], unless it is far less likely than the best overall. */
    fun partialFor(format: TrackingFormat, partials: List<PartialResult>): PartialResult? {
        val best = partials.firstOrNull() ?: return null
        return partials.firstOrNull { it.structure.trackingFormat == format }?.takeIf { it.selectionScore >= best.selectionScore - 4 }
    }

    /** Fuses OCR consensus and partial-barcode digits into one distribution per position. */
    fun fuse(
        format: TrackingFormat,
        ocr: List<SlotDist?>,
        partial: PartialResult?,
        digitModel: List<SlotDist?> = emptyList(),
        ocr2: List<SlotDist?> = emptyList(),
    ): List<SlotDist> {
        val bar = partial?.takeIf { it.structure.trackingFormat == format }?.trackingDigits
        return List(format.length) { i ->
            val alphabet = if (format.isLetterPosition(i)) SlotDist.LETTERS else SlotDist.DIGITS
            val p = DoubleArray(alphabet.length) { 1.0 }
            ocr.getOrNull(i)?.let { s -> for (k in p.indices) p[k] *= floored(s.probs[k], EPS_OCR) }
            digitModel.getOrNull(i)?.takeIf { it.alphabet == alphabet }?.let { s -> for (k in p.indices) p[k] *= floored(s.probs[k], EPS_DIGIT_MODEL) }
            ocr2.getOrNull(i)?.takeIf { it.alphabet == alphabet }?.let { s -> for (k in p.indices) p[k] *= floored(s.probs[k], EPS_OCR2) }
            bar?.getOrNull(i)?.let { s -> for (k in p.indices) p[k] *= floored(s.probs[k], EPS_PARTIAL) }
            SlotDist(alphabet, p)
        }
    }

    /** Best place to show each position: the clearest OCR read, else the barcode symbol. */
    fun locations(
        format: TrackingFormat,
        obs: List<Observation>,
        partial: PartialResult?,
        barcodeBox: (PartialResult, Int) -> Location?,
    ): Map<Int, Location> {
        val out = HashMap<Int, Location>()
        for (i in 0 until format.length) {
            var best: Observation? = null; var bestScore = 0.0
            for (o in obs) {
                if (o.format != format || o.source != Source.OCR) continue
                val b = o.boxes[i] ?: continue
                if (b.isEmpty) continue
                val sc = o.weight * o.boxQuality[i] * max(0.2, o.slots[i]?.bestP ?: 0.3)
                if (sc > bestScore) { bestScore = sc; best = o }
            }
            if (best != null) out[i] = Location(best.frame, best.boxes[i]!!, Source.OCR)
            else if (partial != null && partial.structure.trackingFormat == format) barcodeBox(partial, i)?.let { out[i] = it }
        }
        return out
    }

    /** Data-slot index (0-based among data symbols) holding tracking digit [t]. */
    fun slotForTrackingDigit(st: Gs1Structure, t: Int): Int {
        val di = st.routingDigits + t - (t % 2)
        return st.slots.indexOfFirst { it is Gs1Structure.Slot.Pair && it.digitIndex == di }
    }
}
