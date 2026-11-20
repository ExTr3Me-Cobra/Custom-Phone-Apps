package com.labelrescue.core.fusion

import com.labelrescue.core.candidates.RankResult
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.usps.ServiceHints
import com.labelrescue.core.usps.TrackingFormat
import com.labelrescue.core.verify.LabelContext

/**
 * One compact entry per scan, for the "send me photos and I'll check them" training loop: what
 * the app concluded and why, per digit, with no names or addresses (only the service, ZIPs and
 * date from the label). Meant to be pasted alongside the photo; the real number is filled in by
 * the person who checked the label.
 */
object TrainingReport {
    fun entry(
        id: String,
        time: String,
        header: String,
        out: RecoveryOutput,
        ranked: RankResult?,
        confirmed: String?,
        verification: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder()
        sb.append("=== Scan $id · $time\n")
        sb.append(header).append('\n')
        val s = out.stats
        sb.append("frames ${s.frames} · variants ${s.variants} · OCR reads ${s.ocrReads} · ${s.millis / 1000.0}s\n")
        if (out.ups.isNotEmpty()) sb.append("UPS: " + out.ups.take(3).joinToString { "${it.tracking} (${it.source}${if (it.corrected) ", corrected" else ""})" } + "\n")
        if (out.checkpoints.isNotEmpty()) {
            sb.append("frames used ${out.framesUsed} of ${s.frames}" + (if (out.framesStoppedEarly) " (stopped early: answer settled)" else "") + "\n")
            sb.append("answer by frames: " + out.checkpoints.joinToString(" · ") { c ->
                "${c.frames}: " + (c.top?.let { TrackingFormat.grouped(it) } ?: "none") + " ${(c.confidence * 100).toInt()}%"
            } + "\n")
        }
        val ctx = LabelContext.fromLabelText(out.labelText, out.zip ?: out.fullDecode?.zip)
        sb.append("label: service=${ServiceHints.explain(out.labelText)}; dest ZIP=${ctx.destinationZip ?: "?"}${if (out.zip != null) " (barcode)" else ""}; ")
            .append("origin ZIP=${ctx.originZip ?: "?"}; date=${LabelContext.formatDate(ctx.labelDate).ifEmpty { "?" }}\n")
        val f = out.format
        when {
            out.fullDecode != null -> sb.append("full decode: ${out.fullDecode.tracking} by ${out.fullDecodeBy}\n")
            f == null -> sb.append("no number found\n")
        }
        if (f != null && ranked != null) {
            sb.append("app reading ($f), top candidates:\n")
            for ((i, c) in ranked.candidates.take(5).withIndex()) {
                sb.append("  ${i + 1}. ${TrackingFormat.grouped(c.tracking)}  ${"%.0f".format(c.confidence * 100)}%")
                verification[c.tracking]?.let { sb.append("  [USPS: $it]") }
                sb.append('\n')
            }
        }
        if (f != null && out.fullDecode == null) {
            val srcs = out.perSource.keys.sortedBy { it.ordinal }
            sb.append("per digit, top 2 as digit:percent (fused | ${srcs.joinToString(" | ") { it.name }}):\n")
            for (i in 0 until f.length) {
                sb.append("  #${(i + 1).toString().padStart(2)} ${short(out.slots.getOrNull(i))}")
                for (src in srcs) sb.append(" | ").append(short(out.perSource[src]?.getOrNull(i)))
                sb.append('\n')
            }
            if (out.reads.isNotEmpty()) sb.append("OCR reads (aligned):\n").append(out.reads.joinToString("") { "  $it\n" })
            out.partial?.let { p ->
                sb.append("barcode: layout ${p.structure.name} → ${p.bestTracking} (confidence ${"%.2f".format(p.confidence)}), ZIP ${p.zipDigits.joinToString("") { it.best.toString() }}\n")
            } ?: sb.append("barcode: no usable reading\n")
        }
        sb.append("confirmed in app: ${confirmed?.let { TrackingFormat.grouped(it) } ?: "no"}\n")
        sb.append("REAL NUMBER (fill in): ______________________\n")
        return sb.toString()
    }

    private fun short(d: SlotDist?): String =
        d?.ranked()?.take(2)?.joinToString(" ") { "${it.first}:${"%.0f".format(it.second * 100)}" }?.padEnd(9) ?: "-".padEnd(9)
}
