package com.labelrescue.core.ocr

import com.labelrescue.core.image.Affine
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrInterpreterTest {
    private val truth = "9234690275075500211709"

    /** Builds an OCR line like ML Kit would: 4-4-4-4-4-2 elements, 20 px pitch, 20 px group gap. */
    private fun line(text: String, drop: Set<Int> = emptySet(), replace: Map<Int, Pair<Char, Float>> = emptyMap(), prefix: Boolean = true): OcrLine {
        val elements = ArrayList<OcrElement>()
        if (prefix) elements += OcrElement("TRACKING", 0.9f, IntRect(0, 100, 150, 130), emptyList())
        var x = 200
        var i = 0
        for (g in TrackingFormat.groups(text.length)) {
            val syms = ArrayList<OcrSymbol>()
            for (k in 0 until g) {
                if (i !in drop) {
                    val (c, conf) = replace[i] ?: (text[i] to 0.95f)
                    syms += OcrSymbol(c.toString(), conf, IntRect(x, 100, x + 16, 130))
                }
                x += 20; i++
            }
            x += 20
            if (syms.isNotEmpty()) elements += OcrElement(syms.joinToString("") { it.text }, 0.9f, IntRect(syms.first().box!!.left, 100, syms.last().box!!.right, 130), syms)
        }
        return OcrLine(elements.joinToString(" ") { it.text }, 0.9f, IntRect(0, 100, x, 130), 0f, elements)
    }

    private fun interpret(l: OcrLine) = OcrInterpreter.interpret(OcrPage(listOf(l)), Affine.IDENTITY, 0, "test")

    @Test fun cleanLine() {
        val o = interpret(line(truth)).single()
        assertEquals(TrackingFormat.IMPB_22, o.format)
        assertEquals(truth, o.slots.joinToString("") { it!!.best.toString() })
    }

    @Test fun missingCharactersBecomeUnknownsInTheRightPlace() {
        val o = interpret(line(truth, drop = setOf(4, 12))).single()
        assertNull(o.slots[4]); assertNull(o.slots[12])
        for (i in truth.indices) if (i != 4 && i != 12) assertEquals("pos $i", truth[i], o.slots[i]!!.best)
        // The box for the missing digit sits where it should be (pitch 20, group gap 20).
        val b = o.boxes[4]!!
        assertTrue(b.centerX in 300.0..318.0) // slot 4 starts at 200 + 4·20 + 20
    }

    @Test fun missingFirstCharacter() {
        val o = interpret(line(truth, drop = setOf(0))).single()
        assertNull(o.slots[0])
        assertEquals(truth.substring(1), o.slots.drop(1).joinToString("") { it!!.best.toString() })
    }

    @Test fun lowConfidenceGivesRankedAlternatives() {
        val o = interpret(line(truth, replace = mapOf(4 to ('8' to 0.3f), 9 to ('O' to 0.8f)))).single()
        val alt = o.slots[4]!!.ranked().map { it.first }.take(4)
        assertEquals('8', alt[0])
        assertTrue('6' in alt) // the truth is among the usual 8 confusions
        assertEquals('0', o.slots[9]!!.best)
    }

    @Test fun lookalikeTable() {
        assertEquals(0, DigitConfusion.digitFor('O')!!.first)
        assertEquals(5, DigitConfusion.digitFor('S')!!.first)
        assertNull(DigitConfusion.digitFor('#'))
    }

    @Test fun ignoresShortNumbers() {
        val l = OcrLine("ZIP 12345", 0.9f, null, 0f, listOf(OcrElement("12345", 0.9f, null, emptyList())))
        assertTrue(interpret(l).isEmpty())
    }

    @Test fun alignmentHandlesMergedGroups() {
        // OCR merged groups into one element and lost one char: x positions still place it.
        val us = (0 until 22).filter { it != 7 }.map { 20.0 * it + 20.0 * (it / 4) }
        val a = OcrInterpreter.alignForTest(us, List(us.size) { 0 }, TrackingFormat.IMPB_22)!!
        assertEquals(-1, a.assigned[7])
        assertEquals(21, a.matched)
    }
}
