package com.labelrescue.core.usps

import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedLayoutTest {
    private fun line(t: String, l: Int, top: Int, r: Int, h: Int = 24) = OcrLine(t, 0.9f, null, 0f, emptyList()) to IntRect(l, top, r, top + h)

    /** Intact labels photographed at different distances: barcode 6 line heights tall, 1.5 lines above the number. */
    private fun label(scale: Double, dx: Int, dy: Int): Pair<LabelZones.Anchors, IntRect> {
        fun s(v: Int) = (v * scale).toInt()
        val number = IntRect(dx + s(200), dy + s(1180), dx + s(880), dy + s(1204))
        val bar = IntRect(dx + s(230), dy + s(1000), dx + s(850), dy + s(1144))
        return LabelZones.Anchors(null, null, number) to bar
    }

    @Test
    fun learnsWhereTheUspsBarcodeSits() {
        val samples = listOf(label(1.0, 0, 0), label(0.8, 60, 100), label(1.2, -40, -150), label(0.9, 10, 40)).flatMapIndexed { i, (a, bar) ->
            LayoutSample.fromLabel(a, bar, null, "shipper$i")
        }
        assertEquals(4, samples.size)
        val learned = LearnedLayout.decode(LearnedLayout(samples).encode())
        assertEquals(4, learned.size)
        // A damaged label: only the number line was read, at another scale and place.
        val (a, bar) = label(1.1, 30, -80)
        val area = learned.area(LayoutSample.Target.USPS, LayoutSample.Anchor.NUMBER, a.number!!)
        assertNotNull(area)
        assertTrue("$area should hold $bar", area!!.left <= bar.left && area.right >= bar.right && area.top <= bar.top && area.bottom >= bar.bottom)
        // ...and not much more than it.
        assertTrue(area.height < bar.height * 1.8)

        val z = LabelZones.find(listOf(OcrLine("9261", 0.9f, null, 0f, emptyList()) to a.number), listOf(a.number), 1600, 2000, learned)
        assertTrue(z.learned)
        assertEquals(area, z.usps)
    }

    @Test
    fun oneShipperCountsAtMostFiveTimes() {
        val (a, bar) = label(1.0, 0, 0)
        val samples = List(12) { LayoutSample.fromLabel(a, bar, null, "same").single() }
        assertEquals(LearnedLayout.PER_SHIPPER, LearnedLayout(samples).size)
    }

    @Test
    fun needsTwoLabelsBeforeUsingIt() {
        val (a, bar) = label(1.0, 0, 0)
        val one = LearnedLayout(LayoutSample.fromLabel(a, bar, null, "x"))
        assertNull(one.area(LayoutSample.Target.USPS, LayoutSample.Anchor.NUMBER, a.number!!))
    }

    @Test
    fun misreadAnchorSamplesAreDropped() {
        // From a real export: the barcode 25-31 line heights below its "number line" (an account ID was taken for it).
        val bad = LayoutSample.decode("USPS|NUMBER|-1.167|1.251|25.345|31.448|301093")!!
        val good = LayoutSample.decode("USPS|NUMBER|-0.684|1.376|-6.061|0.788|896840")!!
        assertTrue(!bad.plausible && good.plausible)
        assertEquals(1, LearnedLayout(listOf(bad, good)).size)
    }
}
