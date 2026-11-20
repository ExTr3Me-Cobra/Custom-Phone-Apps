package com.labelrescue.core.verify

import com.labelrescue.core.usps.ImpbParser
import com.labelrescue.core.usps.ParsedNumber
import java.io.IOException
import java.util.Base64
import java.util.UUID

/** What UPS said about a 1Z number. */
sealed class UpsLookup {
    /** UPS knows the package. [usps]: the USPS numbers tied to it (valid check digit), if any. */
    data class Found(val usps: List<ParsedNumber>, val alternates: List<Pair<String, String?>>, val status: String?) : UpsLookup()
    data class NotFound(val message: String?) : UpsLookup()
    data class Retryable(val reason: String, val retryAfterMs: Long? = null) : UpsLookup()
    data class Fatal(val reason: String) : UpsLookup()
}

/**
 * UPS Tracking API client (developer.ups.com), OAuth 2.0 client credentials.
 *
 * - Token: POST /security/v1/oauth/token, HTTP Basic (client id:secret), form body
 *   grant_type=client_credentials; cached until shortly before `expires_in`.
 * - Tracking: GET /api/track/v1/details/{inquiryNumber} with transId and transactionSrc headers.
 *   For packages UPS hands to USPS (SurePost / Ground Saver) the response can list the USPS number
 *   in `alternateTrackingNumber` ({number, type}). UPS doesn't document the type values, so any
 *   alternate number that parses as a USPS number with a valid check digit is taken, and nothing else.
 */
class UpsTrackingClient(
    private val credentials: () -> Pair<String, String>?,
    private val transport: HttpTransport = JavaHttpTransport(),
    private val store: TokenStore = TokenStore.Memory(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val base: String = PRODUCTION,
) {
    private var token: Pair<String, Long>? = null
    var tokenRequests = 0
        private set

    private fun cachedToken(): String? {
        val t = token ?: store.load()?.also { token = it }
        return t?.takeIf { clock() < it.second }?.first
    }

    private fun fetchToken(id: String, secret: String): Any {
        tokenRequests++
        val basic = Base64.getEncoder().encodeToString("$id:$secret".toByteArray())
        val r = try {
            transport.request(
                "POST", "$base/security/v1/oauth/token",
                mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                "grant_type=client_credentials",
            )
        } catch (e: IOException) {
            return UpsLookup.Retryable("Network error: ${e.message}")
        }
        if (r.code == 429 || r.code >= 500) return UpsLookup.Retryable("UPS sign-in busy (${r.code})", UspsTrackingClient.retryAfter(r))
        if (r.code !in 200..299) return UpsLookup.Fatal("UPS sign-in failed (${r.code}). Check the UPS Client ID and Secret in Settings.")
        val m = runCatching { Json.parse(r.body) as Map<*, *> }.getOrNull() ?: return UpsLookup.Retryable("Unreadable UPS sign-in response")
        val t = m["access_token"] as? String ?: return UpsLookup.Fatal("UPS sign-in returned no token")
        val expiresIn = when (val e = m["expires_in"]) { is Number -> e.toLong(); is String -> e.toLongOrNull(); else -> null } ?: 3600L
        val at = clock() + (expiresIn - 60).coerceAtLeast(30) * 1000
        token = t to at
        store.save(t, at)
        return t
    }

    fun lookup(ups: String): UpsLookup {
        val (id, secret) = credentials() ?: return UpsLookup.Fatal("No UPS credentials. Add them in Settings.")
        var retried = false
        while (true) {
            val tok = cachedToken() ?: when (val t = fetchToken(id, secret)) { is String -> t; is UpsLookup -> return t; else -> return UpsLookup.Fatal("sign-in") }
            val r = try {
                transport.request(
                    "GET", "$base/api/track/v1/details/$ups?locale=en_US&returnSignature=false&returnMilestones=false",
                    mapOf(
                        "Authorization" to "Bearer $tok", "Accept" to "application/json",
                        "transId" to UUID.randomUUID().toString().replace("-", "").take(32), "transactionSrc" to "LabelRescue",
                    ),
                    null,
                )
            } catch (e: IOException) {
                return UpsLookup.Retryable("Network error: ${e.message}")
            }
            when {
                r.code == 401 && !retried -> { token = null; store.clear(); retried = true }
                r.code == 401 -> return UpsLookup.Fatal("UPS refused the token (401). Check the credentials.")
                r.code == 403 -> return UpsLookup.Fatal("This UPS app has no Tracking access (403). Add the Tracking product to your app on developer.ups.com.")
                r.code == 429 -> return UpsLookup.Retryable("Rate limited by UPS (429)", UspsTrackingClient.retryAfter(r))
                r.code >= 500 -> return UpsLookup.Retryable("UPS server error (${r.code})", UspsTrackingClient.retryAfter(r))
                r.code in 200..299 -> return parse(r.body)
                else -> return UpsLookup.NotFound(errorMessage(r.body) ?: "HTTP ${r.code}")
            }
        }
    }

    companion object {
        const val PRODUCTION = "https://onlinetools.ups.com"
        const val TEST = "https://wwwcie.ups.com"

        /** Pulls the alternate numbers (and any USPS number among them) out of a track response. */
        fun parse(body: String): UpsLookup {
            val root = runCatching { Json.parse(body) }.getOrNull() ?: return UpsLookup.Retryable("Unreadable UPS response")
            val alts = ArrayList<Pair<String, String?>>()
            var status: String? = null
            var warning: String? = null
            fun walk(x: Any?) {
                when (x) {
                    is Map<*, *> -> {
                        (x["alternateTrackingNumber"] as? List<*>)?.forEach { a ->
                            (a as? Map<*, *>)?.let { m -> (m["number"] as? String)?.let { n -> alts += n.trim() to (m["type"] as? String) } }
                        }
                        if (status == null) ((x["currentStatus"] as? Map<*, *>)?.get("description") as? String)?.let { status = it.trim() }
                        if (warning == null) ((x["warnings"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.let { warning = it["message"] as? String }
                        x.values.forEach(::walk)
                    }
                    is List<*> -> x.forEach(::walk)
                }
            }
            walk(root)
            val hasPackage = Regex("\"package\"\\s*:").containsMatchIn(body)
            if (!hasPackage && alts.isEmpty()) return UpsLookup.NotFound(warning ?: errorMessage(body))
            val usps = alts.mapNotNull { (n, _) ->
                val digits = n.filter { it.isLetterOrDigit() }.uppercase()
                ImpbParser.parse(digits)?.takeIf { it.checkValid } ?: ImpbParser.extractFromPayload(digits).firstOrNull { it.checkValid }
            }.distinctBy { it.tracking }
            return UpsLookup.Found(usps, alts, status)
        }

        private fun errorMessage(body: String): String? = runCatching {
            Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        }.getOrNull()
    }
}
