package com.labelrescue.core.verify

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface HttpTransport {
    /** Throws IOException on network failure. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/** Plain HttpURLConnection (works on the JVM and Android). */
class JavaHttpTransport(private val connectTimeoutMs: Int = 15000, private val readTimeoutMs: Int = 20000) : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            return HttpResponse(code, text, h)
        } finally {
            c.disconnect()
        }
    }
}

/** Where the OAuth token (and which tracking endpoint works) is remembered between runs. */
interface TokenStore {
    fun load(): Pair<String, Long>?
    fun save(token: String, expiresAtMs: Long)
    fun clear()
    fun preferredEndpoint(): String? = null
    fun setPreferredEndpoint(name: String) {}

    class Memory : TokenStore {
        private var t: Pair<String, Long>? = null
        private var ep: String? = null
        override fun load() = t
        override fun save(token: String, expiresAtMs: Long) { t = token to expiresAtMs }
        override fun clear() { t = null }
        override fun preferredEndpoint() = ep
        override fun setPreferredEndpoint(name: String) { ep = name }
    }
}

/** One API call's result, before retries. */
sealed class ApiResult {
    data class Done(val outcome: LookupOutcome) : ApiResult()
    /** Rate limited, server error or network trouble: worth retrying later. */
    data class Retryable(val reason: String, val retryAfterMs: Long? = null) : ApiResult()
    /** No credentials, bad credentials, no Tracking access: retrying won't help. */
    data class Fatal(val reason: String) : ApiResult()
}

fun interface TrackingApi {
    fun lookup(trackingNumber: String): ApiResult
}

/**
 * USPS Tracking API client (developers.usps.com), OAuth 2.0 client credentials.
 *
 * - Token: POST /oauth2/v3/token with a JSON body {client_id, client_secret, grant_type}; cached
 *   (memory + [store]) until shortly before `expires_in`.
 * - Tracking: Tracking 3.2 ("v3r2") is the current API; Tracking 3.0 ("v3", legacy) is closed to
 *   new users and retires July 31, 2027. The published v3r2 example sends a JSON array of
 *   {trackingNumber} items, so that is tried first, then the single-number GET forms. The first
 *   endpoint that answers is remembered.
 * - Tracking errors are HTTP 400 with message codes 150001-150013 (USPS Error Inventory):
 *   150002 "may be incorrect" etc. mean not found; 150001/150003/150009 mean the label exists but
 *   has no status yet. 429 is TOO_MANY_REQUESTS.
 */
class UspsTrackingClient(
    private val credentials: () -> Pair<String, String>?,
    private val transport: HttpTransport = JavaHttpTransport(),
    private val store: TokenStore = TokenStore.Memory(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val base: String = PRODUCTION,
) : TrackingApi {

    enum class Endpoint { V3R2_POST, V3R2_GET, V3_GET }

    private var token: Pair<String, Long>? = null
    var tokenRequests = 0
        private set

    private fun cachedToken(): String? {
        val t = token ?: store.load()?.also { token = it }
        return t?.takeIf { clock() < it.second }?.first
    }

    private sealed class TokenResult {
        data class Ok(val token: String) : TokenResult()
        data class Err(val result: ApiResult) : TokenResult()
    }

    private fun fetchToken(clientId: String, secret: String): TokenResult {
        val body = Json.write(mapOf("client_id" to clientId, "client_secret" to secret, "grant_type" to "client_credentials"))
        tokenRequests++
        val r = try {
            transport.request("POST", "$base/oauth2/v3/token", mapOf("Content-Type" to "application/json", "Accept" to "application/json"), body)
        } catch (e: IOException) {
            return TokenResult.Err(ApiResult.Retryable("Network error: ${e.message}"))
        }
        if (r.code == 429 || r.code >= 500) return TokenResult.Err(ApiResult.Retryable("USPS sign-in busy (${r.code})", retryAfter(r)))
        if (r.code !in 200..299) return TokenResult.Err(ApiResult.Fatal("USPS sign-in failed (${r.code}). Check the Consumer Key and Secret in Settings."))
        val m = runCatching { Json.parse(r.body) as Map<*, *> }.getOrNull()
            ?: return TokenResult.Err(ApiResult.Retryable("Unreadable sign-in response"))
        val t = m["access_token"] as? String ?: return TokenResult.Err(ApiResult.Fatal("USPS sign-in returned no token"))
        val expiresIn = when (val e = m["expires_in"]) {
            is Number -> e.toLong()
            is String -> e.toLongOrNull()
            else -> null
        } ?: 3600L
        val expiresAt = clock() + (expiresIn - 60).coerceAtLeast(30) * 1000
        token = t to expiresAt
        store.save(t, expiresAt)
        return TokenResult.Ok(t)
    }

    override fun lookup(trackingNumber: String): ApiResult {
        val (id, secret) = credentials() ?: return ApiResult.Fatal("No USPS credentials. Add them in Settings.")
        var refreshedAfter401 = false
        val preferred = store.preferredEndpoint()?.let { p -> Endpoint.entries.firstOrNull { it.name == p } }
        val order = listOfNotNull(preferred) + Endpoint.entries.filter { it != preferred }
        var lastReason = "No tracking endpoint answered"
        var i = 0
        while (i < order.size) {
            val ep = order[i]
            val tok = cachedToken() ?: when (val tr = fetchToken(id, secret)) {
                is TokenResult.Ok -> tr.token
                is TokenResult.Err -> return tr.result
            }
            val r = try { call(ep, trackingNumber, tok) } catch (e: IOException) {
                return ApiResult.Retryable("Network error: ${e.message}")
            }
            val known = ep == preferred
            when {
                r.code == 401 && !refreshedAfter401 -> { token = null; store.clear(); refreshedAfter401 = true; continue }
                r.code == 401 -> return ApiResult.Fatal("USPS refused the token (401). Check the credentials.")
                r.code == 429 -> return ApiResult.Retryable("Rate limited by USPS (429)", retryAfter(r))
                r.code >= 500 && r.code != 501 -> return ApiResult.Retryable("USPS server error (${r.code})", retryAfter(r))
                r.code in 200..299 -> {
                    val outcome = parseSuccess(r.body, trackingNumber)
                    if (outcome != null) { store.setPreferredEndpoint(ep.name); return ApiResult.Done(outcome) }
                    lastReason = "Unexpected response from ${ep.name}"
                }
                else -> {
                    val err = trackingError(r.body)
                    if (err != null) { store.setPreferredEndpoint(ep.name); return ApiResult.Done(err) }
                    // No tracking error code: this endpoint form isn't available to this app.
                    if (known && r.code in setOf(400, 404)) return ApiResult.Done(LookupOutcome.NotFound(null, "HTTP ${r.code}"))
                    lastReason = when (r.code) {
                        403 -> "This USPS app has no access to the Tracking API (403). Add the Tracking product to your app on developers.usps.com."
                        else -> "Tracking endpoint ${ep.name} answered ${r.code}"
                    }
                }
            }
            i++
        }
        return ApiResult.Fatal(lastReason)
    }

    private fun call(ep: Endpoint, n: String, tok: String): HttpResponse {
        val h = mapOf("Authorization" to "Bearer $tok", "Accept" to "application/json")
        return when (ep) {
            Endpoint.V3R2_POST -> transport.request("POST", "$base/tracking/v3r2/tracking", h + ("Content-Type" to "application/json"), Json.write(listOf(mapOf("trackingNumber" to n))))
            Endpoint.V3R2_GET -> transport.request("GET", "$base/tracking/v3r2/tracking/$n?expand=DETAIL", h, null)
            Endpoint.V3_GET -> transport.request("GET", "$base/tracking/v3/tracking/$n?expand=DETAIL", h, null)
        }
    }

    private fun parseSuccess(body: String, n: String): LookupOutcome? {
        val root = runCatching { Json.parse(body) }.getOrNull() ?: return null
        val objs = ArrayList<Map<*, *>>()
        fun collect(x: Any?) {
            when (x) {
                is Map<*, *> -> { if (x.containsKey("trackingNumber")) objs += x else x.values.forEach(::collect) }
                is List<*> -> x.forEach(::collect)
            }
        }
        collect(root)
        val item = objs.firstOrNull { (it["trackingNumber"] as? String)?.equals(n, ignoreCase = true) == true } ?: objs.firstOrNull()
            ?: return trackingError(body)
        // A per-item error inside a batch response.
        if (item.containsKey("error") || item.containsKey("errors")) trackingError(Json.write(item))?.let { return it }
        return LookupOutcome.Found(TrackingFacts.fromMap(item))
    }

    companion object {
        const val PRODUCTION = "https://apis.usps.com"
        const val TEST = "https://apis-tem.usps.com"

        private val NOT_FOUND_CODES = setOf("150002", "150004", "150008", "150010", "150011", "150012", "150006", "150007")
        private val NO_STATUS_CODES = setOf("150001", "150003", "150005", "150009", "150013")

        /** Maps a USPS tracking error body to an outcome, or null if it carries no tracking error. */
        fun trackingError(body: String): LookupOutcome? {
            val code = Regex("\\b(1500(0[1-9]|1[0-9]))\\b").find(body)?.value
            val message = runCatching { findMessage(Json.parse(body)) }.getOrNull()
            return when {
                code in NO_STATUS_CODES -> LookupOutcome.NoStatusYet(message)
                code in NOT_FOUND_CODES -> LookupOutcome.NotFound(code, message)
                code == null && message != null && Regex("(?i)tracking number may be incorrect|not (yet )?available|could not be found|not found").containsMatchIn(message) ->
                    if (message.contains("not yet available", ignoreCase = true) && !message.contains("incorrect", ignoreCase = true)) LookupOutcome.NoStatusYet(message)
                    else LookupOutcome.NotFound(null, message)
                else -> null
            }
        }

        private fun findMessage(x: Any?): String? = when (x) {
            is Map<*, *> -> (x["detail"] as? String) ?: (x["message"] as? String) ?: x.values.firstNotNullOfOrNull { findMessage(it) }
            is List<*> -> x.firstNotNullOfOrNull { findMessage(it) }
            else -> null
        }

        fun retryAfter(r: HttpResponse): Long? = r.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
    }
}
