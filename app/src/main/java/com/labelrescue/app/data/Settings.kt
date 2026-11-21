package com.labelrescue.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** App settings, in EncryptedSharedPreferences (key held in the Android Keystore). */
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

    init {
        // The app no longer has online lookups: wipe any USPS or UPS keys and tokens an earlier
        // version saved, so none linger on the phone.
        val old = listOf(
            "verify_enabled", "usps_client_id", "usps_client_secret", "auto_verify", "requests_per_hour",
            "oauth_token", "oauth_expires", "tracking_endpoint",
            "ups_enabled", "ups_client_id", "ups_client_secret", "ups_oauth_token", "ups_oauth_expires",
        )
        if (old.any { prefs.contains(it) }) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    /** Save digit crops of confirmed photo labels for retraining (local only, off by default). */
    var saveTrainingCrops: Boolean
        get() = prefs.getBoolean("save_training_crops", false)
        set(v) = prefs.edit().putBoolean("save_training_crops", v).apply()

    /** Learn from intact labels: on a clean barcode read, save the printed digits as trusted crops. */
    var learnFromIntact: Boolean
        get() = prefs.getBoolean("learn_intact", false)
        set(v) = prefs.edit().putBoolean("learn_intact", v).apply()

    /** Quick scan: stop reading burst frames once the answer is settled (off = always use every frame). */
    var quickScan: Boolean
        get() = prefs.getBoolean("quick_scan", true)
        set(v) = prefs.edit().putBoolean("quick_scan", v).apply()

    private companion object {
        const val FILE = "secure_settings"
    }
}
