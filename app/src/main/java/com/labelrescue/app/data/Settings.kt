package com.labelrescue.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
        set(v) = prefs.edit().putString("usps_client_id", v.trim()).apply()

    var clientSecret: String
        get() = prefs.getString("usps_client_secret", "") ?: ""
        set(v) = prefs.edit().putString("usps_client_secret", v.trim()).apply()

    val canVerify: Boolean get() = verifyEnabled && clientId.isNotBlank() && clientSecret.isNotBlank()

    private companion object {
        const val FILE = "secure_settings"
    }
}
