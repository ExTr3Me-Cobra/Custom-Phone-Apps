package com.labelrescue.core.barcode

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.max

/**
 * Where a label's tracking-number codes are in a photo, whether or not anything could read them:
 * the 1D barcodes (USPS, and UPS on Ground Saver labels), the printed number line under each,
 * and the DataMatrix codes. For training exports: full-resolution crops of exactly the parts that
 * carry the number, damaged or not, and nothing else on the label (no names or addresses).
 */
object ScanCropFinder {
    data class Crop(val kind: String, val rect: IntRect)

    fun find(frame: GrayImage): List<Crop> {
        val out = ArrayList<Crop>()
        val all = IntRect(0, 0, frame.width, frame.height)
        // 1D barcodes: located by their bars, then trimmed to the bars' own extent.
        val bars = ArrayList<BarcodeBounds.Bounds>()
        for (r in BarcodeLocator.locate(frame, 4).filter { !it.vertical }) {
            val b = BarcodeBounds.find(frame, r.rect) ?: continue
            if (!isBarcode(frame, b)) continue
            if (bars.any { o -> val i = o.rect.intersect(b.rect); !i.isEmpty && i.width.toLong() * i.height > 0.5 * b.rect.width.toLong() * b.rect.height }) continue
            bars += b
            if (bars.size >= 2) break
        }
        for ((k, b) in bars.withIndex()) {
            out += Crop("barcode_${k + 1}", b.rect)
            // The printed number sits right under the bars.
            val h = b.rect.height
            val w = b.barsRight - b.barsLeft
            val num = IntRect(b.barsLeft - w / 20, b.rect.bottom - h / 10, b.barsRight + w / 20, b.rect.bottom + (h * 0.45).toInt()).intersect(all)
            if (!num.isEmpty) out += Crop("number_${k + 1}", num)
        }
        // DataMatrix codes: square patches of dense ink of a plausible size.
        val side = { r: IntRect -> minOf(r.width, r.height) }
        val dms = DataMatrixRescue.inkSquares(frame).filter { side(it) >= 0.03 * frame.width && side(it) <= 0.35 * frame.width }
            .filter { d -> bars.none { b -> !b.rect.intersect(d).isEmpty } }
            // Only patches with a DataMatrix's finder pattern: never a logo or a block of address text.
            .filter { d -> isDataMatrix(frame, d, all) }
        for ((k, d) in dms.take(3).withIndex()) {
            val m = max(6, d.width / 8)
            out += Crop("datamatrix_${k + 1}", IntRect(d.left - m, d.top - m, d.right + m, d.bottom + m).intersect(all))
        }
        return out
    }

    /**
     * A DataMatrix's finder pattern and data-like inside at [d], also looked for again in a crop
     * around it (on a big photo the patch is found on a shrunken copy, a few pixels off) and with
     * thin gaps filled (dead lines through every module column).
     */
    private fun isDataMatrix(frame: GrayImage, d: IntRect, all: IntRect): Boolean {
        if (DataMatrixRescue.looksLikeDataMatrix(frame, d, minFit = 0.68)) return true
        val m = max(8, (d.width * 0.3).toInt())
        val area = IntRect(d.left - m, d.top - m, d.right + m, d.bottom + m).intersect(all)
        val crop = frame.crop(area)
        for (img in listOf(crop, DataMatrixRescue.closeH(crop, 2))) {
            val boxes = DataMatrixRescue.inkSquares(img).filter { b -> b.width >= 0.6 * d.width && b.width <= 1.4 * d.width }
            if (boxes.any { DataMatrixRescue.looksLikeDataMatrix(img, it, minFit = 0.68) }) return true
            if (DataMatrixRescue.decode(img) != null) return true
        }
        return false
    }

    /**
     * Bars, not a line of bold text: barcode proportions (at least an eighth as tall as wide) and
     * many bar edges along every sampled row (a tracking barcode has over 100; big letters, a few).
     */
    private fun isBarcode(img: GrayImage, b: BarcodeBounds.Bounds): Boolean {
        val w = b.barsRight - b.barsLeft
        val top = b.rect.top + b.rect.height / 4; val bottom = b.rect.bottom - b.rect.height / 4
        if (bottom - top < 4 || w < 60) return false
        if ((b.rect.height) < w / 8.0) return false
        var rows = 0; var good = 0
        for (y in top until bottom step max(1, (bottom - top) / 8)) {
            rows++
            var lo = 255; var hi = 0
            for (x in b.barsLeft until b.barsRight) { val v = img[x, y]; if (v < lo) lo = v; if (v > hi) hi = v }
            val t = (lo + hi) / 2; var edges = 0
            for (x in b.barsLeft + 1 until b.barsRight) if ((img[x, y] < t) != (img[x - 1, y] < t)) edges++
            if (edges >= 60) good++
        }
        return good >= 0.6 * rows
    }
}
