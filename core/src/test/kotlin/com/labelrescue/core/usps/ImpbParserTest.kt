package com.labelrescue.core.usps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpbParserTest {
    private fun withCheck(payload: String) = payload + CheckDigits.mod10(payload)

    @Test fun parse22WithNineDigitMid() {
        val p = ImpbParser.parse("9234 6902 7507 5500 2117 09")!!
        assertEquals(TrackingFormat.IMPB_22, p.format)
        assertEquals("92", p.channel)
        assertEquals("346", p.serviceType)
        assertEquals("902750755", p.mailerId)
        assertEquals("0021170", p.serial)
        assertEquals('9', p.checkDigit)
        assertTrue(p.checkValid)
        assertTrue(p.warnings.isEmpty())
    }

    @Test fun parse22WithSixDigitMid() {
        // Channel 93: commercial mailer with a six-digit Mailer ID, 10-digit serial (Publication 199).
        val n = withCheck("930010123450000000001")
        val p = ImpbParser.parse(n)!!
        assertEquals("93", p.channel); assertEquals("001", p.serviceType)
        assertEquals("012345", p.mailerId); assertEquals("0000000001", p.serial)
        assertTrue(p.checkValid)
    }

    @Test fun channel94HasASourceIdBeforeTheMailerId() {
        // PC Postage / online: STC, 2-digit source ID, then the Mailer ID (9 digits from a 9, else 6).
        val six = ImpbParser.parse(withCheck("940011012345600000001"))!!
        assertEquals("10", six.sourceId); assertEquals("123456", six.mailerId); assertEquals(7, six.mailerIdStart)
        assertEquals("00000001", six.serial)
        val nine = ImpbParser.parse(withCheck("940011090123456700001"))!!
        assertEquals("901234567", nine.mailerId); assertEquals("00001", nine.serial)
    }

    @Test fun channel92MailerIdStartsWith9AndTheServiceCodeIsChecked() {
        // A real label: 92, STC 612 (on USPS's list), MID 909888642, 11-digit serial.
        val p = ImpbParser.parse("92612909888642571048257340")!!
        assertEquals("909888642", p.mailerId); assertEquals("57104825734", p.serial)
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
        val bad = ImpbParser.parse(withCheck("920001234567890123456"))!!
        assertTrue(bad.warnings.any { "Mailer ID" in it })
        assertTrue(bad.warnings.any { "not on USPS's list" in it })
    }

    @Test fun parse26() {
        val n = withCheck("9505512345678901234567890")
        val p = ImpbParser.parse(n)!!
        assertEquals(TrackingFormat.IMPB_26, p.format)
        assertEquals("055", p.serviceType); assertEquals("123456", p.mailerId)
        assertTrue(p.checkValid)
    }

    @Test fun parse30And34() {
        val t = "9234690275075500211709"
        val p30 = ImpbParser.parse("42012345$t")!!
        assertEquals(TrackingFormat.ROUTED_30, p30.format)
        assertEquals("12345", p30.zip); assertEquals(t, p30.tracking); assertTrue(p30.checkValid)
        val p34 = ImpbParser.parse("420123456789$t")!!
        assertEquals(TrackingFormat.ROUTED_34_ZIP9, p34.format)
        assertEquals("123456789", p34.zip); assertEquals(t, p34.tracking)
        val t26 = withCheck("9505512345678901234567890")
        val p34b = ImpbParser.parse("42012345$t26")!!
        assertEquals(TrackingFormat.ROUTED_34_ZIP5, p34b.format)
        assertEquals(t26, p34b.tracking)
    }

    @Test fun parse20AndS10() {
        val n = withCheck("0307179000052348374")
        val p = ImpbParser.parse(n)!!
        assertEquals(TrackingFormat.IMPB_20, p.format); assertTrue(p.checkValid)
        val s = ImpbParser.parse("ra473124829us")!!
        assertEquals(TrackingFormat.S10, s.format); assertTrue(s.checkValid); assertEquals("47312482", s.serial)
    }

    @Test fun unknownChannelWarns() {
        val p = ImpbParser.parse(withCheck("812345678901234567890"))!!
        assertTrue(p.warnings.isNotEmpty())
    }

    @Test fun rejectsOtherLengths() {
        assertNull(ImpbParser.parse("12345"))
        assertNull(ImpbParser.parse("1234567890123456789012345678")) // 28
    }

    @Test fun extractsFromGs1Payload() {
        val t = "9234690275075500211709"
        val found = ImpbParser.extractFromPayload("]C142012345\u001D$t")
        assertEquals(t, found.single().tracking)
        assertEquals("12345", found.single().zip)
        assertEquals(t, ImpbParser.extractFromPayload("42012345$t").single().tracking)
    }

    @Test fun serviceHints() {
        val h = ServiceHints.serviceHints("USPS GROUND ADVANTAGE ™\nSHIP TO: ... 12345-6789")
        assertEquals("Ground Advantage", h.single().service)
        assertEquals(listOf("123456789"), ServiceHints.zips("SHIP TO 12345-6789"))
        assertEquals("Priority Mail Express", ServiceHints.serviceHints("PRIORITY MAIL EXPRESS 1-DAY").single().service)
    }

    @Test fun grouping() {
        assertEquals("9234 6902 7507 5500 2117 09", TrackingFormat.grouped("9234690275075500211709"))
        assertEquals(listOf(4, 4, 4, 4, 4, 4, 2), TrackingFormat.groups(26))
    }
}

class ServiceHintsFuzzyTest {
    @org.junit.Test fun damagedServiceNames() {
        org.junit.Assert.assertEquals("Ground Advantage", ServiceHints.serviceHints("SP' GROUN) ADVANTAGE").single().service)
        org.junit.Assert.assertEquals("Ground Advantage", ServiceHints.serviceHints("UNITED STATES\nG\nMailed from 08081").single().service)
        org.junit.Assert.assertEquals("Priority Mail Express", ServiceHints.serviceHints("PRIORITY MAIL EXPRES5").single().service)
        org.junit.Assert.assertTrue(ServiceHints.serviceHints("SHIP TO JANE").isEmpty())
    }

    @Test fun gs1HumanReadableFormKeepsTheChannel() {
        // As some decoders return it: brackets around the GS1 labels. "(93)" belongs to the number.
        // A real label: split at the brackets, "93" fell off and the other 20 digits passed as a
        // legacy 20-digit number (their check digit happens to be valid too).
        fun first(p: String) = ImpbParser.extractFromPayload(p).first { it.checkValid }
        val a = first("(420)056418373(93)00120787713680351566")
        org.junit.Assert.assertEquals("9300120787713680351566", a.tracking)
        org.junit.Assert.assertEquals("056418373", a.zip)
        org.junit.Assert.assertEquals("9300120787713680351566", first("(420)05641(93)00120787713680351566").tracking)
        // From real labels (zxing-cpp text): ZIP+4 with a 22-digit number, ZIP5 with a 26-digit one.
        org.junit.Assert.assertEquals("9334611047400005115178", first("(420)610088786(93)34611047400005115178").tracking)
        org.junit.Assert.assertEquals("92612909888642571048257340", first("(420)05641(92)612909888642571048257340").tracking)
        // The raw GS-separated form still works.
        org.junit.Assert.assertEquals("9300120787713680351566", first("42005641\u001D9300120787713680351566").tracking)
        org.junit.Assert.assertEquals("9300120787713680351566", first("]C142005641\u001D9300120787713680351566").tracking)
    }
}
