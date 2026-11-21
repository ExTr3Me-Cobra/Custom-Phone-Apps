package com.labelrescue.mltools

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ml.CharCrop
import com.labelrescue.core.ml.RefNet
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Curates the digit crops of an app export (training_crops/ with labels.csv) into the real-crop
 * training set (training/real_crops):
 *  1. Re-cut: every glyph in the old crop is cut the way the app now cuts (CharCrop: centered on
 *     one character, neighbors blanked); the glyph the digit model reads as the crop's label is
 *     kept (nearest the middle on a tie). Crops cut by older app versions often sat between two
 *     digits or spanned a whole group.
 *  2. Verify: dropped if no glyph reads as the label (damaged beyond reading, or a wrong label),
 *     if the digit is too small (tiny print) or too narrow for its digit (half a glyph), or if a
 *     line runs across it. Crops already in the set, or listed in excluded.txt, are skipped.
 *  3. review.png: a sheet of everything added, by digit, for a visual check; crops found wrong
 *     there go into excluded.txt and out of labels.csv.
 * Args: export crops dir, real_crops dir, models dir.
 */
fun main(args: Array<String>) {
    val src = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: error("-Pcrops=<export>/training_crops is required"))
    val dst = File(args[1]).apply { mkdirs() }
    val net = RefNet.load(File(args[2], "digit_classifier.lrw").readBytes()).runner()
    val labels = File(dst, "labels.csv")
    if (!labels.exists()) labels.writeText("file,digit,trusted,agreed_by,tracking\n")
    val have = labels.readLines().drop(1).map { it.substringBefore(',') }.toSet()
    val excluded = File(dst, "excluded.txt").takeIf { it.exists() }?.readLines()?.map { it.substringBefore('#').trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    fun load(f: File): GrayImage { val bi = ImageIO.read(f); val px = IntArray(bi.width * bi.height); bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width); return GrayImage.fromArgb(bi.width, bi.height, px) }
    fun top(p: FloatArray) = p.indices.maxBy { p[it] }

    val added = ArrayList<Pair<String, Int>>()
    val dropped = LinkedHashMap<String, String>()
    var skipped = 0
    for (line in File(src, "labels.csv").readLines().drop(1)) {
        val cols = line.split(",")
        if (cols.size < 5) continue
        val (file, label, trusted, agreed, tracking) = cols
        if (file in have || file in excluded) { skipped++; continue }
        val f = File(src, file).takeIf { it.exists() } ?: continue
        val d = label.toIntOrNull() ?: continue
        val img = load(f).scaledBy(3.0)
        val box = IntRect(27, 9, 69, 87)
        val match = CharCrop.glyphs(img, box).map { g -> g to CharCrop.extract(img, g) }
            .filter { (_, c) -> top(net.run(c, 32, 32)) == d }
            .minByOrNull { abs(it.first.centerX - 48) }
        val why = when {
            match == null -> "no glyph reads as $d"
            else -> shapeProblem(match.second, d)
        }
        if (why != null) { dropped[file] = why; continue }
        val crop = match!!.second
        val bi = BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until 32) for (x in 0 until 32) bi.raster.setSample(x, y, 0, 255 - (crop[y * 32 + x] * 255).toInt().coerceIn(0, 255))
        ImageIO.write(bi, "png", File(dst, file))
        labels.appendText("$file,$d,$trusted,$agreed,$tracking\n")
        added += file to d
    }
    // Review sheet of what was added, sorted by digit, numbered for excluded.txt.
    val sorted = added.sortedBy { it.second }
    if (sorted.isNotEmpty()) {
        val s = 40; val cols = 25; val rows = (sorted.size + cols - 1) / cols
        val sheet = BufferedImage(cols * (s + 2), rows * (s + 12), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = g.font.deriveFont(9f)
        val index = StringBuilder()
        for ((k, p) in sorted.withIndex()) {
            val x = (k % cols) * (s + 2); val y = (k / cols) * (s + 12)
            g.drawImage(ImageIO.read(File(dst, p.first)), x, y, s, s, null)
            g.color = Color.RED; g.drawString("${p.second}·$k", x + 1, y + s + 9)
            index.append("$k ${p.first}\n")
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(dst.parentFile, "real_crops_review.png").let { File(System.getProperty("java.io.tmpdir"), it.name) })
        File(System.getProperty("java.io.tmpdir"), "real_crops_review.txt").writeText(index.toString())
    }
    println("curated: ${added.size} added, ${dropped.size} dropped, $skipped already in the set or excluded")
    for ((k, v) in dropped.entries.groupBy({ it.value.substringBefore(" (") }, { it.key })) println("  dropped (${v.size}): $k")
    println("review sheet: ${File(System.getProperty("java.io.tmpdir"), "real_crops_review.png")}")
}

/** Why a re-cut crop of digit [d] isn't a good training example, or null. */
fun shapeProblem(c: FloatArray, d: Int): String? {
    val s = CharCrop.SIZE
    var l = s; var r = -1; var t = s; var b = -1
    val colInk = IntArray(s); val rowInk = IntArray(s)
    for (y in 0 until s) for (x in 0 until s) if (c[y * s + x] > 0.5f) { colInk[x]++; rowInk[y]++ }
    for (x in 0 until s) if (colInk[x] >= 2) { if (x < l) l = x; if (x > r) r = x }
    for (y in 0 until s) if (rowInk[y] >= 1) { if (y < t) t = y; if (y > b) b = y }
    if (r < 0 || b < 0) return "empty"
    val w = r - l + 1; val h = b - t + 1
    return when {
        h < 20 -> "too small (tiny print)"
        d != 1 && w < 0.45 * h -> "too narrow for a $d (half a glyph)"
        d != 1 && w > 0.9 * h -> "too wide (two glyphs)"
        rowInk.any { it >= 26 } -> "a line runs across it"
        else -> null
    }
}
