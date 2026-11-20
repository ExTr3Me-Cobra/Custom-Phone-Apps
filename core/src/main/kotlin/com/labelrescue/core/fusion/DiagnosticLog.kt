package com.labelrescue.core.fusion

import com.labelrescue.core.barcode.BarcodeRead
import com.labelrescue.core.barcode.PartialResult
import com.labelrescue.core.barcode.SymbolEvidence
import com.labelrescue.core.candidates.RankResult
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.ocr.OcrPage
import com.labelrescue.core.usps.TrackingFormat

/**
 * A detailed, copy-pasteable log of one recovery for development: every OCR read (with
 * per-character confidence and alternatives), how it aligned to positions, every barcode
 * decode, the partial decoder's measurements and layout candidates, the format vote, each
 * source's digit probabilities, the fused result and the ranked candidates.
 * Thread-safe (frames are processed in parallel; lines are prefixed with the frame).
 */
class DiagnosticLog {
    private val lines = ArrayList<String>()
    private val t0 = System.currentTimeMillis()

    @Synchronized
    fun line(s: String) { lines += "[%6.2fs] %s".format((System.currentTimeMillis() - t0) / 1000.0, s) }

    fun section(title: String) = line("===== $title =====")

    fun ocr(frame: Int, label: String, page: OcrPage, obs: List<Observation>) {
        val numberLines = page.lines.filter { l -> l.text.count { it.isDigit() } >= 8 }
        line("f$frame OCR $label: ${page.lines.size} lines, ${numberLines.size} number-like")
        for (l in numberLines) {
            line("f$frame   line \"${l.text}\" conf=${fmt(l.confidence)} angle=${fmt(l.angle)}")
            val syms = l.elements.flatMap { it.symbols }
            if (syms.isNotEmpty()) {
                line("f$frame   chars " + syms.joinToString(" ") { s ->
                    val alts = s.alternatives?.entries?.sortedByDescending { it.value }?.take(3)?.joinToString("/") { "${it.key}${pct(it.value)}" }
                    "${s.text}:${pct(s.confidence)}" + (alts?.let { "($it)" } ?: "") + (s.box?.let { "@${it.centerX.toInt()}" } ?: "")
                })
            }
        }
        for (o in obs) {
            line("f$frame   -> ${o.source} ${o.format} weight=${fmt(o.weight)} digits=" + o.slots.joinToString("") { it?.best?.toString() ?: "?" })
        }
    }

    fun barcodeReads(frame: Int, where: String, reads: List<BarcodeRead>) {
        if (reads.isEmpty()) return
        line("f$frame decode $where: " + reads.joinToString { "${it.decoder}/${it.kind}: \"${it.text.replace("\u001D", "<GS>")}\"" })
    }

    fun measurements(frame: Int, where: String, ev: List<SymbolEvidence>) {
        if (ev.isEmpty()) return
        line("f$frame partial $where: " + ev.joinToString { e ->
            "n=${e.symbolCount} q=${fmt(e.quality.average())} X=${fmt(e.moduleWidth)}${if (e.extentAnchored) " ext" else ""}${if (e.reversed) " rev" else ""}"
        })
    }

    fun partials(all: List<PartialResult>) {
        section("Damaged-barcode layouts (best first)")
        for (p in all.take(8)) {
            line("${p.structure.name}: tracking=${p.bestTracking} zip=${p.zipDigits.joinToString("") { it.best.toString() }} " +
                "score=${fmt(p.selectionScore)} logZ=${fmt(p.logZ)} conf=${fmt(p.confidence)}")
        }
        all.firstOrNull()?.let { p ->
            line("best layout per digit: " + p.trackingDigits.mapIndexed { i, d -> "${i + 1}=${d}" }.joinToString(" | "))
            if (p.recovered.isNotEmpty()) line("checksum-recovered: " + p.recovered.joinToString { "#${it.position + 1}=${it.digit} ${pct(it.confidence.toFloat())} (raw ${pct(it.rawConfidence.toFloat())})" })
        }
    }

    fun formatVote(scores: Map<TrackingFormat, Double>, chosen: TrackingFormat?) {
        line("format vote: " + scores.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${fmt(it.value)}" } + " -> $chosen")
    }

    fun positions(format: TrackingFormat, perSource: Map<Source, List<SlotDist?>>, fused: List<SlotDist>) {
        section("Per-position evidence ($format)")
        for (i in 0 until format.length) {
            val parts = perSource.entries.joinToString("  ") { (s, l) -> "${s.name}=${l.getOrNull(i)?.toString() ?: "-"}" }
            line("#${(i + 1).toString().padStart(2)} fused=${fused[i]}   $parts")
        }
    }

    fun candidates(r: RankResult, top: Int = 15) {
        section("Candidates (${r.candidates.size} listed, ${"%.3g".format(r.totalValid)} valid of ${"%.3g".format(r.totalCombinations)} combinations)")
        for ((i, c) in r.candidates.take(top).withIndex()) {
            line("${i + 1}. ${TrackingFormat.grouped(c.tracking)} conf=${pct(c.confidence.toFloat())} evidence=${fmt(c.evidenceScore)} prior=${fmt(c.priorScore)}")
        }
    }

    /**
     * The log as text. [redact] masks capitalized label words such as names and street names
     * (keeps digits, ZIPs and words like USPS / PRIORITY / GROUND / MAILED / CREATED).
     */
    @Synchronized
    fun text(redact: Boolean = false): String = if (!redact) lines.joinToString("\n") else lines.joinToString("\n") { redactLine(it) }

    companion object {
        private val KEEP = setOf(
            "USPS", "UNITED", "STATES", "POSTAL", "SERVICE", "PRIORITY", "MAIL", "EXPRESS", "GROUND", "ADVANTAGE", "FIRST", "CLASS",
            "TRACKING", "SHIP", "TO", "FROM", "MAILED", "CREATED", "DATE", "ZIP", "POSTAGE", "PAID", "LB", "OZ", "PARCEL", "SELECT",
            "MEDIA", "CERTIFIED", "RETURN", "RECEIPT", "SIGNATURE", "US", "EP", "OCR", "ML", "KIT", "LINE", "CHARS", "PADDLEOCR",
        )

        fun redactLine(s: String): String = s.replace(Regex("[A-Za-z][A-Za-z'.-]*")) { m ->
            val w = m.value.uppercase().trim('.', '-', '\'')
            // Label text is printed in capitals; the log's own words are lower or mixed case.
            if (w in KEEP || w.length <= 1 || m.value.any { it.isLowerCase() }) m.value else "X".repeat(m.value.length)
        }

        private fun fmt(d: Double) = "%.2f".format(d)
        private fun fmt(f: Float) = if (f.isNaN()) "?" else "%.2f".format(f)
        private fun pct(f: Float) = if (f.isNaN()) "?" else "${(f * 100).toInt()}%"
    }
}
