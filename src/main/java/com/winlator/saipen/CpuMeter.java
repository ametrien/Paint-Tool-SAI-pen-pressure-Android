package com.winlator.saipen;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * A small meter over SAI: how much of what it can use SAI uses now
 * ("SAI 70% of 200%") with a bar, green, yellow near the top, red when
 * full, so an artist sees SAI getting busy before it stops answering
 * (BusySai). Off by default; switched on under Fast cores on SAI Pen's
 * screen (PREF_ON), and on or off over SAI as soon as it changes.
 * Bottom right at first; dragged anywhere, it stays there.
 *
 * The speed log's thread gives it SAI's use once a second (update()).
 */
public final class CpuMeter {
    public static final String PREF_ON = "saipen_cpu_meter";
    public static final boolean ON_BY_DEFAULT = false;
    private static final String PREF_X = "saipen_cpu_meter_x", PREF_Y = "saipen_cpu_meter_y";
    /** A share of the room left and right / above and below: bottom right. */
    private static final float DEFAULT_X = 0.98f, DEFAULT_Y = 0.97f;

    static final int GREEN = 0xFF4CAF50, YELLOW = 0xFFFFC107, RED = 0xFFF44336;
    /** From this share of all SAI can use the bar is yellow, from FULL red (BusySai's full). */
    static final double YELLOW_FROM = 0.6, RED_FROM = BusySai.FULL_SHARE;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static WeakReference<Meter> shown = new WeakReference<>(null);
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private CpuMeter() {}

    /** 0 green, 1 yellow, 2 red. */
    static int level(int saiPercent, int cores) {
        double s = saiPercent / (100.0 * Math.max(1, cores));
        return s >= RED_FROM ? 2 : s >= YELLOW_FROM ? 1 : 0;
    }

    static int color(int level) {
        return level == 2 ? RED : level == 1 ? YELLOW : GREEN;
    }

    /** "SAI 70% of 200%", "SAI ?" before the first second; " · every core" while BusySai opened them. */
    static String label(int saiPercent, int cores, boolean everyCore) {
        if (saiPercent < 0) return "SAI ?";
        return String.format(Locale.ROOT, "SAI %d%% of %d%%", saiPercent, 100 * Math.max(1, cores))
               + (everyCore ? " · every core" : "");
    }

    /** The bar's share, 0 to 1. */
    static float fill(int saiPercent, int cores) {
        if (saiPercent <= 0) return 0;
        return (float) Math.min(1.0, saiPercent / (100.0 * Math.max(1, cores)));
    }

    /** Once per SAI session, when SAI is up (StartupCover). */
    public static void attach(Activity activity, FrameLayout root) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (listener != null) prefs.unregisterOnSharedPreferenceChangeListener(listener);
        listener = (p, key) -> {
            if (!PREF_ON.equals(key)) return;
            UI.post(() -> {
                hide();
                if (p.getBoolean(PREF_ON, ON_BY_DEFAULT)) show(activity, root, prefs);
            });
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        hide();
        if (prefs.getBoolean(PREF_ON, ON_BY_DEFAULT)) show(activity, root, prefs);
    }

    /** SAI's use this second (SpeedSession, its own thread). */
    public static void update(int saiPercent, int cores, boolean everyCore) {
        if (shown.get() == null) return;
        UI.post(() -> {
            Meter m = shown.get();
            if (m != null) m.set(saiPercent, cores, everyCore);
        });
    }

    private static void hide() {
        Meter m = shown.get();
        if (m != null && m.box.getParent() instanceof ViewGroup) ((ViewGroup) m.box.getParent()).removeView(m.box);
        shown = new WeakReference<>(null);
    }

    private static void show(Activity activity, FrameLayout root, SharedPreferences prefs) {
        Meter m = new Meter(activity, root, prefs);
        shown = new WeakReference<>(m);
    }

    private static final class Meter {
        final LinearLayout box;
        final TextView text;
        final View bar;
        final FrameLayout track;

        @SuppressLint("ClickableViewAccessibility")
        Meter(Activity activity, FrameLayout root, SharedPreferences prefs) {
            float dp = activity.getResources().getDisplayMetrics().density;
            box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (8 * dp);
            box.setPadding(pad, (int) (5 * dp), pad, (int) (6 * dp));
            box.setBackground(FloatingKeys.rounded(0xC0202020, 9 * dp));
            /* above SAI, under the closing and loading covers (64 dp) */
            box.setElevation(30 * dp);

            text = new TextView(activity);
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            text.setGravity(Gravity.CENTER);
            text.setText(label(-1, 1, false));
            box.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                            ViewGroup.LayoutParams.WRAP_CONTENT));

            track = new FrameLayout(activity);
            track.setBackground(FloatingKeys.rounded(0x40FFFFFF, 2 * dp));
            bar = new View(activity);
            bar.setBackground(FloatingKeys.rounded(GREEN, 2 * dp));
            bar.setPivotX(0);
            bar.setScaleX(0);
            track.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                            ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams((int) (110 * dp), (int) (4 * dp));
            tl.topMargin = (int) (4 * dp);
            box.addView(track, tl);

            root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                           ViewGroup.LayoutParams.WRAP_CONTENT));
            box.post(() -> {
                box.setX(prefs.getFloat(PREF_X, DEFAULT_X) * Math.max(0, root.getWidth() - box.getWidth()));
                box.setY(prefs.getFloat(PREF_Y, DEFAULT_Y) * Math.max(0, root.getHeight() - box.getHeight()));
            });

            final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            box.setOnTouchListener(new View.OnTouchListener() {
                float rawX, rawY, x, y;
                boolean dragging;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            rawX = e.getRawX();
                            rawY = e.getRawY();
                            x = box.getX();
                            y = box.getY();
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - rawX, dy = e.getRawY() - rawY;
                            if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                            if (dragging) {
                                box.setX(clamp(x + dx, root.getWidth() - box.getWidth()));
                                box.setY(clamp(y + dy, root.getHeight() - box.getHeight()));
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (dragging) {
                                float w = Math.max(1, root.getWidth() - box.getWidth());
                                float h = Math.max(1, root.getHeight() - box.getHeight());
                                prefs.edit().putFloat(PREF_X, box.getX() / w).putFloat(PREF_Y, box.getY() / h).apply();
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        void set(int saiPercent, int cores, boolean everyCore) {
            text.setText(label(saiPercent, cores, everyCore));
            int level = level(Math.max(0, saiPercent), cores);
            bar.setBackground(FloatingKeys.rounded(color(level), bar.getHeight() / 2f));
            bar.setScaleX(fill(saiPercent, cores));
        }
    }

    private static float clamp(float v, float max) {
        return Math.max(0, Math.min(v, Math.max(0, max)));
    }
}
