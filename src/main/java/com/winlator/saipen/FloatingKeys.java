package com.winlator.saipen;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.R;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Keys on the screen, for SAI without a keyboard.
 *
 * A small dot floats over SAI. Drag it anywhere; tap it and it opens into
 * the buttons each person picked on SAI Pen's screen (FloatingKeysSettings,
 * Shortcut), up to 8, of two kinds:
 * - pressed once per tap: Ctrl+Z, Ctrl+T…;
 * - held: a tap keeps the keys down (the button lit) for the pen until the
 *   next tap, or they stay down while a finger is on the button
 *   (ModifierKeys). Ctrl held moves a perspective ruler's handles.
 * Open, the grip on the left drags the buttons and × in the top corner
 * closes them back into the dot; a key still down keeps the dot lit. A long
 * press on the dot hides it, and three fingers held still for a moment
 * (FingersHold) show or hide it, if that is switched on.
 *
 * Why not another tap: two fingers are the right click, three the keyboard,
 * four the menu. Why not Winlator's own on-screen controls
 * (InputControlsView): every touch, the pen's too, then goes through that
 * view first, and its two-finger moves reach the canvas once per finger, so
 * scroll and zoom would run double.
 *
 * The buttons sit beside the canvas, not over it: touches outside them reach
 * the canvas as before.
 */
public final class FloatingKeys {
    /** Show the dot when SAI starts. */
    public static final String PREF_ON = "saipen_keys_on";
    public static final boolean ON_BY_DEFAULT = true;
    /** The buttons, one per line (Shortcut.joinList). */
    public static final String PREF_BUTTONS = "saipen_keys_buttons";
    /** An index into COLORS_BACK / COLORS_LIT. */
    public static final String PREF_COLOR = "saipen_keys_color";
    /** Three fingers held still show or hide the dot. */
    public static final String PREF_GESTURE = "saipen_keys_gesture";
    public static final boolean GESTURE_BY_DEFAULT = true;

    private static final String PREF_X = "saipen_keys_x", PREF_Y = "saipen_keys_y";
    /* where the dot is the first time: the right edge, a third down */
    private static final float DEFAULT_X = 1f, DEFAULT_Y = 0.35f;
    /* a press on the dot this long, without moving, hides it */
    private static final long HIDE_MS = 700;

    /** The colours to choose from: the buttons' background (a little
     *  see-through) and a button that is down. The first, lilac, is the one
     *  before anyone chooses. */
    public static final int[] COLORS_BACK = {
        Color.argb(0xD8, 0x3D, 0x2C, 0x5A),   /* lilac */
        Color.argb(0xD8, 0x2A, 0x2A, 0x2A),   /* graphite */
        Color.argb(0xD8, 0x1C, 0x3A, 0x37),   /* teal */
        Color.argb(0xD8, 0x4A, 0x22, 0x33),   /* rose */
        Color.argb(0xD8, 0x4A, 0x33, 0x14),   /* amber */
        Color.argb(0xD8, 0x1A, 0x33, 0x50),   /* sky */
    };
    public static final int[] COLORS_LIT = {
        Color.rgb(0xB3, 0x9D, 0xDB),
        Color.rgb(0x9E, 0x9E, 0x9E),
        Color.rgb(0x4D, 0xB6, 0xAC),
        Color.rgb(0xF0, 0x62, 0x92),
        Color.rgb(0xFF, 0xB7, 0x4D),
        Color.rgb(0x64, 0xB5, 0xF6),
    };
    private static final int TEXT = Color.rgb(0xF5, 0xF4, 0xFD);
    private static final int TEXT_DIM = Color.argb(0x99, 0xF5, 0xF4, 0xFD);
    private static final float TEXT_SP = 14;

    private FloatingKeys() {}

    public static int colorIndex(SharedPreferences prefs) {
        int i = prefs.getInt(PREF_COLOR, 0);
        return i >= 0 && i < COLORS_BACK.length ? i : 0;
    }

    public static List<Shortcut> buttons(SharedPreferences prefs) {
        return Shortcut.parseList(prefs.getString(PREF_BUTTONS, Shortcut.DEFAULTS));
    }

    /* shows or hides the keys over SAI now; null when there are none */
    private static Runnable toggle;

    /** Shows the on-screen keys if they are hidden, else hides them (a
     *  gesture's ON_SCREEN_KEYS). Nothing if SAI has none. */
    public static void toggle() {
        if (toggle != null) toggle.run();
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void show(Activity activity, FrameLayout root, XServer xServer) {
        final float dp = activity.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final boolean onAtStart = prefs.getBoolean(PREF_ON, ON_BY_DEFAULT);
        final boolean gesture = prefs.getBoolean(PREF_GESTURE, GESTURE_BY_DEFAULT);
        final List<Shortcut> buttons = buttons(prefs);
        /* a finger or pen gesture may show them too (Gestures) */
        final boolean byGesture = Gestures.usesOnScreenKeys(Gestures.actions(prefs.getAll()));
        if ((!onAtStart && !gesture && !byGesture) || buttons.isEmpty()) return;
        final int color = colorIndex(prefs);
        final int lit = COLORS_LIT[color];
        final int pressed = blend(lit, Color.WHITE, 0.45f);
        final Handler ui = new Handler(Looper.getMainLooper());

        /* each X key counts its presses (KeyPresses), so a once-button's
         * Ctrl does not let go of a held Ctrl, nor a gesture's */
        final KeyPress keyPress = new KeyPress() {
            @Override public void press(XKeycode k) { KeyPresses.press(xServer, k); }
            @Override public void release(XKeycode k) { KeyPresses.release(xServer, k); }
        };
        final List<List<XKeycode>> xkeys = new ArrayList<>();
        for (Shortcut s : buttons) {
            List<XKeycode> l = new ArrayList<>();
            for (String name : s.xKeys()) l.add(XKeycode.valueOf(name));
            xkeys.add(l);
        }
        final ModifierKeys held = new ModifierKeys(buttons.size(), new ModifierKeys.Sink() {
            @Override public void press(int i) { for (XKeycode k : xkeys.get(i)) keyPress.press(k); }
            @Override public void release(int i) {
                List<XKeycode> l = xkeys.get(i);
                for (int j = l.size() - 1; j >= 0; j--) keyPress.release(l.get(j));
            }
        });

        final LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackground(rounded(COLORS_BACK[color], 12 * dp));
        int pad = (int) (3 * dp);
        panel.setPadding(pad, pad, pad, pad);

        /* closed: the dot, named after the first held button */
        String dotLabel = activity.getString(R.string.saipen_keys_dot);
        for (Shortcut s : buttons) {
            if (s.held && s.label().length() <= 5) { dotLabel = s.label(); break; }
        }
        final TextView dot = button(activity, dp, dotLabel, 0);
        panel.addView(dot);

        /* open: the grip, the buttons in even columns, × in the top corner */
        final LinearLayout open = new LinearLayout(activity);
        open.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(open);
        final TextView grip = new TextView(activity);
        grip.setText("⋮");
        grip.setTextColor(TEXT_DIM);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        grip.setGravity(Gravity.CENTER);
        open.addView(grip, new LinearLayout.LayoutParams((int) (24 * dp), ViewGroup.LayoutParams.MATCH_PARENT));

        TextPaint paintText = new TextPaint();
        paintText.setTextSize(TEXT_SP * activity.getResources().getDisplayMetrics().scaledDensity);
        float widest = 0;
        for (Shortcut s : buttons) widest = Math.max(widest, paintText.measureText(s.label()));
        final int cellWidth = (int) Math.max(44 * dp, widest + 16 * dp);
        /* the buttons may take half the screen's width */
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int cols = Shortcut.columns(buttons.size(), (int) ((screenW * 0.5f - 56 * dp) / cellWidth));
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        open.addView(grid);
        LinearLayout row = null;
        final List<TextView> views = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            TextView t = button(activity, dp, buttons.get(i).label(), cellWidth);
            row.addView(t, new LinearLayout.LayoutParams(cellWidth, (int) (40 * dp)));
            views.add(t);
        }
        final TextView close = new TextView(activity);
        close.setText("×");
        close.setTextColor(TEXT_DIM);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        open.addView(close, new LinearLayout.LayoutParams((int) (32 * dp), (int) (40 * dp)));

        final Runnable paint = () -> {
            for (int i = 0; i < views.size(); i++) {
                int c = !held.isDown(i) ? Color.TRANSPARENT : held.isLatched(i) ? lit : pressed;
                views.get(i).setBackground(rounded(c, 9 * dp));
            }
            dot.setBackground(rounded(held.anyDown() ? lit : Color.TRANSPARENT, 9 * dp));
        };
        final Runnable keepInside = () -> {
            float maxX = Math.max(0, root.getWidth() - panel.getWidth());
            float maxY = Math.max(0, root.getHeight() - panel.getHeight());
            panel.setX(Math.max(0, Math.min(panel.getX(), maxX)));
            panel.setY(Math.max(0, Math.min(panel.getY(), maxY)));
        };
        final boolean[] isOpen = {false};
        final Runnable closeButtons = () -> {
            /* the dot appears where the × was, so it stays under the finger */
            float right = panel.getX() + panel.getWidth();
            isOpen[0] = false;
            dot.setVisibility(View.VISIBLE);
            open.setVisibility(View.GONE);
            paint.run();
            panel.post(() -> {
                if (right > 0) panel.setX(right - panel.getWidth());
                keepInside.run();
            });
        };
        final Runnable openButtons = () -> {
            isOpen[0] = true;
            dot.setVisibility(View.GONE);
            open.setVisibility(View.VISIBLE);
            paint.run();
            panel.post(keepInside);
        };
        final Runnable hide = () -> {
            held.releaseAll();
            if (isOpen[0]) closeButtons.run();
            panel.setVisibility(View.GONE);
        };
        final Runnable unhide = () -> {
            panel.setVisibility(View.VISIBLE);
            panel.post(keepInside);
        };
        toggle = () -> {
            if (panel.getVisibility() == View.VISIBLE) hide.run();
            else unhide.run();
        };

        /* the dot drags, opens on a tap and hides on a long press; the grip drags */
        dot.setOnTouchListener(dragger(activity, root, panel, prefs, keepInside, openButtons, () -> {
            hide.run();
            Toast.makeText(activity, gesture ? R.string.saipen_keys_hidden_gesture
                : R.string.saipen_keys_hidden, Toast.LENGTH_LONG).show();
        }));
        grip.setOnTouchListener(dragger(activity, root, panel, prefs, keepInside, null, null));
        close.setOnClickListener(v -> closeButtons.run());

        for (int i = 0; i < buttons.size(); i++) {
            final int index = i;
            final TextView t = views.get(i);
            if (buttons.get(i).held) {
                /* down on touch, then a tap latches and a hold lets go */
                t.setOnTouchListener((v, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            held.down(index, SystemClock.uptimeMillis());
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            held.up(index, SystemClock.uptimeMillis());
                            break;
                        default:
                            return true;
                    }
                    paint.run();
                    return true;
                });
            } else {
                /* the whole combination once per tap */
                t.setOnClickListener(v -> {
                    t.setBackground(rounded(pressed, 9 * dp));
                    pressOnce(ui, keyPress, xkeys.get(index),
                        () -> t.setBackground(rounded(Color.TRANSPARENT, 9 * dp)));
                });
            }
        }

        /* three fingers held still: show or hide */
        if (gesture) {
            final FingersHold hold = new FingersHold(24 * dp);
            final Runnable check = () -> {
                if (!hold.due(SystemClock.uptimeMillis())) return;
                if (panel.getVisibility() == View.VISIBLE) hide.run();
                else unhide.run();
                panel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            };
            watchTouches(activity, e -> {
                int n = e.getPointerCount();
                int action = e.getActionMasked();
                boolean allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
                int lifted = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
                int count = allUp ? 0 : lifted >= 0 ? n - 1 : n;
                float[] xs = new float[count], ys = new float[count];
                for (int i = 0, j = 0; i < n && j < count; i++) {
                    if (i == lifted) continue;
                    xs[j] = e.getX(i);
                    ys[j] = e.getY(i);
                    j++;
                }
                long before = hold.when();
                hold.onTouch(count, xs, ys, SystemClock.uptimeMillis());
                long when = hold.when();
                if (when >= 0 && when != before) ui.postAtTime(check, when);
            });
        }

        /* no key stays down in SAI once the buttons are gone */
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                held.releaseAll();
                toggle = null;
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(panel, lp);
        open.setVisibility(View.GONE);
        paint.run();
        if (!onAtStart) panel.setVisibility(View.GONE);
        panel.post(() -> {
            panel.setX(prefs.getFloat(PREF_X, DEFAULT_X) * Math.max(0, root.getWidth() - panel.getWidth()));
            panel.setY(prefs.getFloat(PREF_Y, DEFAULT_Y) * Math.max(0, root.getHeight() - panel.getHeight()));
            keepInside.run();
        });
    }

    interface KeyPress {
        void press(XKeycode k);
        void release(XKeycode k);
    }

    /**
     * A drag moves the whole panel and remembers where; without a drag, a
     * short touch runs `onTap` and a long one `onLongPress` (either may be null).
     */
    @SuppressLint("ClickableViewAccessibility")
    private static View.OnTouchListener dragger(Activity activity, FrameLayout root, View panel,
                                                SharedPreferences prefs, Runnable keepInside,
                                                Runnable onTap, Runnable onLongPress) {
        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        return new View.OnTouchListener() {
            float startRawX, startRawY, startX, startY;
            long downAt;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = e.getRawX();
                        startRawY = e.getRawY();
                        startX = panel.getX();
                        startY = panel.getY();
                        downAt = SystemClock.uptimeMillis();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - startRawX, dy = e.getRawY() - startRawY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging) {
                            panel.setX(startX + dx);
                            panel.setY(startY + dy);
                            keepInside.run();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) savePosition(prefs, root, panel);
                        else if (SystemClock.uptimeMillis() - downAt >= HIDE_MS) {
                            if (onLongPress != null) onLongPress.run();
                        } else if (onTap != null) onTap.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        };
    }

    /**
     * One press of a combination: modifiers and key down, a moment apart so
     * Wine sees each, then up in the other order.
     */
    private static void pressOnce(Handler ui, KeyPress keyPress, List<XKeycode> keys, Runnable done) {
        long t = 0;
        for (XKeycode k : keys) {
            ui.postDelayed(() -> keyPress.press(k), t);
            t += 20;
        }
        t += 30;
        for (int i = keys.size() - 1; i >= 0; i--) {
            final XKeycode k = keys.get(i);
            ui.postDelayed(() -> keyPress.release(k), t);
            t += 20;
        }
        ui.postDelayed(done, t);
    }

    interface TouchWatcher { void onTouch(MotionEvent e); }

    /**
     * Sees every touch on SAI's screen before the screen's own views get it,
     * and changes nothing: the activity's window callback is wrapped, and each
     * call goes on to the one it had.
     */
    static void watchTouches(Activity activity, TouchWatcher watcher) {
        Window window = activity.getWindow();
        final Window.Callback inner = window.getCallback();
        Window.Callback outer = (Window.Callback) Proxy.newProxyInstance(
            Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
            (proxy, method, args) -> {
                if ("dispatchTouchEvent".equals(method.getName()) && args != null && args[0] instanceof MotionEvent) {
                    try {
                        watcher.onTouch((MotionEvent) args[0]);
                    } catch (RuntimeException ignored) {
                        /* a watcher's mistake must never cost a touch */
                    }
                }
                try {
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ite) {
                    throw ite.getCause();
                }
            });
        window.setCallback(outer);
    }

    /** Where the panel is, as a share of the room it has, so it stays at the
     *  same edge when the screen turns. */
    private static void savePosition(SharedPreferences prefs, FrameLayout root, View panel) {
        float roomX = Math.max(1, root.getWidth() - panel.getWidth());
        float roomY = Math.max(1, root.getHeight() - panel.getHeight());
        prefs.edit()
            .putFloat(PREF_X, Math.max(0f, Math.min(1f, panel.getX() / roomX)))
            .putFloat(PREF_Y, Math.max(0f, Math.min(1f, panel.getY() / roomY)))
            .apply();
    }

    private static TextView button(Activity activity, float dp, String label, int width) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (width == 0) {
            t.setMinWidth((int) (44 * dp));
            t.setMinHeight((int) (40 * dp));
            int side = (int) (8 * dp);
            t.setPadding(side, 0, side, 0);
        }
        return t;
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
            (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
            (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
            (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
