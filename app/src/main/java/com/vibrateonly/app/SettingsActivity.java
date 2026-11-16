package com.vibrateonly.app;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** All adjustable options. Every change takes effect immediately. */
public class SettingsActivity extends Activity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Settings");
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
    }

    private void render() {
        content.removeAllViews();
        TextView title = text("Settings", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        heading("Button shortcut");
        choice("Press timing",
                "How close together the two buttons must be pressed. Longer is easier to hit, "
                        + "but single volume presses respond a little slower.",
                Prefs.COMBO_MS, 150,
                new String[] {"Quick (0.1 s)", "Normal (0.15 s)", "Relaxed (0.25 s)",
                        "Very relaxed (0.4 s)"},
                new int[] {100, 150, 250, 400});

        heading("Media");
        toggle("Mute media",
                "While the mode is on, music and videos are muted. (Connecting headphones or a "
                        + "speaker switches the mode off anyway until they disconnect.)",
                Prefs.MUTE_MEDIA, true);

        home();

        heading("On / off buzz");
        choice("Buzz strength", null, Prefs.VIB_STRENGTH, 255,
                new String[] {"Gentle", "Medium", "Strong"}, new int[] {110, 180, 255});
        choice("Buzz length", null, Prefs.VIB_LENGTH, 900,
                new String[] {"Short (0.5 s)", "Normal (0.9 s)", "Long (1.5 s)"},
                new int[] {500, 900, 1500});
        TextView test = link("Test buzz");
        test.setOnClickListener(v -> ModeController.vibrate(this, true));
        content.addView(test);

        heading("Reliability");
        toggle("Always ready",
                "Keeps Vibrate Only running in the foreground (with a small silent notification) so "
                        + "Android never puts it to sleep, and restarts it after a reboot.",
                Prefs.ALWAYS_READY, true);
        if (Prefs.alwaysReady(this)) {
            toggle("Show the \"running\" notification",
                    "Off: no notification at all. Vibrate Only still stays running through its "
                            + "button listener (which Android keeps on), with the same keep-awake and checks.",
                    Prefs.READY_NOTIFICATION, true);
        }
        content.addView(text("Home checks pace themselves: about every minute within a mile of home (so leaving "
                + "or getting back is noticed within about 2 minutes), less often the further away you are. They use "
                + "Wi-Fi and cell towers rather than GPS to save battery.", 14));
        content.addView(text("Tip: also set Settings → Battery → Background usage limits so Vibrate "
                + "Only is under \"Never auto sleeping apps\".", 14));

        heading("Behaviour");
        toggle("Show a notification while on",
                "A silent notification with a \"Turn off\" button, so you can see the mode is on.",
                Prefs.NOTIFICATION, true);
        choice("Turn off automatically after", null, Prefs.AUTO_OFF_MIN, 0,
                new String[] {"Never", "30 minutes", "1 hour", "2 hours", "4 hours", "8 hours",
                        "12 hours"},
                new int[] {0, 30, 60, 120, 240, 480, 720});
        toggle("Keep vibrate if sound mode is changed elsewhere",
                "On: if the sound mode is switched in quick settings (or by another app) while the "
                        + "mode is on, it goes straight back to vibrate, so only the buttons turn it "
                        + "off. Off: changing the sound mode elsewhere ends Vibrate Only Mode.",
                Prefs.KEEP_VIBRATE, true);

        heading("Tip");
        content.addView(text("You can also add a \"Vibrate Only\" tile to quick settings: swipe down "
                + "twice from the top, tap the pencil (edit) button, and drag the tile in.", 14));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Home.register(this);
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        Home.register(this);
        render();
    }

    private void changed() {
        Home.register(this);
        KeepAlive.start(this);
        ModeController.refresh(this);
        VibrateOnlyService service = VibrateOnlyService.instance;
        if (service != null) service.refresh();
    }

    private void toggle(String name, String summary, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(bold(name));
        if (summary != null) texts.addView(text(summary, 14));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(Prefs.get(this).getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> {
            Prefs.get(this).edit().putBoolean(key, on).apply();
            changed();
            if (Prefs.HOME_ON.equals(key) || Prefs.ALWAYS_READY.equals(key)) content.post(this::render);
        });
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        content.addView(row);
    }

    private void choice(String name, String summary, String key, int def, String[] labels,
            int[] values) {
        int current = Prefs.get(this).getInt(key, def);
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) selected = i;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(bold(name));
        if (summary != null) row.addView(text(summary, 14));
        TextView value = link(labels[selected]);
        row.addView(value);
        int initial = selected;
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(name)
                .setSingleChoiceItems(labels, initial, (dialog, which) -> {
                    Prefs.get(this).edit().putInt(key, values[which]).apply();
                    dialog.dismiss();
                    changed();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show());
        content.addView(row);
    }

    /** Turn on when leaving home, off when getting back. */
    private void home() {
        heading("Home");
        toggle("Turn on when I leave home",
                "Vibrate Only switches on when you leave home and off when you get back. You can "
                        + "still use the buttons in between. Leaving or arriving can take a few "
                        + "minutes to be noticed.",
                Prefs.HOME_ON, false);
        if (!Prefs.homeOn(this)) return;

        boolean fine = Home.hasLocationPermission(this);
        boolean always = Home.hasBackgroundPermission(this);
        if (!fine || !always) {
            content.addView(bold(fine ? "⬜ Location: choose \"Allow all the time\""
                    : "⬜ Allow location"));
            content.addView(text(fine
                    ? "Needed so it works while the app is closed. On the next screen pick "
                            + "\"Allow all the time\" (and keep \"Use precise location\" on)."
                    : "Choose \"While using the app\" with precise location; the next step "
                            + "asks for \"all the time\".", 14));
            TextView grant = link("Allow");
            grant.setOnClickListener(v -> requestPermissions(fine
                    ? new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}
                    : new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 10));
            content.addView(grant);
        } else {
            content.addView(text("✅ Location allowed all the time", 14));
        }

        content.addView(bold(Prefs.hasHome(this)
                ? String.format(java.util.Locale.US, "Home: %.5f, %.5f",
                        Prefs.homeLat(this), Prefs.homeLng(this))
                : "Home: not set yet"));
        TextView here = link("Use where I am now (do this at home)");
        here.setOnClickListener(v -> {
            if (!fine) {
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 10);
                return;
            }
            here.setText("Finding your location…");
            Home.currentLocation(this, loc -> runOnUiThread(() -> {
                if (loc == null) {
                    Toast.makeText(this, "Couldn't get your location. Is Location on?",
                            Toast.LENGTH_LONG).show();
                } else {
                    Prefs.setHome(this, loc.getLatitude(), loc.getLongitude());
                    Home.forgetState(this);
                    Toast.makeText(this, "Home saved", Toast.LENGTH_SHORT).show();
                    changed();
                }
                render();
            }));
        });
        content.addView(here);
        TextView typed = link("Enter coordinates (e.g. copied from Google Maps)");
        typed.setOnClickListener(v -> enterCoordinates());
        content.addView(typed);
        if (Prefs.hasHome(this)) {
            TextView map = link("Check it on the map");
            map.setOnClickListener(v -> {
                String pos = Prefs.homeLat(this) + "," + Prefs.homeLng(this);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("geo:" + pos + "?q=" + pos + "(Home)")));
                } catch (Exception e) {
                    Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show();
                }
            });
            content.addView(map);
        }
        // Home Wi-Fi: the main way to know you're home; location is the double check.
        java.util.Set<String> wifi = HomeWifi.networks(this);
        content.addView(bold(wifi.isEmpty() ? "Home Wi-Fi: not set" : "Home Wi-Fi: " + String.join(", ", wifi)));
        content.addView(text("Connecting to it means you're home. When it drops, Vibrate Only waits 2 minutes, "
                + "then checks your location: away turns it on; still home (power or internet out) keeps it off and "
                + "keeps watching until the Wi-Fi is back. Uses almost no battery.", 14));
        TextView addWifi = link("Use the Wi-Fi I'm on now as home");
        addWifi.setOnClickListener(v -> {
            String ssid = HomeWifi.currentSsid(this);
            if (ssid == null) {
                Toast.makeText(this, "Connect to your home Wi-Fi first (and allow location).", Toast.LENGTH_LONG).show();
                return;
            }
            HomeWifi.add(this, ssid);
            Toast.makeText(this, "Home Wi-Fi: " + ssid, Toast.LENGTH_SHORT).show();
            changed();
            render();
        });
        content.addView(addWifi);
        if (!wifi.isEmpty()) {
            TextView clearWifi = link("Forget home Wi-Fi");
            clearWifi.setOnClickListener(v -> { HomeWifi.clear(this); changed(); render(); });
            content.addView(clearWifi);
        }
        choice("Home size",
                "How far from that spot still counts as being home. Bigger is more reliable "
                        + "(no switching on while you're in the garden); smaller is more exact.",
                Prefs.HOME_RADIUS, 150,
                new String[] {"100 m", "150 m", "250 m", "400 m", "800 m"},
                new int[] {100, 150, 250, 400, 800});
        String reading = Home.lastReading(this);
        content.addView(text(reading != null ? "Right now: " + reading
                : "Right now: no location reading yet (it checks every couple of minutes)", 14));
        String last = Home.lastEvent(this);
        if (last != null) content.addView(text("Last: " + last, 14));
        content.addView(text("If it doesn't switch when you leave: make sure Location is on, "
                + "\"Google Location Accuracy\" is on (Settings → Location → Location services), "
                + "and Vibrate Only's location permission is \"Allow all the time\". Connected car "
                + "Bluetooth or earbuds keep Vibrate Only off until they disconnect.", 14));
    }

    /** Accepts "51.5007, -0.1246" (Google Maps: press and hold a spot, tap the numbers to copy). */
    private void enterCoordinates() {
        EditText input = new EditText(this);
        input.setHint("51.50070, -0.12460");
        if (Prefs.hasHome(this)) input.setText(Prefs.homeLat(this) + ", " + Prefs.homeLng(this));
        new AlertDialog.Builder(this)
                .setTitle("Home coordinates")
                .setMessage("In Google Maps, press and hold your home, then tap the numbers "
                        + "that appear to copy them, and paste them here.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String[] parts = input.getText().toString().replace("(", "").replace(")", "")
                            .split("[,\\s]+");
                    try {
                        double lat = Double.parseDouble(parts[0].trim());
                        double lng = Double.parseDouble(parts[1].trim());
                        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new NumberFormatException();
                        Prefs.setHome(this, lat, lng);
                        Home.forgetState(this);
                        changed();
                        render();
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "That doesn't look like coordinates",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void heading(String s) {
        TextView h = text(s, 18);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(24), 0, dp(4));
        content.addView(h);
    }

    private TextView bold(String s) {
        TextView t = text(s, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView link(String s) {
        TextView t = text(s, 15);
        t.setTextColor(0xFF3D5AFE);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
