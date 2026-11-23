package com.labelrescue.mltools

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ml.CharCrop
import com.labelrescue.core.synth.Synth
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The damage seen on real labels, applied to the real (curated) digit crops, so the digit model
 * learns real printers' fonts under that damage. Calibrated on the real damaged labels
 * (training/real_damaged_test and the regression cases), in units of the text height h:
 *  - dead print-head lines: 0.03-0.1 h wide, 0-3 across a digit, ink gone along the whole height;
 *  - dead bands (several dead elements side by side): up to about 0.45 h, never more than a
 *    third of the glyph's width, so the digit stays readable (labels would be guesses otherwise);
 *  - fading: ink lightened to 35-85% of its darkness, sometimes only on one side of the digit;
 *  - phone blur (sigma 0.015-0.05 h) and sensor noise.
 * Each crop is damaged at three times its size, then cut again exactly as the app cuts digits.
 */
object RealDamage {
    private const val UP = 3

    /** [n] damaged versions of each crop in [dir] (labels.csv), leaving out [excludeTracking]. */
    fun samples(dir: File, excludeTracking: Set<String>, n: Int, rnd: Random): List<Pair<ByteArray, Int>> = labeled(dir, excludeTracking, n, rnd).map { it.first to it.second }

    /** Like [samples], with the tracking number each damaged crop came from. */
    fun labeled(dir: File, excludeTracking: Set<String>, n: Int, rnd: Random): List<Triple<ByteArray, Int, String>> {
        val rows = File(dir, "labels.csv").readLines().drop(1).map { it.split(",") }.filter { it.size >= 5 && it[4] !in excludeTracking }
        val out = ArrayList<Triple<ByteArray, Int, String>>()
        for (r in rows) {
            val f = File(dir, r[0]); if (!f.exists()) continue
            val d = r[1].toIntOrNull() ?: continue
            val base = load(f).scaledBy(UP.toDouble())
            repeat(n) {
                val img = damage(base, rnd)
                val crop = CharCrop.extract(img, IntRect(9 * UP, 3 * UP, 23 * UP, 29 * UP))
                out += Triple(ByteArray(crop.size) { (crop[it] * 255).roundToInt().coerceIn(0, 255).toByte() }, d, r[4])
            }
        }
        return out
    }

    /** One damaged version of an (upscaled) crop: paper ~235, ink dark. */
    fun damage(src: GrayImage, rnd: Random): GrayImage {
        var img = src.copy()
        val w = img.width; val hgt = img.height
        val h = hgt / 1.25 // the crop is 1.25 text heights tall
        // The glyph's columns (to keep bands from erasing too much of it).
        val cols = (0 until w).filter { x -> (0 until hgt).count { y -> img[x, y] < 128 } > 2 }
        val gl = cols.minOrNull() ?: w / 3; val gr = cols.maxOrNull() ?: 2 * w / 3
        val gw = max(1, gr - gl + 1)
        fun clearColumn(x0: Int, width: Int) {
            for (x in max(0, x0) until min(w, x0 + width)) for (y in 0 until hgt) {
                val edge = x == x0 || x == x0 + width - 1
                img[x, y] = if (edge) max(img[x, y], (img[x, y] + 232) / 2) else max(img[x, y], 226 + rnd.nextInt(8))
            }
        }
        // Dead print-head lines (the commonest damage): 0-3 across the digit.
        if (rnd.nextDouble() < 0.75) repeat(1 + rnd.nextInt(3)) {
            val lw = max(1, (h * (0.03 + rnd.nextDouble() * 0.07)).roundToInt())
            clearColumn(gl + rnd.nextInt(gw) - lw / 2, lw)
        }
        // A dead band: several elements side by side, up to half the glyph.
        if (rnd.nextDouble() < 0.2) {
            val bw = min((h * (0.12 + rnd.nextDouble() * 0.33)).roundToInt(), (gw * 0.34).roundToInt()).coerceAtLeast(2)
            clearColumn(gl - bw / 2 + rnd.nextInt(gw), bw)
        }
        // Fading: the whole digit, or one side (a printhead that runs cold towards one end).
        if (rnd.nextDouble() < 0.45) {
            val keep = 0.35 + rnd.nextDouble() * 0.5
            val side = rnd.nextInt(3) // 0 all, 1 left lighter, 2 right lighter
            for (x in 0 until w) {
                val f = when (side) { 0 -> keep; 1 -> keep + (1 - keep) * x / w.toDouble(); else -> 1 - (1 - keep) * x / w.toDouble() }
                for (y in 0 until hgt) img[x, y] = (235 - (235 - img[x, y]) * f).roundToInt().coerceIn(0, 255)
            }
        }
        // Thin partial streaks (lighter, not blank), as on the streaked Parcel Select label.
        if (rnd.nextDouble() < 0.25) repeat(1 + rnd.nextInt(2)) {
            val x0 = gl + rnd.nextInt(gw); val sw = max(1, (h * 0.05).roundToInt())
            for (x in x0 until min(w, x0 + sw)) for (y in 0 until hgt) img[x, y] = (img[x, y] + 200) / 2
        }
        img = Synth.blur(img, h * (0.015 + rnd.nextDouble() * 0.035))
        return Synth.noise(img, 2.0 + rnd.nextDouble() * 6, rnd)
    }

    private fun load(f: File): GrayImage {
        val bi = ImageIO.read(f); val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
        return GrayImage.fromArgb(bi.width, bi.height, px)
    }
}
