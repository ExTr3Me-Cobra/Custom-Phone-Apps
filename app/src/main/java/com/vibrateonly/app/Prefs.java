package com.vibrateonly.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

/** User settings, all stored in one SharedPreferences file. */
final class Prefs {
    static final String FILE = "vibrate_only";

    static final String COMBO_MS = "combo_ms";
    static final String MUTE_MEDIA = "mute_media";
    static final String VIB_STRENGTH = "vib_strength";
    static final String VIB_LENGTH = "vib_length";
    static final String NOTIFICATION = "notification";
    static final String AUTO_OFF_MIN = "auto_off_min";
    static final String KEEP_VIBRATE = "keep_vibrate";
    static final String ALWAYS_READY = "always_ready";
    static final String FAST_CHECK = "fast_check";
    static final String KEEP_AWAKE = "keep_awake";
    static final String READY_NOTIFICATION = "ready_notification";
    static final String HOME_ON = "home_on";
    static final String HOME_LAT = "home_lat";
    static final String HOME_LNG = "home_lng";
    static final String HOME_RADIUS = "home_radius";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int comboWindowMs(Context c) {
        return get(c).getInt(COMBO_MS, 150);
    }

    static boolean muteMedia(Context c) {
        return get(c).getBoolean(MUTE_MEDIA, true);
    }

    static int vibrationStrength(Context c) {
        return get(c).getInt(VIB_STRENGTH, 255);
    }

    static int vibrationLengthMs(Context c) {
        return get(c).getInt(VIB_LENGTH, 900);
    }

    static boolean showNotification(Context c) {
        return get(c).getBoolean(NOTIFICATION, true);
    }

    static int autoOffMinutes(Context c) {
        return get(c).getInt(AUTO_OFF_MIN, 0);
    }

    /** If the sound mode is changed some other way, put it back to vibrate (true) or end the mode. */
    /** Run in the foreground so Android never puts the app to sleep. */
    static boolean alwaysReady(Context c) {
        return get(c).getBoolean(ALWAYS_READY, true);
    }

    /** Check location every ~20 s with GPS instead of every ~2 min. */
    static boolean fastCheck(Context c) {
        return get(c).getBoolean(FAST_CHECK, true);
    }

    /** Never let the processor fully sleep (fastest reactions, more battery). */
    static boolean keepAwake(Context c) {
        return false; // Removed: it kept the phone from ever sleeping. Checks now space themselves out instead.
    }

    static boolean readyNotification(Context c) {
        return get(c).getBoolean(READY_NOTIFICATION, true);
    }

    static boolean keepVibrate(Context c) {
        return get(c).getBoolean(KEEP_VIBRATE, true);
    }

    /** Turn the mode on when leaving home and off when getting back. */
    static boolean homeOn(Context c) {
        return get(c).getBoolean(HOME_ON, false);
    }

    static boolean hasHome(Context c) {
        return get(c).contains(HOME_LAT) && get(c).contains(HOME_LNG);
    }

    static double homeLat(Context c) {
        return Double.longBitsToDouble(get(c).getLong(HOME_LAT, 0));
    }

    static double homeLng(Context c) {
        return Double.longBitsToDouble(get(c).getLong(HOME_LNG, 0));
    }

    static void setHome(Context c, double lat, double lng) {
        get(c).edit()
                .putLong(HOME_LAT, Double.doubleToRawLongBits(lat))
                .putLong(HOME_LNG, Double.doubleToRawLongBits(lng))
                .apply();
    }

    /** Metres around home that still count as being home. */
    static int homeRadius(Context c) {
        return get(c).getInt(HOME_RADIUS, 150);
    }
}
