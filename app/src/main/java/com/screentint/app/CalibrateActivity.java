package com.screentint.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Calibrate by eye: test images with the warmth, tint and brightness sliders next to them. The tint
 * layer covers this screen too, so every change shows on the images straight away.
 */
public class CalibrateActivity extends Activity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        TextView title = text("Calibrate by eye", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);
        content.addView(text("Best results: daylight or a neutral room light, screen at your usual brightness, and "
                + "Settings > Display > Screen mode set to Natural (the phone's most accurate mode).", 14));

        heading("1. Greys should have no colour");
        content.addView(text("Look at the grey steps. If they lean yellow or orange, move Warmth towards cooler; "
                + "if they lean blue, towards warmer. If they lean green, move Tint towards pinker; if "
                + "pink or purple, towards greener. Small moves: a few points make a visible difference.", 14));
        content.addView(new Pattern(this, Pattern.RAMP), new LinearLayout.LayoutParams(-1, dp(90)));
        content.addView(new Pattern(this, Pattern.GREYS), new LinearLayout.LayoutParams(-1, dp(120)));
        AdjustRow.add(this, content, "Warmth", "Cooler ← → warmer", Tint.KEY_WARMTH, -100f, 100f, 0f, 1f, "",
                () -> {});
        AdjustRow.add(this, content, "Tint", "Greener ← → pinker", Tint.KEY_TINT, -100f, 100f, 0f, 1f, "",
                () -> {});

        heading("2. White against paper");
        content.addView(text("Open a full white screen and hold a sheet of plain white printer paper next to the "
                + "phone in daylight. Match the colour (not the brightness) with Warmth and Tint.", 14));
        content.addView(button("Show full white screen (tap it to close)", this::showWhite));

        heading("3. Check real colours");
        content.addView(text("Colours should look clean, and skin tones natural rather than pink, yellow or "
                + "grey. If they look washed out, the tint is too strong: go back towards 0.", 14));
        content.addView(new Pattern(this, Pattern.BARS), new LinearLayout.LayoutParams(-1, dp(70)));
        content.addView(new Pattern(this, Pattern.SKIN), new LinearLayout.LayoutParams(-1, dp(70)));

        heading("4. Brightness (optional)");
        content.addView(text("Dims the whole screen evenly, below the phone's own lowest brightness.", 14));
        AdjustRow.add(this, content, "Brightness", null, Tint.KEY_BRIGHTNESS, 20f, 100f, 100f, 1f, "%", () -> {});

        heading("Good to know");
        content.addView(text("• A phone can't measure its own screen, so this is calibration by eye. For lab "
                + "accuracy you'd need a colorimeter.\n"
                + "• Reviews measured the Galaxy S26 Ultra at an average colour error (ΔE) of about 2-2.6 in "
                + "Natural mode, which is close to what the eye can notice, so most phones need little or "
                + "no correction. Samsung has said some S26 Ultras show a red cast in some conditions and "
                + "that a software fix is coming; if yours does, a little Tint towards greener (or Warmth "
                + "towards cooler) helps until then.\n"
                + "• Vivid mode has the phone's own white balance and red/green/blue sliders under Screen "
                + "mode > Advanced settings. Those change the screen itself rather than laying a colour "
                + "over it, so for big corrections they're the better tool; use Screen Tint for fine "
                + "touches and for going warmer or dimmer than the phone allows.", 13));
    }

    private void showWhite() {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        View white = new View(this);
        white.setBackgroundColor(Color.WHITE);
        white.setOnClickListener(v -> d.dismiss());
        d.setContentView(white);
        d.show();
    }

    /** One test image: a grey ramp, grey patches, colour bars or skin and nature tones. */
    static final class Pattern extends View {
        static final int RAMP = 0, GREYS = 1, BARS = 2, SKIN = 3;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pattern(Context c, int kind) {
            super(c);
            this.kind = kind;
            paint.setTextSize(12 * c.getResources().getDisplayMetrics().scaledDensity);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int[] cols;
            switch (kind) {
                case RAMP:
                    cols = new int[11];
                    for (int i = 0; i <= 10; i++) { int v = Math.round(255 * i / 10f); cols[i] = Color.rgb(v, v, v); }
                    break;
                case GREYS:
                    cols = new int[] {Color.rgb(64, 64, 64), Color.rgb(128, 128, 128), Color.rgb(191, 191, 191), Color.WHITE};
                    break;
                case BARS:
                    cols = new int[] {Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW};
                    break;
                default:
                    // Light, medium and dark skin, sky blue and foliage green (typical photo colours).
                    cols = new int[] {Color.rgb(241, 194, 167), Color.rgb(198, 134, 103), Color.rgb(120, 72, 52),
                            Color.rgb(110, 160, 220), Color.rgb(80, 130, 60)};
            }
            float cw = w / (float) cols.length;
            for (int i = 0; i < cols.length; i++) {
                paint.setColor(cols[i]);
                canvas.drawRect(i * cw, 0, (i + 1) * cw + 1, h, paint);
            }
            if (kind == RAMP || kind == GREYS) {
                for (int i = 0; i < cols.length; i++) {
                    int grey = Color.red(cols[i]);
                    paint.setColor(grey > 140 ? Color.BLACK : Color.WHITE);
                    String s = Math.round(grey / 2.55f) + "%";
                    canvas.drawText(s, i * cw + (cw - paint.measureText(s)) / 2, h - 8, paint);
                }
            }
        }
    }

    private void heading(String s) {
        TextView h = text(s, 18);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(22), 0, dp(6));
        content.addView(h);
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
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
