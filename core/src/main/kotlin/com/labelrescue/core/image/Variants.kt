package com.labelrescue.core.image

import kotlin.math.abs
import kotlin.math.max

/** One preprocessed version of (part of) a frame. [toFrame] maps its pixels back to the frame. */
class Variant(val name: String, val image: GrayImage, val toFrame: Affine)

/**
 * Preprocessing variants. Every decoder runs on every variant: different damage is readable
 * under different processing (contrast for faded print, threshold for smudges, sharpening for
 * blur, rotation for skew, upscaling for small print).
 */
object Variants {

    data class Options(
        val maxSide: Int = 2000,
        /** Upscale when the region's longest side is below this. */
        val upscaleBelow: Int = 1400,
        val rotations: List<Double> = listOf(-3.0, 3.0),
        val includeThreshold: Boolean = true,
    )

    fun make(frame: GrayImage, region: IntRect? = null, opt: Options = Options()): List<Variant> {
        var base = frame
        var toFrame = Affine.IDENTITY
        if (region != null) {
            val r = region.intersect(IntRect(0, 0, frame.width, frame.height))
            if (!r.isEmpty && (r.width < frame.width || r.height < frame.height)) {
                base = frame.crop(r)
                toFrame = Affine.translate(r.left.toDouble(), r.top.toDouble())
            }
        }
        val longest = max(base.width, base.height)
        if (longest > opt.maxSide) {
            val f = opt.maxSide.toDouble() / longest
            base = base.scaledBy(f)
            toFrame = Affine.scale(1 / f).then(toFrame)
        }
        val out = ArrayList<Variant>()
        out += Variant("gray", base, toFrame)
        val clahe = Filters.clahe(Filters.normalize(base))
        out += Variant("clahe", clahe, toFrame)
        out += Variant("sharpen", Filters.sharpen(base), toFrame)
        if (opt.includeThreshold) out += Variant("threshold", Filters.adaptiveThreshold(clahe), toFrame)
        if (longest < opt.upscaleBelow) {
            val f = if (longest < opt.upscaleBelow / 2) 3.0 else 2.0
            out += Variant("upscale${f.toInt()}x", Filters.sharpen(base.scaledBy(f), 1.0, 0.8), Affine.scale(1 / f).then(toFrame))
        }
        val skew = Filters.estimateSkew(base)
        if (abs(skew) >= 0.6) {
            // The sign convention of the estimate vs. rotation is checked by trying both and keeping
            // the one that ends up straighter.
            val a = base.rotated(skew); val b = base.rotated(-skew)
            val best = if (abs(Filters.estimateSkew(a.first)) <= abs(Filters.estimateSkew(b.first))) a else b
            out += Variant("deskew", best.first, best.second.then(toFrame))
        }
        for (deg in opt.rotations) {
            val (img, back) = base.rotated(deg)
            out += Variant("rot${if (deg > 0) "+" else ""}${deg.toInt()}", img, back.then(toFrame))
        }
        return out
    }
}
