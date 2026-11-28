package com.winlator.saipen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.R;

import java.util.List;

/**
 * The "Gestures" part of SAI Pen's settings (issue #10): for each finger
 * and pen gesture (Gestures.Input), what it does. A row per gesture with
 * its action; a tap on the row offers the choices, "A key…" the key picker
 * of the on-screen keys. Built in code into an empty container of the
 * launcher's layout. Read again each time SAI starts (GestureActions).
 */
public final class GesturesSettings {
    private GesturesSettings() {}

    /** The short line next to the row's title: "Standard" / "2 changed". */
    public static String summary(Activity a, SharedPreferences prefs) {
        int n = Gestures.changed(Gestures.actions(prefs.getAll()));
        return n == 0 ? a.getString(R.string.saipen_gestures_value_standard)
            : a.getResources().getQuantityString(R.plurals.saipen_gestures_value_changed, n, n);
    }

    /** Fills `body`; `changed` runs after every change (for the summary). */
    public static void build(Activity a, LinearLayout body, SharedPreferences prefs, int dialogTheme,
                             Runnable changed) {
        final float dp = a.getResources().getDisplayMetrics().density;
        final int textColor = attrColor(a, R.attr.colorPrimaryText, Color.WHITE);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            body.removeAllViews();
            body.addView(note(a, a.getString(R.string.saipen_gestures_note)));
            body.addView(note(a, a.getString(R.string.saipen_gestures_hold_note)));
            body.addView(heading(a, textColor, R.string.saipen_gestures_fingers));
            for (Gestures.Input in : Gestures.fingerInputs()) {
                body.addView(row(a, dp, textColor, prefs, dialogTheme, in, () -> {
                    fill[0].run();
                    changed.run();
                }));
            }
            body.addView(note(a, a.getString(R.string.saipen_gestures_edge_note)));
            body.addView(heading(a, textColor, R.string.saipen_gestures_pen));
            for (Gestures.Input in : Gestures.penInputs()) {
                body.addView(row(a, dp, textColor, prefs, dialogTheme, in, () -> {
                    fill[0].run();
                    changed.run();
                }));
            }
            body.addView(note(a, a.getString(R.string.saipen_gestures_air_note)));
            /* a Bluetooth S Pen's button away from the screen: Samsung's Air
             * actions, set in Samsung's settings (AirActionKeys) */
            if ("samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER))
                body.addView(note(a, a.getString(R.string.saipen_gestures_bluetooth_note)));
            TextView reset = link(a, dp, a.getString(R.string.saipen_gestures_reset));
            reset.setOnClickListener(v -> {
                SharedPreferences.Editor e = prefs.edit();
                for (Gestures.Input in : Gestures.Input.values()) e.remove(in.pref());
                e.remove(Gestures.OLD_KEYS_HOLD_PREF);
                e.apply();
                fill[0].run();
                changed.run();
            });
            body.addView(reset);
        };
        fill[0].run();
    }

    /** One gesture: its name, and what it does now in the accent colour. */
    private static LinearLayout row(Activity a, float dp, int textColor, SharedPreferences prefs, int dialogTheme,
                                    Gestures.Input in, Runnable changed) {
        Gestures.Action now = Gestures.actions(prefs.getAll())[in.ordinal()];
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight((int) (48 * dp));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        TextView name = new TextView(a);
        name.setText(inputName(in));
        name.setTextColor(textColor);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(name);
        TextView value = new TextView(a);
        value.setText(label(a, now));
        value.setTextColor(FloatingKeys.COLORS_LIT[0]);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(value);
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        row.setOnClickListener(v -> pick(a, prefs, dialogTheme, in, changed));
        return row;
    }

    /** The choices for one gesture; "A key…" goes on to the key picker. */
    private static void pick(Activity a, SharedPreferences prefs, int dialogTheme, Gestures.Input in,
                             Runnable changed) {
        final List<Gestures.Type> types = Gestures.choices(in);
        Gestures.Action now = Gestures.actions(prefs.getAll())[in.ordinal()];
        String[] labels = new String[types.size()];
        int checked = -1;
        for (int i = 0; i < types.size(); i++) {
            Gestures.Type t = types.get(i);
            if (t == Gestures.Type.KEY) {
                labels[i] = now.type == Gestures.Type.KEY
                    ? a.getString(R.string.saipen_action_key) + "  " + label(a, now)
                    : a.getString(R.string.saipen_action_key);
            }
            else labels[i] = typeName(a, t);
            if (t == now.type) checked = i;
        }
        new AlertDialog.Builder(a, dialogTheme)
            .setTitle(inputName(in))
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                d.dismiss();
                Gestures.Type t = types.get(which);
                if (t != Gestures.Type.KEY) {
                    save(prefs, in, Gestures.Action.of(t));
                    changed.run();
                    return;
                }
                boolean hold = Gestures.canHold(in);
                FloatingKeysSettings.askKeys(a, dialogTheme, R.string.saipen_action_key_title,
                    R.string.saipen_action_key_ok,
                    hold ? R.string.saipen_action_kind_once_pen : 0,
                    hold ? R.string.saipen_action_kind_held_pen : 0,
                    "Z", s -> {
                        save(prefs, in, Gestures.Action.key(s));
                        changed.run();
                    });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void save(SharedPreferences prefs, Gestures.Input in, Gestures.Action action) {
        SharedPreferences.Editor e = prefs.edit();
        /* the hold's old switch (Gestures.actions) gives way to what is set here */
        if (in == Gestures.Input.THREE_HOLD) e.remove(Gestures.OLD_KEYS_HOLD_PREF);
        if (action.equals(in.byDefault)) e.remove(in.pref());
        else e.putString(in.pref(), action.toString());
        e.apply();
    }

    /** What an action reads as: "Right click (picks a colour)", "Ctrl+Z". */
    public static String label(Activity a, Gestures.Action action) {
        if (action.type != Gestures.Type.KEY) return typeName(a, action.type);
        return action.keys.held ? a.getString(R.string.saipen_action_key_held, action.keys.label())
            : action.keys.label();
    }

    private static String typeName(Activity a, Gestures.Type t) {
        switch (t) {
            case DRAW: return a.getString(R.string.saipen_action_draw);
            case LEFT_CLICK: return a.getString(R.string.saipen_action_left);
            case RIGHT_CLICK: return a.getString(R.string.saipen_action_right);
            case KEYBOARD: return a.getString(R.string.saipen_action_keyboard);
            case PAD_KEYBOARD: return a.getString(R.string.saipen_action_pad_keyboard);
            case ON_SCREEN_KEYS: return a.getString(R.string.saipen_action_keys);
            case MENU: return a.getString(R.string.saipen_action_menu);
            case BRING_BACK: return a.getString(R.string.saipen_action_bring_back);
            case KEY: return a.getString(R.string.saipen_action_key);
            case NONE:
            default: return a.getString(R.string.saipen_action_none);
        }
    }

    static int inputName(Gestures.Input in) {
        switch (in) {
            case TWO_TAP: return R.string.saipen_gesture_two_tap;
            case TWO_DOUBLE_TAP: return R.string.saipen_gesture_two_double_tap;
            case THREE_TAP: return R.string.saipen_gesture_three_tap;
            case THREE_DOUBLE_TAP: return R.string.saipen_gesture_three_double_tap;
            case THREE_HOLD: return R.string.saipen_gesture_three_hold;
            case LONG_PRESS: return R.string.saipen_gesture_long_press;
            case EDGE_LEFT: return R.string.saipen_gesture_edge_left;
            case EDGE_RIGHT: return R.string.saipen_gesture_edge_right;
            case PEN_BUTTON: return R.string.saipen_gesture_pen_button;
            case PEN_ERASER: return R.string.saipen_gesture_pen_eraser;
            case PEN_HOVER_BUTTON:
            default: return R.string.saipen_gesture_pen_hover_button;
        }
    }

    private static TextView heading(Activity a, int textColor, int text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(textColor);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAllCaps(true);
        t.setLetterSpacing(0.05f);
        float dp = a.getResources().getDisplayMetrics().density;
        t.setPadding(0, (int) (14 * dp), 0, (int) (2 * dp));
        return t;
    }

    private static TextView note(Activity a, String text) {
        TextView t = new TextView(a, null, 0, R.style.SaiPenNote);
        t.setText(text);
        return t;
    }

    private static TextView link(Activity a, float dp, String text) {
        TextView t = new TextView(a, null, 0, R.style.SaiPenNote);
        t.setText(text);
        t.setTextColor(FloatingKeys.COLORS_LIT[0]);
        t.setMinHeight((int) (40 * dp));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private static int attrColor(Activity a, int attr, int fallback) {
        TypedValue v = new TypedValue();
        if (!a.getTheme().resolveAttribute(attr, v, true)) return fallback;
        if (v.resourceId != 0) return a.getResources().getColor(v.resourceId, a.getTheme());
        return v.data;
    }
}
