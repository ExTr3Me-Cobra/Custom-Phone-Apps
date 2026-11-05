package com.screentint.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** A labelled slider with − / + buttons for one adjustment (warmth, tint or brightness). */
final class AdjustRow {
    private AdjustRow() {}

    /**
     * Adds the row to [parent]. The value runs from [min] to [max] in [step]s and is saved under
     * [key]; [changed] runs after every change.
     */
    static void add(Activity a, LinearLayout parent, String title, String help, String key, float min, float max,
                    float def, float step, String unit, Runnable changed) {
        float density = a.getResources().getDisplayMetrics().density;
        TextView label = new TextView(a);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, Math.round(12 * density), 0, 0);
        parent.addView(label);
        if (help != null) {
            TextView h = new TextView(a);
            h.setText(help);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            parent.addView(h);
        }
        int steps = Math.round((max - min) / step);
        SeekBar bar = new SeekBar(a);
        bar.setMax(steps);
        Runnable show = () -> {
            float v = Tint.adjust(a, key, def);
            label.setText(String.format(Locale.US, "%s: %s%s", title, (min < 0 && v > 0 ? "+" : "") + trim(v), unit));
        };
        bar.setProgress(Math.round((Tint.adjust(a, key, def) - min) / step));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                set(a, key, min + p * step);
                show.run();
                changed.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(nudge(a, density, "−", () -> {
            float v = Math.max(min, Tint.adjust(a, key, def) - step);
            set(a, key, v);
            bar.setProgress(Math.round((v - min) / step));
            show.run();
            changed.run();
        }));
        line.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        line.addView(nudge(a, density, "+", () -> {
            float v = Math.min(max, Tint.adjust(a, key, def) + step);
            set(a, key, v);
            bar.setProgress(Math.round((v - min) / step));
            show.run();
            changed.run();
        }));
        parent.addView(line);
        show.run();
    }

    private static void set(Activity a, String key, float v) {
        Tint.setAdjust(a, key, v);
        if (!Tint.enabled(a)) Tint.setEnabled(a, true);
    }

    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
    }

    private static Button nudge(Activity a, float density, String label, Runnable action) {
        Button b = new Button(a);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        int s = Math.round(44 * density);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }
}
