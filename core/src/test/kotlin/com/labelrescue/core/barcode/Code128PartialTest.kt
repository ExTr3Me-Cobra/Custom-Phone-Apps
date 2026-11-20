package com.labelrescue.core.barcode

import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.synth.Synth
import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.ImpbParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Code128PartialTest {
    private val tracking = "9234690275075500211709"
    private val zip = "12345"
    private val values = Code128.impbValues(tracking, zip)
    private val module = 3.0
    private val decoder = Code128Partial()

    private fun decode(img: GrayImage) = decoder.decode(decoder.measure(img))

    /** x of the left edge of symbol [k] (0 = start) in a rendered barcode. */
    private fun symbolX(k: Int) = ((12 + 11 * k) * module).toInt()

    @Test fun tableIsConsistent() {
        for (v in 0 until 106) assertEquals("value $v", 11, Code128.PATTERNS[v].sum())
        assertEquals(13, Code128.PATTERNS[Code128.STOP].sum())
        assertEquals(107, Code128.PATTERNS.map { it.joinToString("") }.toSet().size)
    }

    @Test fun zxingReadsOurRendering() {
        val img = Synth.renderBarcode(values, module)
        val reads = ZxingDecoder().decode(img)
        val found = reads.flatMap { ImpbParser.extractFromPayload(it.text) }
        assertEquals(tracking, found.single().tracking)
        assertEquals(zip, found.single().zip)
    }

    @Test fun cleanBarcode() {
        val r = decode(Synth.renderBarcode(values, module))
        assertNotNull(r)
        assertEquals("420+ZIP5+22", r!!.structure.name)
        assertEquals(tracking, r.bestTracking)
        assertTrue(r.trackingDigits.all { it.bestP > 0.99 })
        assertEquals(zip, r.zipDigits.joinToString("") { it.best.toString() })
    }

    @Test fun oneSymbolDestroyedIsRecoveredByChecksum() {
        // Symbol 9 holds tracking digits 4-5 ("69").
        var img = Synth.renderBarcode(values, module)
        img = Synth.verticalLoss(img, symbolX(9) + 2, (11 * module).toInt() - 2)
        assertTrue(ZxingDecoder().decode(img).isEmpty())
        val r = decode(img)!!
        assertEquals(tracking, r.bestTracking)
        val rec = r.recovered.filter { it.position in 4..5 }
        assertEquals(2, rec.size)
        assertTrue(rec.all { it.confidence > 0.95 && it.rawConfidence < 0.5 })
    }

    @Test fun twoSymbolsDestroyedStillRankTruthHigh() {
        var img = Synth.renderBarcode(values, module)
        img = Synth.verticalLoss(img, symbolX(9) + 2, (11 * module).toInt() - 2)
        img = Synth.verticalLoss(img, symbolX(14) + 2, (11 * module).toInt() - 2)
        val r = decode(img)!!
        // With two symbols lost there are ~10 checksum-consistent completions; the exact
        // joint likelihood must put the truth among the top ones.
        val ll = r.logLikelihood(tracking)
        assertTrue(ll.isFinite())
        val wrong = tracking.substring(0, 4) + "1" + tracking.substring(5)
        assertTrue(ll > r.logLikelihood(wrong))
    }

    @Test fun startTornOffUsesStopAnchor() {
        var img = Synth.renderBarcode(values, module)
        img = Synth.tear(img, IntRect(0, 0, symbolX(1), img.height))
        val r = decode(img)!!
        assertEquals(tracking, r.bestTracking)
    }

    @Test fun stopTornOff() {
        var img = Synth.renderBarcode(values, module)
        img = Synth.tear(img, IntRect(symbolX(values.size - 1) - 3, 0, img.width, img.height))
        val r = decode(img)!!
        assertEquals(tracking, r.bestTracking)
    }

    @Test fun blurNoiseSmudgesAndDropouts() {
        val rnd = Random(11)
        var img = Synth.renderBarcode(values, 3.4)
        img = Synth.blur(img, 1.0)
        img = Synth.inkDropout(img, rnd, 10, 10)
        img = Synth.smudge(img, rnd, 4, 8)
        img = Synth.noise(img, 8.0, rnd)
        val r = decode(img)!!
        assertEquals(tracking, r.bestTracking)
    }

    @Test fun upsideDown() {
        val img = Synth.renderBarcode(values, module).rotated90(2).first
        assertEquals(tracking, decode(img)!!.bestTracking)
    }

    @Test fun otherLayouts() {
        val t26 = "9505512345678901234567890".let { it + CheckDigits.mod10(it) }
        for ((t, z) in listOf(tracking to null, tracking to "123456789", t26 to null)) {
            val r = decode(Synth.renderBarcode(Code128.impbValues(t, z), module))!!
            assertEquals(t, r.bestTracking)
        }
    }
}
