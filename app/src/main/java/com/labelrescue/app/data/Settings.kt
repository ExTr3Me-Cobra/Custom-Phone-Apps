package com.labelrescue.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.labelrescue.core.verify.TokenStore

/**
 * Optional USPS developer API credentials, stored with EncryptedSharedPreferences (key held in
 * the Android Keystore). The app works fully without them.
 */
@Suppress("DEPRECATION")
class Settings(ctx: Context) {
    private val prefs: SharedPreferences = try {
        open(ctx)
    } catch (e: Exception) {
        // Keystore trouble (rare, e.g. after a device restore): start fresh rather than crash.
        ctx.deleteSharedPreferences(FILE)
        open(ctx)
    }

    private fun open(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var verifyEnabled: Boolean
        get() = prefs.getBoolean("verify_enabled", false)
        set(v) = prefs.edit().putBoolean("verify_enabled", v).apply()

    var clientId: String
        get() = prefs.getString("usps_client_id", "") ?: ""
        set(v) { if (v.trim() != clientId) tokenStore.clear(); prefs.edit().putString("usps_client_id", v.trim()).apply() }

    var clientSecret: String
        get() = prefs.getString("usps_client_secret", "") ?: ""
        set(v) { tokenStore.clear(); prefs.edit().putString("usps_client_secret", v.trim()).apply() }

    val hasCredentials: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val canVerify: Boolean get() = verifyEnabled && hasCredentials

    /** Verify automatically after ranking (otherwise only on tap). */
    var autoVerify: Boolean
        get() = prefs.getBoolean("auto_verify", true)
        set(v) = prefs.edit().putBoolean("auto_verify", v).apply()

    /**
     * Requests per hour. USPS doesn't publish the default quota (quota increases are requested
     * through USPS support), so the default is conservative; 429 responses also trigger backoff.
     */
    var requestsPerHour: Int
        get() = prefs.getInt("requests_per_hour", 60)
        set(v) = prefs.edit().putInt("requests_per_hour", v.coerceIn(1, 10000)).apply()

    /** Save digit crops of confirmed photo labels for retraining (local only, off by default). */
    var saveTrainingCrops: Boolean
        get() = prefs.getBoolean("save_training_crops", false)
        set(v) = prefs.edit().putBoolean("save_training_crops", v).apply()

    /** Quick scan: stop reading burst frames once the answer is settled (off = always use every frame). */
    var quickScan: Boolean
        get() = prefs.getBoolean("quick_scan", true)
        set(v) = prefs.edit().putBoolean("quick_scan", v).apply()

    /** The OAuth token is cached (encrypted) until it expires, plus which tracking endpoint works. */
    val tokenStore: TokenStore = object : TokenStore {
        override fun load(): Pair<String, Long>? {
            val t = prefs.getString("oauth_token", null) ?: return null
            return t to prefs.getLong("oauth_expires", 0)
        }
        override fun save(token: String, expiresAtMs: Long) { prefs.edit().putString("oauth_token", token).putLong("oauth_expires", expiresAtMs).apply() }
        override fun clear() { prefs.edit().remove("oauth_token").remove("oauth_expires").apply() }
        override fun preferredEndpoint(): String? = prefs.getString("tracking_endpoint", null)
        override fun setPreferredEndpoint(name: String) { prefs.edit().putString("tracking_endpoint", name).apply() }
    }

    fun credentials(): Pair<String, String>? = if (hasCredentials) clientId to clientSecret else null

    // ---- UPS → USPS lookup (optional; the user's own developer.ups.com keys) ----
    var upsEnabled: Boolean
        get() = prefs.getBoolean("ups_enabled", false)
        set(v) = prefs.edit().putBoolean("ups_enabled", v).apply()

    var upsClientId: String
        get() = prefs.getString("ups_client_id", "") ?: ""
        set(v) { if (v.trim() != upsClientId) upsTokenStore.clear(); prefs.edit().putString("ups_client_id", v.trim()).apply() }

    var upsClientSecret: String
        get() = prefs.getString("ups_client_secret", "") ?: ""
        set(v) { upsTokenStore.clear(); prefs.edit().putString("ups_client_secret", v.trim()).apply() }

    val hasUpsCredentials: Boolean get() = upsClientId.isNotBlank() && upsClientSecret.isNotBlank()

    fun upsCredentials(): Pair<String, String>? = if (hasUpsCredentials) upsClientId to upsClientSecret else null

    val upsTokenStore: TokenStore = object : TokenStore {
        override fun load(): Pair<String, Long>? {
            val t = prefs.getString("ups_oauth_token", null) ?: return null
            return t to prefs.getLong("ups_oauth_expires", 0)
        }
        override fun save(token: String, expiresAtMs: Long) { prefs.edit().putString("ups_oauth_token", token).putLong("ups_oauth_expires", expiresAtMs).apply() }
        override fun clear() { prefs.edit().remove("ups_oauth_token").remove("ups_oauth_expires").apply() }
    }

    private companion object {
        const val FILE = "secure_settings"
    }
}
