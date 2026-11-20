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
            if (exact.isFinite()) (exact - approx).coerceIn(-25.0, 25.0) else -25.0
        }
    }

    companion object {
        fun empty(labelText: String, stats: PipelineStats, notes: List<String>) =
            RecoveryOutput(null, emptyList(), emptyMap(), null, null, null, labelText, null, emptyMap(), stats, notes)
    }
}

/** Multi-frame consensus per source, then fusion across sources, per digit position. */
object Fusion {
    const val EPS_OCR = 0.08
    const val EPS_PARTIAL = 0.03

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
            for (o in same) {
                val s = o.slots[i] ?: continue
                if (s.alphabet != alphabet) continue
                for (k in acc.indices) acc[k] += o.weight * s.probs[k]
                w += o.weight
            }
            if (w <= 0) null else {
                val pseudo = 0.15
                val mixed = DoubleArray(acc.size) { (acc[it] + pseudo / acc.size) / (w + pseudo) }
                val gamma = min(2.5, 1 + 0.35 * ln(1 + w))
                SlotDist(alphabet, DoubleArray(mixed.size) { mixed[it].pow(gamma) })
            }
        }
    }

    /** Picks the tracking format the evidence agrees on. */
    fun chooseFormat(obs: List<Observation>, partial: PartialResult?): TrackingFormat? {
        val score = HashMap<TrackingFormat, Double>()
        for (o in obs) score.merge(o.format, o.weight, Double::plus)
        if (partial != null) score.merge(partial.structure.trackingFormat, 5.0, Double::plus)
        return score.maxByOrNull { it.value }?.key
    }

    /** Fuses OCR consensus and partial-barcode digits into one distribution per position. */
    fun fuse(format: TrackingFormat, ocr: List<SlotDist?>, partial: PartialResult?): List<SlotDist> {
        val bar = partial?.takeIf { it.structure.trackingFormat == format }?.trackingDigits
        return List(format.length) { i ->
            val alphabet = if (format.isLetterPosition(i)) SlotDist.LETTERS else SlotDist.DIGITS
            val p = DoubleArray(alphabet.length) { 1.0 }
            ocr.getOrNull(i)?.let { s -> for (k in p.indices) p[k] *= floored(s.probs[k], EPS_OCR) }
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
                if (o.format != format) continue
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
