package com.winlator.saipen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.winlator.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The "On-screen keys" part of SAI Pen's settings: what FloatingKeys shows
 * over SAI. Whether it shows at the start, the three-finger hold, the
 * buttons (up to Shortcut.MAX, each pressed once or held) and the colour.
 * Built in code into an empty container of the launcher's layout.
 */
public final class FloatingKeysSettings {
    private FloatingKeysSettings() {}

    /** The short line next to the row's title: "On" / "Off". */
    public static String summary(Activity a, SharedPreferences prefs) {
        boolean on = prefs.getBoolean(FloatingKeys.PREF_ON, FloatingKeys.ON_BY_DEFAULT)
            || prefs.getBoolean(FloatingKeys.PREF_GESTURE, FloatingKeys.GESTURE_BY_DEFAULT);
        return a.getString(on ? R.string.saipen_keys_value_on : R.string.saipen_keys_value_off);
    }

    /** Fills `body`; `changed` runs after every change (for the summary). */
    public static void build(Activity a, LinearLayout body, SharedPreferences prefs, int dialogTheme,
                             Runnable changed) {
        final float dp = a.getResources().getDisplayMetrics().density;
        final int textColor = attrColor(a, R.attr.colorPrimaryText, Color.WHITE);
        final int dimColor = attrColor(a, R.attr.colorSecondaryText, Color.GRAY);
        body.removeAllViews();

        body.addView(note(a, R.string.saipen_keys_note));
        body.addView(toggle(a, textColor, R.string.saipen_keys_switch, prefs,
            FloatingKeys.PREF_ON, FloatingKeys.ON_BY_DEFAULT, changed));
        body.addView(toggle(a, textColor, R.string.saipen_keys_gesture, prefs,
            FloatingKeys.PREF_GESTURE, FloatingKeys.GESTURE_BY_DEFAULT, changed));

        /* the buttons */
        body.addView(note(a, a.getString(R.string.saipen_keys_buttons_note, Shortcut.MAX)));
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final TextView add = link(a, dp, R.string.saipen_keys_add);
        TextView reset = link(a, dp, R.string.saipen_keys_reset);
        actions.addView(add);
        actions.addView(reset);
        body.addView(actions);
        /* Exit SAI: a button of its own kind, so a link of its own */
        final TextView addExit = link(a, dp, R.string.saipen_keys_add_exit);
        body.addView(addExit);
        Switch exitOwn = toggle(a, textColor, R.string.saipen_keys_exit_own, prefs,
            FloatingKeys.PREF_EXIT_OWN, FloatingKeys.EXIT_OWN_BY_DEFAULT, changed);
        body.addView(exitOwn);
        /* its size, shown while it is on */
        final RadioGroup exitSize = new RadioGroup(a);
        exitSize.setOrientation(RadioGroup.HORIZONTAL);
        int[] sizeText = {R.string.saipen_keys_exit_small, R.string.saipen_keys_exit_medium,
            R.string.saipen_keys_exit_large};
        int chosenSize = ExitButtonSize.of(prefs.getInt(FloatingKeys.PREF_EXIT_SIZE, ExitButtonSize.BY_DEFAULT));
        for (int i = 0; i < sizeText.length; i++) {
            final int size = i;
            RadioButton r = new RadioButton(a);
            r.setText(sizeText[i]);
            r.setTextColor(textColor);
            r.setId(View.generateViewId());
            r.setMinHeight((int) (40 * dp));
            r.setPadding(r.getPaddingLeft(), 0, (int) (16 * dp), 0);
            exitSize.addView(r);
            if (i == chosenSize) exitSize.check(r.getId());
            r.setOnCheckedChangeListener((b, on) -> {
                if (!on) return;
                prefs.edit().putInt(FloatingKeys.PREF_EXIT_SIZE, size).apply();
                changed.run();
            });
        }
        body.addView(exitSize);
        /* darker, towards black, and its own opacity or the keys' (ExitLook) */
        final LinearLayout exitLook = new LinearLayout(a);
        exitLook.setOrientation(LinearLayout.VERTICAL);
        exitLook.addView(note(a, R.string.saipen_keys_exit_dark));
        /* the slider, and at its end the red as it will be: shown, not told */
        LinearLayout darkRow = new LinearLayout(a);
        darkRow.setOrientation(LinearLayout.HORIZONTAL);
        darkRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.SeekBar dark = new android.widget.SeekBar(a);
        dark.setMax(ExitLook.MAX_DARK);
        dark.setProgress(ExitLook.dark(prefs.getInt(ExitLook.PREF_DARK, 0)));
        final View darkSwatch = new View(a);
        final GradientDrawable darkPaint = new GradientDrawable();
        darkPaint.setCornerRadius(8 * dp);
        darkPaint.setStroke(Math.max(1, (int) dp), dimColor);
        darkPaint.setColor(ExitLook.color(FloatingKeys.EXIT_RED, dark.getProgress(), 100));
        darkSwatch.setBackground(darkPaint);
        dark.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {
                darkPaint.setColor(ExitLook.color(FloatingKeys.EXIT_RED, v, 100));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                prefs.edit().putInt(ExitLook.PREF_DARK, s.getProgress()).apply();
                changed.run();
            }
        });
        darkRow.addView(dark, new LinearLayout.LayoutParams(0, (int) (40 * dp), 1));
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams((int) (48 * dp), (int) (28 * dp));
        swatchLp.setMarginStart((int) (8 * dp));
        darkRow.addView(darkSwatch, swatchLp);
        exitLook.addView(darkRow);
        int savedOwn = prefs.getInt(ExitLook.PREF_OPACITY, ExitLook.SAME_AS_KEYS);
        final android.widget.SeekBar own = new android.widget.SeekBar(a);
        own.setMax(100 - ExitLook.MIN_OPACITY);
        own.setProgress(ExitLook.opacity(savedOwn, KeysOpacity.BY_DEFAULT) - ExitLook.MIN_OPACITY);
        own.setOnSeekBarChangeListener(saveOnRelease(v -> {
            prefs.edit().putInt(ExitLook.PREF_OPACITY, v + ExitLook.MIN_OPACITY).apply();
            changed.run();
        }));
        Switch same = new Switch(a);
        same.setText(R.string.saipen_keys_exit_same_opacity);
        same.setTextColor(textColor);
        same.setMinHeight((int) (44 * dp));
        same.setChecked(savedOwn == ExitLook.SAME_AS_KEYS);
        own.setVisibility(same.isChecked() ? View.GONE : View.VISIBLE);
        same.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            prefs.edit().putInt(ExitLook.PREF_OPACITY,
                on ? ExitLook.SAME_AS_KEYS : own.getProgress() + ExitLook.MIN_OPACITY).apply();
            own.setVisibility(on ? View.GONE : View.VISIBLE);
            changed.run();
        });
        exitLook.addView(same);
        exitLook.addView(own, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * dp)));
        body.addView(exitLook);
        exitSize.setVisibility(exitOwn.isChecked() ? View.VISIBLE : View.GONE);
        exitLook.setVisibility(exitOwn.isChecked() ? View.VISIBLE : View.GONE);
        exitOwn.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            prefs.edit().putBoolean(FloatingKeys.PREF_EXIT_OWN, on).apply();
            exitSize.setVisibility(on ? View.VISIBLE : View.GONE);
            exitLook.setVisibility(on ? View.VISIBLE : View.GONE);
            changed.run();
        });

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            List<Shortcut> now = FloatingKeys.buttons(prefs);
            for (int i = 0; i < now.size(); i++) {
                final int index = i;
                final Shortcut s = now.get(i);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight((int) (40 * dp));
                TextView name = new TextView(a);
                name.setText(s.exitSai ? a.getString(R.string.saipen_keys_exit) : s.label());
                name.setTextColor(textColor);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                /* ↑ ↓: the order on the panel, top to bottom */
                for (final int step : new int[]{-1, 1}) {
                    TextView move = link(a, dp, step < 0 ? R.string.saipen_keys_up : R.string.saipen_keys_down);
                    move.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    move.setPadding((int) (10 * dp), 0, (int) (10 * dp), 0);
                    move.setContentDescription(a.getString(step < 0 ? R.string.saipen_keys_up_long
                        : R.string.saipen_keys_down_long));
                    final int to = index + step;
                    if (to < 0 || to >= now.size()) {
                        move.setEnabled(false);
                        move.setAlpha(0.25f);
                    } else move.setOnClickListener(v -> {
                        List<Shortcut> l = FloatingKeys.buttons(prefs);
                        if (index < l.size() && to < l.size()) java.util.Collections.swap(l, index, to);
                        save(prefs, l);
                        fill[0].run();
                        changed.run();
                    });
                    row.addView(move);
                }
                /* once / held: a tap switches, unless it can only be held */
                TextView kind = link(a, dp, s.held ? R.string.saipen_keys_kind_held : R.string.saipen_keys_kind_once);
                if (s.exitSai) kind.setVisibility(View.INVISIBLE);   /* two taps, neither once nor held */
                else if (s.key == null) kind.setTextColor(dimColor);
                else kind.setOnClickListener(v -> {
                    List<Shortcut> l = FloatingKeys.buttons(prefs);
                    if (index < l.size()) l.set(index, new Shortcut(s.modifiers, s.key, !s.held));
                    save(prefs, l);
                    fill[0].run();
                    changed.run();
                });
                row.addView(kind);
                TextView remove = link(a, dp, R.string.saipen_keys_remove);
                remove.setOnClickListener(v -> {
                    List<Shortcut> l = FloatingKeys.buttons(prefs);
                    if (index < l.size()) l.remove(index);
                    save(prefs, l);
                    fill[0].run();
                    changed.run();
                });
                row.addView(remove);
                list.addView(row);
            }
            if (now.isEmpty()) list.addView(note(a, R.string.saipen_keys_none));
            boolean room = now.size() < Shortcut.MAX;
            add.setEnabled(room);
            add.setAlpha(room ? 1f : 0.4f);
            boolean hasExit = false;
            for (Shortcut s : now) hasExit |= s.exitSai;
            addExit.setVisibility(hasExit ? View.GONE : View.VISIBLE);
            addExit.setEnabled(room);
            addExit.setAlpha(room ? 1f : 0.4f);
        };
        fill[0].run();

        add.setOnClickListener(v -> askKeys(a, dialogTheme, R.string.saipen_keys_add_title, R.string.saipen_keys_add_ok,
                R.string.saipen_keys_kind_once_long, R.string.saipen_keys_kind_held_long, "T", s -> {
            List<Shortcut> l = FloatingKeys.buttons(prefs);
            boolean repeat = false;
            for (Shortcut o : l) repeat |= o.sameKeys(s);
            if (!repeat && l.size() < Shortcut.MAX) l.add(s);
            save(prefs, l);
            fill[0].run();
            changed.run();
        }));
        addExit.setOnClickListener(v -> {
            List<Shortcut> l = FloatingKeys.buttons(prefs);
            boolean repeat = false;
            for (Shortcut o : l) repeat |= o.exitSai;
            if (!repeat && l.size() < Shortcut.MAX) l.add(Shortcut.exitSai());
            save(prefs, l);
            fill[0].run();
            changed.run();
        });
        reset.setOnClickListener(v -> {
            prefs.edit().remove(FloatingKeys.PREF_BUTTONS).apply();
            fill[0].run();
            changed.run();
        });

        /* the colour */
        body.addView(note(a, R.string.saipen_keys_color_note));
        final LinearLayout swatches = new LinearLayout(a);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(swatches);
        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            int chosen = FloatingKeys.colorIndex(prefs);
            for (int i = 0; i < swatches.getChildCount(); i++) {
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(FloatingKeys.COLORS_LIT[i]);
                if (i == chosen) d.setStroke((int) (3 * dp), textColor);
                swatches.getChildAt(i).setBackground(d);
            }
        };
        for (int i = 0; i < FloatingKeys.COLORS_LIT.length; i++) {
            final int index = i;
            View s = new View(a);
            s.setOnClickListener(v -> {
                prefs.edit().putInt(FloatingKeys.PREF_COLOR, index).apply();
                paint[0].run();
                changed.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp));
            lp.setMargins(0, (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            swatches.addView(s, lp);
        }
        paint[0].run();

        /* how solid: the panel and the red Exit SAI together */
        body.addView(note(a, R.string.saipen_keys_opacity_note));
        body.addView(percentSlider(a, dp, textColor, KeysOpacity.MIN,
            KeysOpacity.of(prefs.getInt(KeysOpacity.PREF, KeysOpacity.BY_DEFAULT)), v -> {
                prefs.edit().putInt(KeysOpacity.PREF, v).apply();
                changed.run();
            }));
    }

    interface OnButton { void picked(Shortcut s); }

    /**
     * A slider from `min` to 100 percent with the percent beside it, which
     * follows the finger; `save` gets the percent once the finger lets go.
     */
    private static LinearLayout percentSlider(Activity a, float dp, int textColor, int min, int now,
                                              java.util.function.IntConsumer save) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.SeekBar bar = new android.widget.SeekBar(a);
        bar.setMax(100 - min);
        bar.setProgress(now - min);
        final TextView shown = new TextView(a);
        shown.setTextColor(textColor);
        shown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        shown.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        shown.setText(now + "%");
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {
                shown.setText((v + min) + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { save.accept(s.getProgress() + min); }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, (int) (40 * dp), 1));
        row.addView(shown, new LinearLayout.LayoutParams((int) (56 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** A slider that saves once the finger lets go, not on every step. */
    private static android.widget.SeekBar.OnSeekBarChangeListener saveOnRelease(java.util.function.IntConsumer save) {
        return new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {}
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { save.accept(s.getProgress()); }
        };
    }

    /**
     * Ctrl, Alt, Shift, a key (or none) and once / held: a new button, or a
     * gesture's key (GesturesSettings). onceText, heldText: the two choices'
     * texts, or 0 for none (always once, and a key is needed then).
     */
    static void askKeys(Activity activity, int dialogTheme, int title, int ok, int onceText, int heldText,
                        String firstKey, OnButton picked) {
        /* the dialog is light whatever the screen's theme: its views made
         * with the dark screen's context had white text on the white dialog
         * (0.4.28, 0.4.29). Made in the dialog's own theme, they match it. */
        android.content.Context a = new android.view.ContextThemeWrapper(activity, dialogTheme);
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, (int) (8 * dp), pad, 0);

        LinearLayout mods = new LinearLayout(a);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox ctrl = new CheckBox(a), alt = new CheckBox(a), shift = new CheckBox(a);
        ctrl.setText("Ctrl");
        alt.setText("Alt");
        shift.setText("Shift");
        ctrl.setChecked(true);
        for (CheckBox c : new CheckBox[]{ctrl, alt, shift}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (12 * dp));
            mods.addView(c, lp);
        }
        box.addView(mods);

        final String none = a.getString(R.string.saipen_keys_no_key);
        List<String> choices = new ArrayList<>();
        choices.add(none);
        choices.addAll(Shortcut.KEYS);
        Spinner key = new Spinner(a);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        key.setAdapter(adapter);
        key.setSelection(Math.max(0, choices.indexOf(firstKey)));
        box.addView(key);

        final boolean canHold = onceText != 0 && heldText != 0;
        RadioGroup kind = new RadioGroup(a);
        RadioButton once = new RadioButton(a), held = new RadioButton(a);
        if (canHold) {
            once.setText(onceText);
            held.setText(heldText);
            once.setId(View.generateViewId());
            held.setId(View.generateViewId());
            kind.addView(once);
            kind.addView(held);
            kind.check(once.getId());
            box.addView(kind);
        }

        new AlertDialog.Builder(activity, dialogTheme)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(ok, (d, w) -> {
                int m = (ctrl.isChecked() ? Shortcut.CTRL : 0) | (alt.isChecked() ? Shortcut.ALT : 0)
                    | (shift.isChecked() ? Shortcut.SHIFT : 0);
                String k = (String) key.getSelectedItem();
                if (none.equals(k)) k = null;
                if (k == null && m == 0) return;   /* nothing to press */
                /* a modifier alone is always held (Shortcut): with no
                 * held choice offered, a key is needed */
                if (k == null && !canHold) return;
                picked.picked(new Shortcut(m, k, canHold && held.isChecked()));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void save(SharedPreferences prefs, List<Shortcut> l) {
        prefs.edit().putString(FloatingKeys.PREF_BUTTONS, Shortcut.joinList(l)).apply();
    }

    private static Switch toggle(Activity a, int textColor, int text, SharedPreferences prefs, String pref,
                                 boolean byDefault, Runnable changed) {
        Switch s = new Switch(a);
        s.setText(text);
        s.setTextColor(textColor);
        s.setMinHeight((int) (44 * a.getResources().getDisplayMetrics().density));
        s.setChecked(prefs.getBoolean(pref, byDefault));
        s.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            prefs.edit().putBoolean(pref, on).apply();
            changed.run();
        });
        return s;
    }

    private static TextView note(Activity a, int text) {
        return note(a, a.getString(text));
    }

    private static TextView note(Activity a, String text) {
        TextView t = new TextView(a, null, 0, R.style.SaiPenNote);
        t.setText(text);
        return t;
    }

    private static TextView link(Activity a, float dp, int text) {
        TextView t = new TextView(a, null, 0, R.style.SaiPenNote);
        t.setText(text);
        t.setTextColor(FloatingKeys.COLORS_LIT[0]);
        t.setMinHeight((int) (40 * dp));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding((int) (4 * dp), 0, (int) (16 * dp), 0);
        return t;
    }

    private static int attrColor(Activity a, int attr, int fallback) {
        TypedValue v = new TypedValue();
        if (!a.getTheme().resolveAttribute(attr, v, true)) return fallback;
        if (v.resourceId != 0) return a.getResources().getColor(v.resourceId, a.getTheme());
        return v.data;
    }
}
