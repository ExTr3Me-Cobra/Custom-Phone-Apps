package com.labelrescue.core.ups

import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat
import com.labelrescue.core.verify.HttpResponse
import com.labelrescue.core.verify.HttpTransport
import com.labelrescue.core.verify.KnownNumberPrior
import com.labelrescue.core.verify.UpsLookup
import com.labelrescue.core.verify.UpsTrackingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpsTest {
    // Published test numbers (tracking_number_data): valid and invalid.
    private val valid = listOf("1Z5R89390357567127", "1Z879E930346834440", "1Z410E7W0392751591", "1Z8V92A70367203024", "1ZXX3150YW44070023")

    @Test fun checkDigit() {
        for (v in valid) assertTrue(v, UpsNumber.isValid(v))
        for (v in listOf("2Z5R89390357567127", "1A5R89390357567127", "1Z1111111111111111", "1Z5R89390357567128")) assertFalse(v, UpsNumber.isValid(v))
        assertTrue(UpsNumber("1ZXX3150YW44070023", "t").isUspsHandoff)
        assertEquals("1Z 5R8 939 03 5756 712 7", UpsNumber("1Z5R89390357567127", "t").grouped)
    }

    @Test fun findsPrintedAndOcrDamagedNumbers() {
        assertEquals(listOf("1Z8V92A70367203024"), UpsNumber.find("TRACKING #: 1Z 8V9 2A7 03 6720 302 4\nBILLING: P/P", "ocr").map { it.tracking })
        // OCR read the 0 as O: one lookalike swap restores the check digit.
        val fixed = UpsNumber.find("1Z 5R8 939 O3 5756 712 7", "ocr", allowCorrection = true).single()
        assertEquals("1Z5R89390357567127", fixed.tracking); assertTrue(fixed.corrected)
        // "1Z" read as "I2".
        assertEquals("1Z879E930346834440", UpsNumber.find("I2 879 E93 03 4683 444 0", "ocr", allowCorrection = true).single().tracking)
        assertTrue(UpsNumber.find("1Z 5R8 939 O3 5756 712 7", "ocr").isEmpty())
    }

    @Test fun maxiCodeShortFormIsRebuilt() {
        val payload = "[)>\u001E01\u001D96841706672\u001D840\u001D003\u001D1Z57567127\u001DUPSN\u001D5R8939\u001D195\u001D\u001D1/1\u001D\u001DY\u001D\u001E\u0004"
        val rebuilt = UpsNumber.fromMaxiCode(payload).map { it.tracking }
        assertTrue(rebuilt.toString(), "1Z5R89390357567127" in rebuilt)
        assertTrue(rebuilt.size <= 10)
    }

    private val uspsNumber = "9234690275075500211709"

    private class FakeUps(val track: (String) -> HttpResponse) : HttpTransport {
        var tokens = 0
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            if (url.contains("/security/v1/oauth/token")) {
                tokens++
                assertTrue(headers["Authorization"]!!.startsWith("Basic "))
                assertEquals("grant_type=client_credentials", body)
                return HttpResponse(200, """{"token_type":"Bearer","issued_at":"1","client_id":"id","access_token":"tokU","expires_in":"14399","status":"approved"}""")
            }
            assertEquals("Bearer tokU", headers["Authorization"])
            assertTrue(headers.containsKey("transId") && headers.containsKey("transactionSrc"))
            return track(url.substringAfter("/details/").substringBefore("?"))
        }
    }

    @Test fun lookupFindsTheUspsNumberAmongAlternates() {
        assertTrue(CheckDigits.isValidMod10(uspsNumber))
        val fake = FakeUps { n ->
            HttpResponse(200, """{"trackResponse":{"shipment":[{"inquiryNumber":"$n","package":[{"trackingNumber":"$n",
                "currentStatus":{"description":"In Transit","code":"IT"},
                "alternateTrackingNumber":[{"number":"OTHER123","type":"X"},{"number":"$uspsNumber","type":"USPS PIC"}]}]}]}}""")
        }
        val c = UpsTrackingClient({ "id" to "secret" }, fake)
        val r = c.lookup("1ZXX3150YW44070023") as UpsLookup.Found
        assertEquals(listOf(uspsNumber), r.usps.map { it.tracking })
        assertEquals("In Transit", r.status)
        c.lookup("1ZXX3150YW44070023")
        assertEquals("token is cached", 1, fake.tokens)
    }

    @Test fun lookupWithoutUspsNumberOrUnknown() {
        val none = UpsTrackingClient({ "id" to "s" }, FakeUps { n -> HttpResponse(200, """{"trackResponse":{"shipment":[{"package":[{"trackingNumber":"$n","currentStatus":{"description":"Label Created"}}]}]}}""") })
        val r = none.lookup("1Z5R89390357567127") as UpsLookup.Found
        assertTrue(r.usps.isEmpty())
        val missing = UpsTrackingClient({ "id" to "s" }, FakeUps { HttpResponse(404, """{"response":{"errors":[{"code":"TW0001","message":"Tracking Information Not Found"}]}}""") })
        assertEquals(UpsLookup.NotFound("Tracking Information Not Found"), missing.lookup("1Z5R89390357567127"))
        val busy = UpsTrackingClient({ "id" to "s" }, FakeUps { HttpResponse(429, "") })
        assertTrue(busy.lookup("1Z5R89390357567127") is UpsLookup.Retryable)
        assertTrue(UpsTrackingClient({ null }, FakeUps { HttpResponse(200, "") }).lookup("1Z5R89390357567127") is UpsLookup.Fatal)
    }

    @Test fun knownNumberRanksFirstWhenTheLabelAllowsIt() {
        // Damaged label: two digits unknown; the UPS lookup supplies the number.
        val f = TrackingFormat.IMPB_22
        val slots = uspsNumber.mapIndexed { i, c -> if (i == 4 || i == 11) SlotDist.uniform(SlotDist.DIGITS) else SlotDist.known(c, SlotDist.DIGITS) }
        val r = CandidateEngine().rank(f, slots, KnownNumberPrior(setOf(uspsNumber)))
        assertEquals(uspsNumber, r.best!!.tracking)
        assertTrue(r.best!!.confidence > 0.99)
    }
}
