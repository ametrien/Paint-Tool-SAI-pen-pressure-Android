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
import com.winlator.XServerDisplayActivity;
import com.winlator.winhandler.SaiPenRequests;
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
 * Shortcut), up to Shortcut.MAX (10), of two kinds:
 * - pressed once per tap: Ctrl+Z, Ctrl+T…;
 * - held: a tap keeps the keys down (the button lit) for the pen until the
 *   next tap, or they stay down while a finger is on the button
 *   (ModifierKeys). Ctrl held moves a perspective ruler's handles.
 * An Exit SAI button may be among them (Shortcut.EXIT_SAI), and, if the
 * artist switches it on (PREF_EXIT_OWN), a big red one of its own, floating
 * apart from the dot (top right at first, dragged like the dot, hidden and
 * shown with it). Either one: two taps (ExitTaps) close SAI the way its red
 * cross does (SaiPenRequests.closeSai).
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

    /** Exit SAI as a red button of its own, apart from the dot. */
    public static final String PREF_EXIT_OWN = "saipen_keys_exit_own";
    public static final boolean EXIT_OWN_BY_DEFAULT = false;
    /** Its size: an ExitButtonSize index. */
    public static final String PREF_EXIT_SIZE = "saipen_keys_exit_size";

    private static final String PREF_X = "saipen_keys_x", PREF_Y = "saipen_keys_y";
    private static final String PREF_EXIT_X = "saipen_keys_exit_x", PREF_EXIT_Y = "saipen_keys_exit_y";
    /* where the red Exit SAI is the first time: top right, under SAI's own
     * title bar and menu */
    private static final float DEFAULT_EXIT_X = 1f, DEFAULT_EXIT_Y = 0.1f;
    /* SAI's own close button: #A00040 behind a white cross (from a tablet screenshot, 2026-11-26) */
    /* as see-through as the keys' panel (COLORS_BACK, alpha 0xD8); the cross stays solid */
    private static final int EXIT_RED = Color.argb(0xD8, 0xA0, 0x00, 0x40);
    private static final int EXIT_RED_ASKING = Color.argb(0xD8, 0xD8, 0x1B, 0x60);
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
        final boolean ownExit = prefs.getBoolean(PREF_EXIT_OWN, EXIT_OWN_BY_DEFAULT);
        if ((!onAtStart && !gesture && !byGesture) || (buttons.isEmpty() && !ownExit)) return;
        /* no buttons, only the red Exit SAI: the dot stays away */
        final boolean hasKeys = !buttons.isEmpty();
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
        final String exitLabel = activity.getString(R.string.saipen_keys_exit);
        final String exitAsk = activity.getString(R.string.saipen_keys_exit_ask);
        float widest = 0;
        for (Shortcut s : buttons) widest = Math.max(widest, paintText.measureText(s.exitSai ? exitLabel : s.label()));
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
            Shortcut s = buttons.get(i);
            TextView t = button(activity, dp, s.exitSai ? exitLabel : s.label(), cellWidth);
            row.addView(t, new LinearLayout.LayoutParams(cellWidth, (int) (40 * dp)));
            views.add(t);
        }
        final TextView close = new TextView(activity);
        close.setText("×");
        close.setTextColor(TEXT_DIM);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        open.addView(close, new LinearLayout.LayoutParams((int) (32 * dp), (int) (40 * dp)));

        final ExitTaps exitTaps = new ExitTaps();
        final Runnable paint = () -> {
            for (int i = 0; i < views.size(); i++) {
                if (buttons.get(i).exitSai) {
                    boolean asking = exitTaps.asking(SystemClock.uptimeMillis());
                    views.get(i).setBackground(rounded(asking ? lit : Color.TRANSPARENT, 9 * dp));
                    continue;
                }
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
        final Runnable unaskExit = () -> {
            exitTaps.reset();
            for (int i = 0; i < views.size(); i++) {
                if (buttons.get(i).exitSai) {
                    views.get(i).setText(exitLabel);
                    views.get(i).setBackground(rounded(Color.TRANSPARENT, 9 * dp));
                }
            }
        };
        final Runnable closeButtons = () -> {
            unaskExit.run();
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
        /* the red Exit SAI of its own, if switched on */
        final float exitScale = ExitButtonSize.scale(prefs.getInt(PREF_EXIT_SIZE, ExitButtonSize.BY_DEFAULT));
        final TextView exitOwn = ownExit ? exitButton(activity, dp, exitScale, exitLabel) : null;
        final Runnable keepExitInside = () -> {
            if (exitOwn != null) keepInside(root, exitOwn);
        };
        final boolean[] shown = {onAtStart};
        final Runnable hide = () -> {
            shown[0] = false;
            held.releaseAll();
            if (isOpen[0]) closeButtons.run();
            panel.setVisibility(View.GONE);
            if (exitOwn != null) exitOwn.setVisibility(View.GONE);
        };
        final Runnable unhide = () -> {
            shown[0] = true;
            if (hasKeys) {
                panel.setVisibility(View.VISIBLE);
                panel.post(keepInside);
            }
            if (exitOwn != null) {
                exitOwn.setVisibility(View.VISIBLE);
                exitOwn.post(keepExitInside);
            }
        };
        final Runnable flip = () -> {
            if (shown[0]) hide.run();
            else unhide.run();
        };
        toggle = flip;
        /* Exit SAI's second tap, in the row or on its own: no key left down
         * under SAI's own Save dialog, the buttons out of its way */
        final Runnable closeSai = () -> {
            held.releaseAll();
            if (isOpen[0]) closeButtons.run();
            if (activity instanceof XServerDisplayActivity)
                SaiPenRequests.closeSai(((XServerDisplayActivity) activity).getWinHandler());
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
            if (buttons.get(i).exitSai) {
                Runnable tap = exitTap(ui, exitTaps, t, exitLabel, exitAsk,
                    rounded(Color.TRANSPARENT, 9 * dp), rounded(lit, 9 * dp), closeSai);
                t.setOnClickListener(v -> tap.run());
            } else if (buttons.get(i).held) {
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
                flip.run();
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
        if (!onAtStart || !hasKeys) panel.setVisibility(View.GONE);
        panel.post(() -> {
            panel.setX(prefs.getFloat(PREF_X, DEFAULT_X) * Math.max(0, root.getWidth() - panel.getWidth()));
            panel.setY(prefs.getFloat(PREF_Y, DEFAULT_Y) * Math.max(0, root.getHeight() - panel.getHeight()));
            keepInside.run();
        });

        if (exitOwn != null) {
            /* its own two taps, its own place; a drag moves it, a long press does nothing */
            final ExitTaps ownTaps = new ExitTaps();
            /* idle: SAI's cross, no words; asking: "Exit?" */
            android.graphics.drawable.Drawable idle = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {rounded(EXIT_RED, 8 * dp * exitScale), new Cross(2.2f * dp * exitScale)});
            exitOwn.setBackground(idle);
            Runnable tap = exitTap(ui, ownTaps, exitOwn, "", exitAsk,
                idle, rounded(EXIT_RED_ASKING, 8 * dp * exitScale), closeSai);
            exitOwn.setOnTouchListener(dragger(activity, root, exitOwn, prefs, PREF_EXIT_X, PREF_EXIT_Y,
                keepExitInside, tap, null));
            root.addView(exitOwn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            if (!onAtStart) exitOwn.setVisibility(View.GONE);
            exitOwn.post(() -> {
                exitOwn.setX(prefs.getFloat(PREF_EXIT_X, DEFAULT_EXIT_X) * Math.max(0, root.getWidth() - exitOwn.getWidth()));
                exitOwn.setY(prefs.getFloat(PREF_EXIT_Y, DEFAULT_EXIT_Y) * Math.max(0, root.getHeight() - exitOwn.getHeight()));
                keepExitInside.run();
            });
        }
    }

    /**
     * What a tap on an Exit SAI button does (ExitTaps): the first lights it
     * and asks `ask`, going back to `label` if no second tap comes; the second
     * runs `close`.
     */
    private static Runnable exitTap(Handler ui, ExitTaps taps, TextView t, String label, String ask,
                                    android.graphics.drawable.Drawable idle,
                                    android.graphics.drawable.Drawable asking, Runnable close) {
        return () -> {
            if (taps.tap(SystemClock.uptimeMillis())) {
                t.setText(label);
                t.setBackground(idle);
                close.run();
                return;
            }
            t.setText(ask);
            t.setBackground(asking);
            ui.postDelayed(() -> {
                if (!taps.asking(SystemClock.uptimeMillis())) {
                    t.setText(label);
                    t.setBackground(idle);
                }
            }, ExitTaps.CONFIRM_MS + 50);
        };
    }

    /** The red Exit SAI of its own: a rounded square like SAI's close
     *  button, a white cross on it (its background, set by the caller) and no
     *  words until it asks "Exit?". `scale`: ExitButtonSize, 1 for medium. */
    private static TextView exitButton(Activity activity, float dp, float scale, String label) {
        TextView t = new TextView(activity);
        t.setText("");
        t.setContentDescription(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * scale);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        /* 42 dp: a quarter smaller than the first 56 (owner, 2026-11-26); no
           shadow, like the keys' panel, so the see-through red stays clean */
        int side = (int) (42 * dp * scale);
        t.setWidth(side);
        t.setHeight(side);
        return t;
    }

    /** A white cross with round ends, a third of the way in from each side. */
    private static final class Cross extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Cross(float stroke) {
            p.setColor(Color.WHITE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float in = Math.min(b.width(), b.height()) / 3f;
            float l = b.left + in, t = b.top + in, r = b.right - in, bo = b.bottom - in;
            c.drawLine(l, t, r, bo, p);
            c.drawLine(r, t, l, bo, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Keeps `v` inside `root`. */
    private static void keepInside(FrameLayout root, View v) {
        float maxX = Math.max(0, root.getWidth() - v.getWidth());
        float maxY = Math.max(0, root.getHeight() - v.getHeight());
        v.setX(Math.max(0, Math.min(v.getX(), maxX)));
        v.setY(Math.max(0, Math.min(v.getY(), maxY)));
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
        return dragger(activity, root, panel, prefs, PREF_X, PREF_Y, keepInside, onTap, onLongPress);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static View.OnTouchListener dragger(Activity activity, FrameLayout root, View panel,
                                                SharedPreferences prefs, String prefX, String prefY,
                                                Runnable keepInside,
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
                        if (dragging) savePosition(prefs, prefX, prefY, root, panel);
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
    private static void savePosition(SharedPreferences prefs, String prefX, String prefY,
                                     FrameLayout root, View panel) {
        float roomX = Math.max(1, root.getWidth() - panel.getWidth());
        float roomY = Math.max(1, root.getHeight() - panel.getHeight());
        prefs.edit()
            .putFloat(prefX, Math.max(0f, Math.min(1f, panel.getX() / roomX)))
            .putFloat(prefY, Math.max(0f, Math.min(1f, panel.getY() / roomY)))
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
