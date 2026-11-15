package com.vibrateonly.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.text.DateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * Turns Vibrate Only Mode on when you leave home and off when you get back, using Android's
 * geofencing (the battery-friendly way location reminders work).
 */
final class Home {
    private static final String TAG = "VibrateOnly";
    private static final String KEY_LAST_EVENT = "home_last_event";
    /** Last known: at home (true) or away (false). Only a change switches the mode. */
    private static final String KEY_AT_HOME = "home_inside";

    private Home() {}

    static boolean hasLocationPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** "Allow all the time": needed for it to work while the app isn't open. */
    static boolean hasBackgroundPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent pendingIntent(Context c) {
        // Mutable: the system adds the arrive / leave details to it.
        return PendingIntent.getBroadcast(c, 7,
                new Intent(c, HomeReceiver.class).setAction(HomeReceiver.ACTION_GEOFENCE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /** Sets up (or removes) the home watch to match the settings. Safe to call any time. */
    @SuppressLint("MissingPermission")
    static void register(Context c) {
        Context app = c.getApplicationContext();
        GeofencingClient client = LocationServices.getGeofencingClient(app);
        PendingIntent pi = pendingIntent(app);
        client.removeGeofences(pi);
        if (!Prefs.homeOn(app) || !Prefs.hasHome(app)
                || !hasLocationPermission(app) || !hasBackgroundPermission(app)) {
            stopBackup(app);
            return;
        }
        HomeWifi.start(app);
        // Restart the backup check so a change in settings takes effect.
        stopBackup(app);
        startBackup(app);
        Geofence fence = new Geofence.Builder()
                .setRequestId("home")
                .setCircularRegion(Prefs.homeLat(app), Prefs.homeLng(app), Prefs.homeRadius(app))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER
                        | Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();
        GeofencingRequest request = new GeofencingRequest.Builder()
                // Report where you are right away too (only a change from the last known
                // state switches the mode, see atHomeChanged).
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER
                        | GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofence(fence)
                .build();
        try {
            client.addGeofences(request, pi)
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Home watch failed", e);
                        int code = e instanceof ApiException ? ((ApiException) e).getStatusCode() : -1;
                        noteEvent(app, code == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
                                ? "Android's area watching is unavailable: turn on Google Location "
                                        + "Accuracy (Settings → Location → Location services). The "
                                        + "backup check still runs"
                                : "Couldn't set up Android's area watching (code " + code
                                        + "). The backup check still runs");
                    });
        } catch (SecurityException e) {
            Log.w(TAG, "No location permission", e);
        }
    }

    /** Finds where the phone is right now (for "Use where I am now"). */
    @SuppressLint("MissingPermission")
    static void currentLocation(Context c, Consumer<Location> done) {
        if (!hasLocationPermission(c)) {
            done.accept(null);
            return;
        }
        LocationServices.getFusedLocationProviderClient(c)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(done::accept)
                .addOnFailureListener(e -> done.accept(null));
    }

    /** Remembers what happened last, for the settings screen. */
    static void noteEvent(Context c, String what) {
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date());
        Prefs.get(c).edit().putString(KEY_LAST_EVENT, what + " (" + when + ")").apply();
    }

    /** Records being at home or away; true when that's a change (so the mode should switch). */
    static boolean atHomeChanged(Context c, boolean atHome) {
        String prev = Prefs.get(c).getString(KEY_AT_HOME, null);
        String now = atHome ? "home" : "away";
        Prefs.get(c).edit().putString(KEY_AT_HOME, now).apply();
        return !now.equals(prev);
    }

    /** Forget where you were (new home spot), so the next report counts as a change. */
    static void forgetState(Context c) {
        Prefs.get(c).edit().remove(KEY_AT_HOME).apply();
    }

    // ------------------------------------------------------------------ backup location check

    private static final String KEY_LAST_DISTANCE = "home_last_distance";
    private static final String KEY_LAST_CHECK = "home_last_check";
    private static LocationCallback backup;

    /** Within this far of home's edge (about a mile), checks are frequent; further away they space out. */
    private static final float NEAR_M = 1609f;
    /** Fast enough to stay ahead of highway speed (about 70 mph) while far away. */
    private static final float FAST_MPS = 31f;
    private static long currentInterval;

    /**
     * How often to check, from how far you were from home at the last reading: about every minute within a mile of
     * home (so leaving or getting back is noticed within about 2 minutes), and further out only as often as needed
     * to catch you before you can reach that mile at highway speed (at most every 15 minutes).
     */
    private static long intervalFor(Context c) {
        if (HomeWifi.checking()) return 60_000L; // home Wi-Fi just dropped: decide quickly
        SharedPreferences p = Prefs.get(c);
        if (!p.contains(KEY_LAST_DISTANCE)) return 60_000L;
        float beyond = p.getFloat(KEY_LAST_DISTANCE, 0f) - Prefs.homeRadius(c) - NEAR_M;
        if (beyond <= 0) return 60_000L;
        long ms = (long) (beyond / FAST_MPS * 1000);
        return Math.max(120_000L, Math.min(900_000L, ms));
    }

    /**
     * A second, independent way to notice leaving and getting home: location readings compared with the home spot.
     * Android's area watching can be slow or unavailable on some phones; this catches what it misses. It uses
     * Wi-Fi and cell towers rather than GPS (much less battery, and still accurate enough near home), and how
     * often it checks depends on how far away you are (see [intervalFor]).
     */
    @SuppressLint("MissingPermission")
    static synchronized void startBackup(Context c) {
        Context app = c.getApplicationContext();
        // Home Wi-Fi decides while Wi-Fi is on: location only runs for the double check after it drops.
        if (HomeWifi.inCharge(app) && !HomeWifi.checking()) { stopBackup(app); return; }
        long interval = intervalFor(app);
        if (backup != null && interval == currentInterval) return;
        stopBackup(app);
        currentInterval = interval;
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
                .setMinUpdateIntervalMillis(Math.min(interval, 30_000L))
                // Readings are batched up to the interval, so the phone can stay asleep in between.
                .setMaxUpdateDelayMillis(interval)
                .build();
        backup = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                // Near home but the reading is too rough to judge (no Wi-Fi around): one quick GPS fix instead.
                if (loc.hasAccuracy() && loc.getAccuracy() > 400f && currentInterval <= 60_000L) {
                    try {
                        LocationServices.getFusedLocationProviderClient(app)
                                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener(fix -> { if (fix != null) checkLocation(app, fix, "GPS check"); });
                    } catch (SecurityException ignored) { }
                    return;
                }
                checkLocation(app, loc, "backup check");
                // Closer or further away now: check more or less often.
                if (intervalFor(app) != currentInterval) startBackup(app);
            }
        };
        try {
            LocationServices.getFusedLocationProviderClient(app)
                    .requestLocationUpdates(request, backup, Looper.getMainLooper());
        } catch (SecurityException e) {
            backup = null;
        }
    }

    /** One location check right now (the Wi-Fi double check): Wi-Fi/cell first, GPS if that's too rough. */
    @SuppressLint("MissingPermission")
    static void checkNow(Context c) {
        Context app = c.getApplicationContext();
        if (!hasLocationPermission(app)) return;
        try {
            LocationServices.getFusedLocationProviderClient(app)
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc != null && (!loc.hasAccuracy() || loc.getAccuracy() <= 400f)) {
                            checkLocation(app, loc, "location double check");
                        } else {
                            LocationServices.getFusedLocationProviderClient(app)
                                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                    .addOnSuccessListener(fix -> { if (fix != null) checkLocation(app, fix, "GPS double check"); });
                        }
                    });
        } catch (SecurityException ignored) { }
    }

    static synchronized void stopBackup(Context c) {
        if (backup == null) return;
        LocationServices.getFusedLocationProviderClient(c.getApplicationContext()).removeLocationUpdates(backup);
        backup = null;
        currentInterval = 0;
    }

    /** Compares a location with home and switches the mode if you've clearly left or come back. */
    static void checkLocation(Context c, Location loc, String how) {
        if (!Prefs.homeOn(c) || !Prefs.hasHome(c)) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 400f) return; // too vague to judge
        float[] out = new float[1];
        Location.distanceBetween(Prefs.homeLat(c), Prefs.homeLng(c), loc.getLatitude(), loc.getLongitude(), out);
        float distance = out[0];
        Prefs.get(c).edit()
                .putFloat(KEY_LAST_DISTANCE, distance)
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply();
        float radius = Prefs.homeRadius(c);
        float margin = Math.max(50f, loc.hasAccuracy() ? loc.getAccuracy() : 50f);
        if (distance <= radius) {
            arrived(c, true, how);
        } else if (distance > radius + margin) {
            arrived(c, false, how);
            // Away is settled: from here, home Wi-Fi (or Android's home area) notices you getting back.
            if (HomeWifi.checking()) { HomeWifi.settledAway(); stopBackup(c); }
        }
        // In between: not sure yet, leave it as it is.
    }

    /** Leaving (false) or getting home (true), from either way of watching. */
    static void arrived(Context c, boolean atHome, String how) {
        if (!atHomeChanged(c, atHome)) return;
        String waiting = !atHome && ModeController.deviceConnected(c)
                ? " (waiting until headphones / speaker disconnect)" : "";
        noteEvent(c, (atHome ? "Got home: Vibrate Only off" : "Left home: Vibrate Only on" + waiting)
                + " – " + how);
        ModeController.setByLocation(c, !atHome);
    }

    /** "About 1.2 km from home (checked 14:05)", or null before the first reading. */
    static String lastReading(Context c) {
        SharedPreferences p = Prefs.get(c);
        if (!p.contains(KEY_LAST_CHECK)) return null;
        float d = p.getFloat(KEY_LAST_DISTANCE, 0f);
        String dist = d < 1000 ? Math.round(d) + " m" : String.format(java.util.Locale.US, "%.1f km", d / 1000f);
        String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(p.getLong(KEY_LAST_CHECK, 0)));
        return "About " + dist + " from home (checked " + when + ")";
    }

    static String lastEvent(Context c) {
        return Prefs.get(c).getString(KEY_LAST_EVENT, null);
    }
}
