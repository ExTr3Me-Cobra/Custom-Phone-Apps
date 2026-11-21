package com.labelrescue.core.usps

import com.labelrescue.core.image.IntRect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where barcodes sat on intact labels, relative to printed text the app can still find on a
 * damaged one ("anchors"): the USPS number line, the "USPS TRACKING #" line, the UPS 1Z line.
 * Horizontal offsets are in anchor widths, vertical ones in anchor heights, so they carry over
 * to any photo size, distance and position.
 */
data class LayoutSample(
    val target: Target,
    val anchor: Anchor,
    val left: Double, val right: Double, val top: Double, val bottom: Double,
    /** Mailer ID (or "ups"): each shipper counts at most [LearnedLayout.PER_SHIPPER] times. */
    val shipper: String,
) {
    enum class Target { USPS, UPS }
    enum class Anchor { NUMBER, USPS_LINE, UPS_LINE }

    /**
     * Where a barcode can be relative to its anchor: the USPS barcode sits above the number line
     * and below "USPS TRACKING #", the UPS one below its 1Z line. Anything else came from a
     * misread anchor (another line taken for it) and is not used.
     */
    val plausible: Boolean get() = right - left in 0.3..4.0 && bottom - top in 1.0..40.0 && when (anchor) {
        Anchor.NUMBER -> bottom in -25.0..2.0 && top < 0
        Anchor.USPS_LINE, Anchor.UPS_LINE -> top in -2.0..6.0 && bottom > 1.0
    }

    fun encode(): String = listOf(target, anchor, f(left), f(right), f(top), f(bottom), shipper.filter { it.isLetterOrDigit() }).joinToString("|")

    /** Where this sample puts the barcode, for an anchor at [a]. */
    fun place(a: IntRect): IntRect = IntRect(
        (a.left + left * a.width).roundToInt(), (a.top + top * a.height).roundToInt(),
        (a.left + right * a.width).roundToInt(), (a.top + bottom * a.height).roundToInt(),
    )

    companion object {
        private fun f(d: Double) = "%.3f".format(java.util.Locale.US, d)

        fun decode(s: String): LayoutSample? = runCatching {
            val p = s.trim().split('|')
            LayoutSample(Target.valueOf(p[0]), Anchor.valueOf(p[1]), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p.getOrElse(6) { "" })
        }.getOrNull()

        /** Offsets of [bar] relative to anchor [a]. */
        fun of(target: Target, anchor: Anchor, bar: IntRect, a: IntRect, shipper: String): LayoutSample? {
            if (a.width < 8 || a.height < 4) return null
            val w = a.width.toDouble(); val h = a.height.toDouble()
            val s = LayoutSample(target, anchor, (bar.left - a.left) / w, (bar.right - a.left) / w, (bar.top - a.top) / h, (bar.bottom - a.top) / h, shipper)
            // Implausible pairings (an anchor read on the wrong line) are not learned.
            return s.takeIf { it.plausible }
        }

        /** The samples one intact label teaches: each barcode found, against each anchor found. */
        fun fromLabel(anchors: LabelZones.Anchors, uspsBar: IntRect?, upsBar: IntRect?, shipper: String): List<LayoutSample> {
            val out = ArrayList<LayoutSample>()
            if (uspsBar != null) {
                anchors.number?.let { of(Target.USPS, Anchor.NUMBER, uspsBar, it, shipper)?.let(out::add) }
                anchors.uspsLine?.let { of(Target.USPS, Anchor.USPS_LINE, uspsBar, it, shipper)?.let(out::add) }
            }
            if (upsBar != null) anchors.upsLine?.let { of(Target.UPS, Anchor.UPS_LINE, upsBar, it, "ups")?.let(out::add) }
            return out
        }
    }
}

/** What the app learned about barcode positions (see [LayoutSample]). */
class LearnedLayout(samples: List<LayoutSample>) {
    /** At most [PER_SHIPPER] samples per shipper and kind, most recent last, so one shipper can't dominate. */
    val samples: List<LayoutSample> = samples.filter { it.plausible }.groupBy { Triple(it.target, it.anchor, it.shipper) }.values.flatMap { it.takeLast(PER_SHIPPER) }

    val size: Int get() = samples.size

    /**
     * Where [target] should be, for an anchor of kind [anchor] at [box]: the span the learned
     * labels cover (10th to 90th percentile of each edge), padded. Null below [MIN_SAMPLES].
     */
    fun area(target: LayoutSample.Target, anchor: LayoutSample.Anchor, box: IntRect): IntRect? {
        val s = samples.filter { it.target == target && it.anchor == anchor }
        if (s.size < MIN_SAMPLES) return null
        fun pct(v: List<Double>, q: Double): Double { val o = v.sorted(); return o[((o.size - 1) * q).roundToInt()] }
        val l = pct(s.map { it.left }, 0.1); val r = pct(s.map { it.right }, 0.9)
        val t = pct(s.map { it.top }, 0.1); val b = pct(s.map { it.bottom }, 0.9)
        val w = box.width.toDouble(); val h = max(4, box.height).toDouble()
        val padX = 0.06 * w; val padY = 1.0 * h
        return IntRect(
            (box.left + l * w - padX).roundToInt(), (box.top + t * h - padY).roundToInt(),
            (box.left + r * w + padX).roundToInt(), (box.top + b * h + padY).roundToInt(),
        )
    }

    fun encode(): String = samples.joinToString("\n") { it.encode() }

    companion object {
        const val PER_SHIPPER = 5
        const val MIN_SAMPLES = 2
        /** Most samples kept in all (oldest dropped first). */
        const val MAX_SAMPLES = 400

        fun decode(text: String): LearnedLayout = LearnedLayout(text.lines().mapNotNull { if (it.isBlank()) null else LayoutSample.decode(it) })
    }
}
