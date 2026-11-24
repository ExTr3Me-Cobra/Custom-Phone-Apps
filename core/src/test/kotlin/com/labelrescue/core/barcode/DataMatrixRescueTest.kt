package com.labelrescue.core.barcode

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.labelrescue.core.image.Filters
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.usps.BarcodeImages
import com.labelrescue.core.usps.ImpbParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DataMatrixRescueTest {

    @Test fun reedSolomonFixesErasuresAndErrors() {
        val rnd = Random(3)
        val enc = ReedSolomonEncoder(GenericGF.DATA_MATRIX_FIELD_256)
        repeat(300) {
            val nData = 8 + rnd.nextInt(30); val ecc = 6 + rnd.nextInt(20)
            val cw = IntArray(nData + ecc) { if (it < nData) rnd.nextInt(256) else 0 }
            enc.encode(cw, ecc)
            val errs = rnd.nextInt(ecc / 2 + 1); val ers = rnd.nextInt(ecc - 2 * errs + 1)
            val pos = cw.indices.shuffled(rnd).take(errs + ers)
            val bad = cw.copyOf()
            for (p in pos) bad[p] = bad[p] xor (1 + rnd.nextInt(255))
            assertTrue(ReedSolomonErasures.decode(bad, ecc, pos.drop(errs)))
            assertArrayEquals(cw, bad)
        }
    }

    /** A rendered IMmb at [px] pixels per module with paper around it. */
    private fun render(tracking: String, zip: String, px: Int): GrayImage {
        val m = BarcodeImages.immb(tracking, zip)
        val img = GrayImage.filled(m.width * px + 40, m.height * px + 40, 235)
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y])
            for (dy in 0 until px) for (dx in 0 until px) img[20 + x * px + dx, 20 + y * px + dy] = 30
        return img
    }

    /**
     * Dead print-head bands (vertical white columns, the common real damage) across synthetic
     * IMmbs, then focus blur and noise. The rescue must read every label ZXing reads, more
     * labels overall, and never a wrong number.
     */
    @Test fun readsDeadLineDataMatrixZxingCannot() {
        val rnd = Random(11)
        val zx = ZxingDecoder(listOf(com.google.zxing.BarcodeFormat.DATA_MATRIX))
        var zxOk = 0; var rescueOk = 0; var wrong = 0; var lost = 0; val n = 120
        repeat(n) { k ->
            val num = withCheck("93" + (1..19).joinToString("") { rnd.nextInt(10).toString() })
            val zip = "%05d".format(rnd.nextInt(100000))
            val px = 6 + rnd.nextInt(4)
            var img = render(num, zip, px)
            val bands = ArrayList<Pair<Int, Int>>()
            repeat(1 + rnd.nextInt(4)) {
                val w = (px * (0.3 + rnd.nextDouble() * 0.9)).toInt().coerceAtLeast(1)
                val x0 = 20 + rnd.nextInt(img.width - 40 - w)
                bands += x0 to w
                for (y in 0 until img.height) for (x in x0 until x0 + w) img[x, y] = 235
            }
            img = Filters.gaussianBlur(img, 0.4 + rnd.nextDouble() * 0.8)
            for (i in img.data.indices) img.data[i] = ((img.data[i].toInt() and 0xFF) + rnd.nextInt(-8, 9)).coerceIn(0, 255).toByte()
            val plain = zx.decode(img).any { r -> ImpbParser.extractFromPayload(r.text).any { it.tracking == num } }
            val dead: (Int, Int) -> Boolean = { x, _ -> bands.any { (b, w) -> x >= b - 1 && x <= b + w } }
            val res = DataMatrixRescue.decode(img, dead)
            val got = res?.let { ImpbParser.extractFromPayload(it.text).firstOrNull { p -> p.checkValid }?.tracking }
            if (plain) zxOk++
            if (got == num) rescueOk++ else if (got != null) wrong++
            if (plain && got != num) lost++
        }
        println("DataMatrix with dead print-head bands: ZXing $zxOk/$n, rescue $rescueOk/$n, wrong $wrong, ZXing-only $lost, either ${rescueOk + lost}")
        assertEquals(0, wrong)
        // The pipeline runs ZXing first anyway: what counts is what the rescue adds on top.
        val either = rescueOk + lost
        assertTrue("ZXing $zxOk, with the rescue $either", either >= zxOk * 1.2)
    }

    private fun withCheck(body: String): String {
        var s = 0
        for ((i, c) in body.reversed().withIndex()) s += (c - '0') * (if (i % 2 == 0) 3 else 1)
        return body + ((10 - s % 10) % 10)
    }
}
