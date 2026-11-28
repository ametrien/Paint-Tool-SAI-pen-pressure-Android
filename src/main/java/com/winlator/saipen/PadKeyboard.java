package com.winlator.saipen;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.ArrayList;
import java.util.List;

/**
 * SAI Pen's own keyboard over SAI (PadKeyboardLayout says why and which
 * keys). It looks like the on-screen keys: their colour and opacity
 * (FloatingKeys.PREF_COLOR, KeysOpacity), the same text and lit colours.
 * Shown and hidden by a gesture set to it (Gestures.Type.PAD_KEYBOARD) or by
 * the ⌨ button among the on-screen keys (Shortcut.keyboard()).
 *
 * The bar on top drags it, and it remembers where (bottom centre at first);
 * × hides it. A key is down in X while a finger (or the pen) is on it, like
 * a real key; ⌫, Del and the arrows repeat while held. Ctrl, Alt and Shift:
 * PadKeyboardLayout.Modifiers. The picture does not move up for it, as it
 * does for the tablet's keyboard: it floats, so drag it out of the way.
 *
 * Built the first time it is shown, and again when the keys' colour or
 * opacity changes. Main thread only.
 */
public final class PadKeyboard {
    private static final String PREF_X = "saipen_kbd_x", PREF_Y = "saipen_kbd_y";
    private static final float DEFAULT_X = 0.5f, DEFAULT_Y = 1f;
    private static final long REPEAT_FIRST_MS = 400, REPEAT_MS = 50;

    private static final int TEXT = Color.rgb(0xF5, 0xF4, 0xFD);
    private static final int TEXT_DIM = Color.argb(0x99, 0xF5, 0xF4, 0xFD);
    /* each key a little lighter than the panel, so the keys read as keys */
    private static final int KEY_FILL = Color.argb(0x24, 0xFF, 0xFF, 0xFF);

    private static Activity activity;
    private static FrameLayout root;
    private static XServer xServer;
    private static View panel;
    private static Runnable releaseAll;
    /* kept: SharedPreferences holds its listeners weakly */
    private static SharedPreferences.OnSharedPreferenceChangeListener restyle;

    private PadKeyboard() {}

    /** Once per SAI session, when SAI is up (StartupCover). Shows nothing yet. */
    public static void attach(Activity a, FrameLayout r, XServer x) {
        activity = a;
        root = r;
        xServer = x;
        panel = null;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        restyle = (p, key) -> {
            if (!FloatingKeys.PREF_COLOR.equals(key) && !KeysOpacity.PREF.equals(key)) return;
            if (panel == null) return;
            boolean wasShown = panel.getVisibility() == View.VISIBLE;
            remove();
            build();
            if (!wasShown && panel != null) panel.setVisibility(View.GONE);
        };
        prefs.registerOnSharedPreferenceChangeListener(restyle);
    }

    public static boolean isShown() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    /** Shows the keyboard if it is hidden, else hides it. */
    public static void toggle() {
        if (activity == null || root == null) return;
        if (isShown()) hide();
        else show();
    }

    public static void show() {
        if (activity == null || root == null) return;
        if (panel == null) build();
        if (panel == null) return;
        panel.setVisibility(View.VISIBLE);
        panel.post(() -> keepInside(panel));
        FloatingKeys.repaint();
    }

    public static void hide() {
        if (releaseAll != null) releaseAll.run();
        if (panel != null) panel.setVisibility(View.GONE);
        FloatingKeys.repaint();
    }

    private static void remove() {
        if (releaseAll != null) releaseAll.run();
        if (panel != null && panel.getParent() == root) root.removeView(panel);
        panel = null;
        releaseAll = null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void build() {
        final Activity a = activity;
        final float dp = a.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        final int color = FloatingKeys.colorIndex(prefs);
        final int lit = FloatingKeys.COLORS_LIT[color];
        final int opacity = KeysOpacity.of(prefs.getInt(KeysOpacity.PREF, KeysOpacity.BY_DEFAULT));
        final Handler ui = new Handler(Looper.getMainLooper());

        /* as wide as fits, up to a laptop keyboard's width; keys about square */
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int pad = (int) (4 * dp);
        int width = (int) Math.min(screenW * 0.96f, 760 * dp);
        float unit = (width - 2 * pad) / PadKeyboardLayout.ROW_UNITS;
        int keyH = (int) Math.max(34 * dp, Math.min(unit * 0.92f, 50 * dp));
        /* five rows and the bar in at most half the screen's height */
        int barH = (int) (24 * dp);
        keyH = Math.min(keyH, (int) ((screenH * 0.5f - barH - 2 * pad) / PadKeyboardLayout.ROWS.size()));
        final int keyHeight = keyH;

        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(FloatingKeys.rounded(KeysOpacity.apply(FloatingKeys.COLORS_BACK[color], opacity), 12 * dp));
        box.setPadding(pad, 0, pad, pad);
        /* touches on the keyboard stay on it, never reach the canvas */
        box.setClickable(true);

        /* the bar: drag here; × hides */
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView grip = new TextView(a);
        grip.setText("⋯");
        grip.setTextColor(TEXT_DIM);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        grip.setGravity(Gravity.CENTER);
        bar.addView(grip, new LinearLayout.LayoutParams(0, barH, 1));
        TextView close = new TextView(a);
        close.setText("×");
        close.setTextColor(TEXT_DIM);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        bar.addView(close, new LinearLayout.LayoutParams((int) (40 * dp), barH));
        box.addView(bar);

        final PadKeyboardLayout.Modifiers mods = new PadKeyboardLayout.Modifiers();
        final List<TextView> views = new ArrayList<>();
        final List<PadKeyboardLayout.Key> keys = new ArrayList<>();

        final Runnable paint = () -> {
            boolean shift = mods.on(PadKeyboardLayout.Kind.SHIFT);
            for (int i = 0; i < views.size(); i++) {
                PadKeyboardLayout.Key k = keys.get(i);
                TextView v = views.get(i);
                if (k.isModifier()) {
                    PadKeyboardLayout.ModState s = mods.state(k.kind);
                    v.setBackground(FloatingKeys.rounded(s == PadKeyboardLayout.ModState.LOCKED ? lit
                        : s == PadKeyboardLayout.ModState.ONCE ? blend(lit, 0x55) : KEY_FILL, 7 * dp));
                    v.setTextColor(s == PadKeyboardLayout.ModState.LOCKED ? Color.BLACK : TEXT);
                } else if (!v.isPressed()) {
                    v.setText(k.label(shift));
                }
            }
        };
        final Runnable[] modsOff = new Runnable[1];
        modsOff[0] = () -> {
            for (PadKeyboardLayout.Kind m : mods.clear()) KeyPresses.release(xServer, XKeycode.valueOf(PadKeyboardLayout.xKey(m)));
        };

        for (List<PadKeyboardLayout.Key> row : PadKeyboardLayout.ROWS) {
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (PadKeyboardLayout.Key k : row) {
                final TextView v = new TextView(a);
                v.setText(k.label);
                v.setTextColor(TEXT);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.label.length() > 1 ? 13 : 16);
                v.setGravity(Gravity.CENTER);
                v.setSingleLine(true);
                v.setBackground(FloatingKeys.rounded(KEY_FILL, 7 * dp));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, keyHeight, k.units);
                int m = (int) Math.max(1, 1.5f * dp);
                lp.setMargins(m, m, m, m);
                line.addView(v, lp);
                views.add(v);
                keys.add(k);
                v.setOnTouchListener(keyTouch(ui, k, v, mods, paint, dp, lit));
            }
            box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        close.setOnClickListener(v -> hide());
        grip.setOnTouchListener(dragger(a, box, prefs));
        releaseAll = () -> {
            modsOff[0].run();
            paint.run();
        };
        /* no key stays down in SAI once the keyboard is gone */
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { modsOff[0].run(); }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(box, lp);
        panel = box;
        paint.run();
        box.post(() -> {
            box.setX(prefs.getFloat(PREF_X, DEFAULT_X) * Math.max(0, root.getWidth() - box.getWidth()));
            box.setY(prefs.getFloat(PREF_Y, DEFAULT_Y) * Math.max(0, root.getHeight() - box.getHeight()));
            keepInside(box);
        });
    }

    /** One key: down in X while touched (repeating if it repeats); a modifier steps on a tap. */
    @SuppressLint("ClickableViewAccessibility")
    private static View.OnTouchListener keyTouch(Handler ui, PadKeyboardLayout.Key k, TextView v,
                                                 PadKeyboardLayout.Modifiers mods, Runnable paint,
                                                 float dp, int lit) {
        final XKeycode x = XKeycode.valueOf(k.xKey);
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            KeyPresses.release(xServer, x);
            KeyPresses.press(xServer, x);
            ui.postDelayed(repeat[0], REPEAT_MS);
        };
        return (view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (k.isModifier()) {
                        PadKeyboardLayout.ModState s = mods.tap(k.kind);
                        if (s == PadKeyboardLayout.ModState.ONCE) KeyPresses.press(xServer, x);
                        else if (s == PadKeyboardLayout.ModState.OFF) KeyPresses.release(xServer, x);
                        paint.run();
                        return true;
                    }
                    v.setPressed(true);
                    v.setBackground(FloatingKeys.rounded(lit, 7 * dp));
                    v.setTextColor(Color.BLACK);
                    KeyPresses.press(xServer, x);
                    if (k.kind == PadKeyboardLayout.Kind.REPEAT) ui.postDelayed(repeat[0], REPEAT_FIRST_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (k.isModifier()) return true;
                    ui.removeCallbacks(repeat[0]);
                    KeyPresses.release(xServer, x);
                    v.setPressed(false);
                    v.setBackground(FloatingKeys.rounded(KEY_FILL, 7 * dp));
                    v.setTextColor(TEXT);
                    /* Shift, Ctrl, Alt for this key only: up again after it */
                    for (PadKeyboardLayout.Kind m : mods.typed())
                        KeyPresses.release(xServer, XKeycode.valueOf(PadKeyboardLayout.xKey(m)));
                    paint.run();
                    return true;
                default:
                    return true;
            }
        };
    }

    /** The bar drags the keyboard and remembers where, as a share of the room. */
    @SuppressLint("ClickableViewAccessibility")
    private static View.OnTouchListener dragger(Activity a, View box, SharedPreferences prefs) {
        final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        return new View.OnTouchListener() {
            float startRawX, startRawY, startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = e.getRawX();
                        startRawY = e.getRawY();
                        startX = box.getX();
                        startY = box.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - startRawX, dy = e.getRawY() - startRawY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging) {
                            box.setX(startX + dx);
                            box.setY(startY + dy);
                            keepInside(box);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            float roomX = Math.max(1, root.getWidth() - box.getWidth());
                            float roomY = Math.max(1, root.getHeight() - box.getHeight());
                            prefs.edit()
                                .putFloat(PREF_X, Math.max(0f, Math.min(1f, box.getX() / roomX)))
                                .putFloat(PREF_Y, Math.max(0f, Math.min(1f, box.getY() / roomY)))
                                .apply();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    private static void keepInside(View v) {
        if (root == null) return;
        float maxX = Math.max(0, root.getWidth() - v.getWidth());
        float maxY = Math.max(0, root.getHeight() - v.getHeight());
        v.setX(Math.max(0, Math.min(v.getX(), maxX)));
        v.setY(Math.max(0, Math.min(v.getY(), maxY)));
    }

    /** `rgb` with this alpha. */
    private static int blend(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
