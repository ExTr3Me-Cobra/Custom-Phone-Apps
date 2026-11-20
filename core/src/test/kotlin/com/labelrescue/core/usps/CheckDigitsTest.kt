package com.labelrescue.core.usps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckDigitsTest {
    @Test fun mod10KnownNumbers() {
        assertTrue(CheckDigits.isValidMod10("9234190275070500211709"))
        assertTrue(CheckDigits.isValidMod10("9234690275075500211709"))
        assertEquals(9, CheckDigits.mod10("923419027507050021170"))
    }

    @Test fun mod10RejectsSingleDigitErrors() {
        val good = "9234190275070500211709"
        for (i in good.indices) for (d in '0'..'9') {
            if (d == good[i]) continue
            val bad = good.substring(0, i) + d + good.substring(i + 1)
            assertFalse("$bad should fail", CheckDigits.isValidMod10(bad))
        }
    }

    @Test fun mod10WeightsFromTheRight() {
        // Payload "1": weight 3 -> sum 3 -> check 7.
        assertEquals(7, CheckDigits.mod10("1"))
        // Payload "10": rightmost 0 weight 3, then 1 weight 1 -> sum 1 -> check 9.
        assertEquals(9, CheckDigits.mod10("10"))
        assertFalse(CheckDigits.isValidMod10("92A4"))
    }

    @Test fun s10() {
        // UPU example: 47312482 -> weights 8 6 4 2 3 5 9 7 -> sum 200 -> 200 mod 11 = 2 -> 11-2 = 9.
        assertEquals(9, CheckDigits.s10("47312482"))
        assertTrue(CheckDigits.isValidS10("RA473124829US"))
        assertFalse(CheckDigits.isValidS10("RA473124828US"))
        assertFalse(CheckDigits.isValidS10("R1473124829US"))
        // Remainder 0 -> 11 -> check 5; remainder 1 -> 10 -> check 0.
        assertEquals(5, CheckDigits.s10FromSum(22))
        assertEquals(0, CheckDigits.s10FromSum(23))
    }

    @Test fun code128Checksum() {
        // Start C (105), values 12, 34 -> 105 + 12*1 + 34*2 = 185 -> 185 mod 103 = 82.
        assertEquals(82, CheckDigits.code128(intArrayOf(105, 12, 34)))
    }
}
