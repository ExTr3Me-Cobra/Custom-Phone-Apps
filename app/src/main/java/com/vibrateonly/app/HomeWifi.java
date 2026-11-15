package com.vibrateonly.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;

/**
 * Home by Wi-Fi: connecting to your home Wi-Fi means you're home (Vibrate Only off straight away). Dropping it
 * starts a double check: 2 minutes later one location check decides. Away: Vibrate Only on, and nothing more runs
 * until your Wi-Fi (or Android's home area) says you're back. Still home (power cut, router down): it stays off and
 * keeps a light location check going, so leaving is still noticed within about 2 minutes, until the Wi-Fi is back.
 *
 * Android announces Wi-Fi changes, so this costs next to no battery. With Wi-Fi switched off it can't tell, and the
 * paced location checks take over (see Home.startBackup).
 */
final class HomeWifi {
    private static final String KEY = "home_wifi";
    private static final long DOUBLE_CHECK_MS = 2 * 60_000L;

    private HomeWifi() {}

    /** Your home Wi-Fi names. */
    static Set<String> networks(Context c) {
        return new HashSet<>(Prefs.get(c).getStringSet(KEY, new HashSet<>()));
    }

    static void add(Context c, String ssid) {
        Set<String> s = networks(c);
        s.add(ssid);
        Prefs.get(c).edit().putStringSet(KEY, s).apply();
    }

    static void clear(Context c) {
        Prefs.get(c).edit().remove(KEY).apply();
    }

    /** Wi-Fi tells home apart: home Wi-Fi is set and Wi-Fi is switched on. */
    static boolean inCharge(Context c) {
        if (networks(c).isEmpty()) return false;
        WifiManager wm = c.getApplicationContext().getSystemService(WifiManager.class);
        return wm != null && wm.isWifiEnabled();
    }

    /** The Wi-Fi you're connected to now (null if none, or if Android won't say). */
    @SuppressWarnings("deprecation")
    static String currentSsid(Context c) {
        WifiManager wm = c.getApplicationContext().getSystemService(WifiManager.class);
        if (wm == null) return null;
        WifiInfo info = wm.getConnectionInfo();
        return clean(info != null ? info.getSSID() : null);
    }

    private static String clean(String ssid) {
        if (ssid == null) return null;
        String s = ssid.replace("\"", "").trim();
        return s.isEmpty() || s.equals("<unknown ssid>") ? null : s;
    }

    // ---- watching ----

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static ConnectivityManager.NetworkCallback callback;
    private static Network homeNetwork;
    /** Between dropping home Wi-Fi and the location check settling it (or the outage check after). */
    private static volatile boolean checking;
    private static Context app;

    static boolean onHomeWifi() { return homeNetwork != null; }
    static boolean checking() { return checking; }

    private static final Runnable doubleCheck = () -> {
        if (app == null || homeNetwork != null) return;
        checking = true;
        Home.noteEvent(app, "Home Wi-Fi gone for 2 minutes: checking location");
        Home.startBackup(app);
        Home.checkNow(app);
    };

    /** Starts watching Wi-Fi (once; safe to call again). */
    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (callback != null) return;
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        callback = new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (!(caps.getTransportInfo() instanceof WifiInfo)) return;
                String ssid = clean(((WifiInfo) caps.getTransportInfo()).getSSID());
                if (ssid == null || !networks(app).contains(ssid)) return;
                handler.post(() -> {
                    boolean was = homeNetwork != null;
                    homeNetwork = network;
                    handler.removeCallbacks(doubleCheck);
                    checking = false;
                    Home.stopBackup(app);
                    if (!was) Home.arrived(app, true, "home Wi-Fi");
                });
            }

            @Override
            public void onLost(Network network) {
                handler.post(() -> {
                    if (!network.equals(homeNetwork)) return;
                    homeNetwork = null;
                    handler.removeCallbacks(doubleCheck);
                    handler.postDelayed(doubleCheck, DOUBLE_CHECK_MS);
                });
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
        } catch (RuntimeException e) {
            callback = null;
        }
    }

    /** Away confirmed: the double check is done; Wi-Fi (or Android's home area) takes it from here. */
    static void settledAway() {
        checking = false;
    }
}
