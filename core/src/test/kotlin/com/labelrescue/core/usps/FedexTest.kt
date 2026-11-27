package com.labelrescue.core.usps

import org.junit.Assert.assertTrue
import org.junit.Test

class FedexTest {
    /** FedEx Ground's own barcode ("96" + 32 digits) is never a USPS number. */
    @Test fun fedexGroundBarcodeIsNotUsps() {
        val found = ImpbParser.extractFromPayload("9621091390000000000900384165467480")
        assertTrue("found $found", found.isEmpty())
        assertTrue(ImpbParser.parse("9621091390000000000900384165467480").let { it == null || !it.checkValid || it.channel !in ImpbParser.CHANNELS })
    }
}
