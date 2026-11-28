package com.winlator.saipen;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.R;

/**
 * The cheat sheet (CheatSheet): one page to read, in the dark style of the
 * gestures' settings. Every gesture as it is set now, where drawings go,
 * what is good to know, and how to keep SAI from lagging.
 */
public final class CheatSheetDialog {
    private CheatSheetDialog() {}

    /** Shows the sheet; it counts as seen once it is closed. */
    public static void show(Activity a, SharedPreferences prefs) {
        new Build(a, prefs).show();
    }

    private static final class Build {
        final Activity a;
        final SharedPreferences prefs;
        final float dp;
        final int textColor, dimColor, accent;   /* as on SAI Pen's screen */
        LinearLayout body;

        Build(Activity a, SharedPreferences prefs) {
            this.a = a;
            this.prefs = prefs;
            dp = a.getResources().getDisplayMetrics().density;
            textColor = attrColor(a, R.attr.colorPrimaryText, Color.WHITE);
            /* the screen's labels (SaiPenLabel) */
            dimColor = attrColor(a, R.attr.colorSecondaryText,
                Color.argb(0xB3, Color.red(textColor), Color.green(textColor), Color.blue(textColor)));
            /* the blue of the GitHub link at the bottom of SAI Pen's screen:
             * the theme's link colour (saipen_credits sets none of its own) */
            accent = attrColor(a, android.R.attr.textColorLink, Color.rgb(0x02, 0x88, 0xD1));
        }

        void show() {
            Dialog d = new Dialog(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * dp);
            box.setPadding(pad, (int) (16 * dp), pad, (int) (4 * dp));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(attrColor(a, R.attr.colorPrimarySurface, Color.rgb(32, 33, 36)));
            bg.setCornerRadius(16 * dp);
            box.setBackground(bg);

            TextView title = text(R.string.saipen_cheat_title, 20, textColor);
            box.addView(title);

            ScrollView scroll = new ScrollView(a);
            body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body);
            box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            fill();

            TextView done = text(R.string.saipen_cheat_done, 16, accent);
            done.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            done.setMinHeight((int) (48 * dp));
            done.setOnClickListener(v -> d.dismiss());
            box.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            d.setContentView(box);
            d.setOnDismissListener(x -> prefs.edit().putBoolean(CheatSheet.PREF_CHEAT_SHEET_SEEN, true).apply());
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(android.R.color.transparent);
                int screenW = a.getResources().getDisplayMetrics().widthPixels;
                int screenH = a.getResources().getDisplayMetrics().heightPixels;
                w.setLayout(Math.min((int) (600 * dp), (int) (screenW * 0.94f)), (int) (screenH * 0.88f));
            }
            d.show();
        }

        void fill() {
            TextView fan = text(R.string.saipen_cheat_fan, 13, dimColor);
            fan.setPadding(0, (int) (6 * dp), 0, 0);
            body.addView(fan);

            heading(R.string.saipen_cheat_gestures);
            Gestures.Action[] actions = Gestures.actions(prefs.getAll());
            for (CheatSheet.Row r : CheatSheet.rows(actions)) {
                if (r.fixed != null) row(fixedName(r.fixed), fixedDoes(r.fixed));
                else row(a.getString(GesturesSettings.inputName(r.input)),
                         GesturesSettings.label(a, actions[r.input.ordinal()]));
            }
            note(a.getString(R.string.saipen_cheat_gestures_note));

            heading(R.string.saipen_cheat_files);
            bullet(R.string.saipen_cheat_files_f);
            bullet(R.string.saipen_cheat_files_g);
            bullet(R.string.saipen_cheat_files_e);
            bullet(R.string.saipen_cheat_files_c);
            note(a.getString(R.string.saipen_cheat_files_auto));
            bullet(R.string.saipen_cheat_files_copy);
            bullet(R.string.saipen_cheat_files_gallery);
            bullet(R.string.saipen_cheat_files_timelapse);

            heading(R.string.saipen_cheat_good);
            TextView licence = bullet(R.string.saipen_cheat_licence);
            licence.setText(Html.fromHtml(a.getString(R.string.saipen_cheat_licence), Html.FROM_HTML_MODE_LEGACY));
            licence.setMovementMethod(LinkMovementMethod.getInstance());
            licence.setLinkTextColor(accent);
            bullet(R.string.saipen_cheat_menu_closes);
            bullet(R.string.saipen_cheat_exit);
            bullet(R.string.saipen_cheat_scaling);

            heading(R.string.saipen_cheat_speed);
            int gb = CheatSheet.lowRamGb(totalMemory(a));
            if (gb > 0) {
                TextView ram = text(0, 15, textColor);
                ram.setText(a.getString(R.string.saipen_cheat_speed_ram, gb));
                ram.setPadding(0, 0, 0, (int) (6 * dp));
                body.addView(ram);
            }
            note(a.getString(R.string.saipen_cheat_speed_why));
            bullet(R.string.saipen_cheat_speed_apps);
            bullet(R.string.saipen_cheat_speed_killed);
            bullet(R.string.saipen_cheat_speed_slow);
        }

        String fixedName(CheatSheet.Fixed f) {
            switch (f) {
                case PEN: return a.getString(R.string.saipen_cheat_pen);
                case ONE_FINGER: return a.getString(R.string.saipen_cheat_one);
                case TWO_FINGERS: return a.getString(R.string.saipen_cheat_two);
                case FOUR_TAP: return a.getString(R.string.saipen_cheat_four_tap);
                default: return a.getString(R.string.saipen_cheat_four_hold);
            }
        }

        String fixedDoes(CheatSheet.Fixed f) {
            switch (f) {
                case PEN: return a.getString(R.string.saipen_cheat_pen_does);
                case ONE_FINGER: return a.getString(R.string.saipen_cheat_one_does);
                case TWO_FINGERS: return a.getString(R.string.saipen_cheat_two_does);
                case FOUR_TAP: return a.getString(R.string.saipen_cheat_four_tap_does);
                default: return a.getString(R.string.saipen_cheat_four_hold_does);
            }
        }

        void heading(int res) {
            /* like the screen's section labels (SaiPenLabel) */
            TextView h = text(res, 12, dimColor);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setAllCaps(true);
            h.setLetterSpacing(0.08f);
            h.setPadding(0, (int) (20 * dp), 0, (int) (6 * dp));
            body.addView(h);
        }

        /** A gesture and what it does, side by side. */
        void row(String gesture, String does) {
            LinearLayout l = new LinearLayout(a);
            l.setPadding(0, (int) (5 * dp), 0, (int) (5 * dp));
            TextView g = text(0, 15, textColor);
            g.setText(gesture);
            g.setPadding(0, 0, (int) (12 * dp), 0);
            TextView d = text(0, 15, dimColor);
            d.setText(does);
            l.addView(g, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            l.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
            body.addView(l);
        }

        TextView bullet(int res) {
            LinearLayout l = new LinearLayout(a);
            l.setPadding(0, (int) (3 * dp), 0, (int) (3 * dp));
            TextView dot = text(0, 15, dimColor);
            dot.setText("•");
            dot.setPadding(0, 0, (int) (10 * dp), 0);
            TextView t = text(res, 15, textColor);
            t.setLineSpacing(0, 1.1f);
            l.addView(dot);
            l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            body.addView(l);
            return t;
        }

        void note(String s) {
            TextView t = text(0, 14, dimColor);
            t.setText(s);
            t.setLineSpacing(0, 1.1f);
            t.setPadding(0, (int) (6 * dp), 0, (int) (4 * dp));
            body.addView(t);
        }

        TextView text(int res, float sp, int color) {
            TextView t = new TextView(a);
            if (res != 0) t.setText(res);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            return t;
        }
    }

    static long totalMemory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }

    private static int attrColor(Activity a, int attr, int fallback) {
        TypedValue v = new TypedValue();
        if (!a.getTheme().resolveAttribute(attr, v, true)) return fallback;
        if (v.resourceId != 0) return a.getResources().getColor(v.resourceId, a.getTheme());
        return v.data;
    }
}
