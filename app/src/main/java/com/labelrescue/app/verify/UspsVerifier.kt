package com.labelrescue.app.verify

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional online check against the USPS Tracking API (apis.usps.com) using the user's own free
 * developer credentials. Runs only when the user turns it on in Settings and taps verify; nothing
 * else in the app uses the network.
 */
class UspsVerifier(private val clientId: String, private val clientSecret: String) {

    sealed class Result {
        /** USPS knows this number. */
        data class Found(val summary: String) : Result()
        data object NotFound : Result()
        data class Error(val message: String) : Result()
    }

    private var token: String? = null
    private var tokenExpires = 0L

    private fun token(): String {
        token?.let { if (System.currentTimeMillis() < tokenExpires) return it }
        val body = JSONObject().put("client_id", clientId).put("client_secret", clientSecret)
            .put("grant_type", "client_credentials").toString()
        val (code, text) = http("POST", "$BASE/oauth2/v3/token", body, null)
        if (code !in 200..299) throw IOException("USPS sign-in failed ($code). Check the credentials in Settings.")
        val o = JSONObject(text)
        val t = o.getString("access_token")
        token = t
        tokenExpires = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000
        return t
    }

    /** Blocking; call off the main thread. */
    fun check(tracking: String): Result = try {
        val (code, text) = http("GET", "$BASE/tracking/v3/tracking/$tracking?expand=SUMMARY", null, token())
        when {
            code in 200..299 -> {
                val o = runCatching { JSONObject(text) }.getOrNull()
                if (o?.has("error") == true) Result.NotFound
                else Result.Found(
                    o?.optString("statusSummary")?.takeIf { it.isNotBlank() }
                        ?: o?.optString("status")?.takeIf { it.isNotBlank() }
                        ?: "Known to USPS",
                )
            }
            code == 404 || code == 400 -> Result.NotFound
            code == 401 || code == 403 -> { token = null; Result.Error("USPS refused the request ($code). Check the credentials.") }
            code == 429 -> Result.Error("USPS rate limit reached; try again later.")
            else -> Result.Error("USPS returned $code")
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "Network error")
    }

    private fun http(method: String, url: String, json: String?, bearer: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/json")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val BASE = "https://apis.usps.com"
    }
}
