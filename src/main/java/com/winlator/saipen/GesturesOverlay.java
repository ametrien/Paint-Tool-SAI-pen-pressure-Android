package com.winlator.saipen;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.R;

/**
 * The gestures' settings (GesturesSettings) over SAI, to try a gesture and
 * change it without leaving the drawing: four fingers held still for a
 * moment (FingersHold) open them. A four-finger TAP is Winlator's menu; a
 * hold is no tap, so the menu does not open as well. Changes are in use at
 * once (GestureActions listens to the preferences).
 *
 * Two tabs: Gestures, and the On-screen keys (FloatingKeysSettings), which
 * the ⚙ on the keys' panel opens straight away. The keys are built again
 * with each change (FloatingKeys.attach listens), so they change while the
 * window is open.
 *
 * The three-finger hold is watched here too: it does what it is set to
 * under Gestures (Gestures.Input.THREE_HOLD), the keys by default.
 */
public final class GesturesOverlay {
    public static final int FINGERS = 4;
    public static final int TAB_GESTURES = 0, TAB_KEYS = 1;

    private GesturesOverlay() {}

    public static void attach(Activity activity) {
        final float dp = activity.getResources().getDisplayMetrics().density;
        final Handler ui = new Handler(Looper.getMainLooper());
        final FingersHold hold = new FingersHold(24 * dp, FINGERS);
        final Dialog[] open = new Dialog[1];
        final Runnable check = () -> {
            if (!hold.due(SystemClock.uptimeMillis())) return;
            if (open[0] != null && open[0].isShowing()) return;
            activity.getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            open[0] = show(activity, TAB_GESTURES);
        };
        final FingersHold three = new FingersHold(24 * dp, FingersHold.FINGERS);
        final Runnable checkThree = () -> {
            if (!three.due(SystemClock.uptimeMillis())) return;
            GestureActions g = GestureActions.current();
            if (g == null || !g.isSet(Gestures.Input.THREE_HOLD)) return;
            activity.getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            g.run(Gestures.Input.THREE_HOLD, 0, 0, false, false);
        };
        FloatingKeys.watchTouches(activity, e -> {
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
            long now = SystemClock.uptimeMillis();
            long before = hold.when();
            hold.onTouch(count, xs, ys, now);
            long when = hold.when();
            if (when >= 0 && when != before) ui.postAtTime(check, when);
            long beforeThree = three.when();
            three.onTouch(count, xs, ys, now);
            long whenThree = three.when();
            if (whenThree >= 0 && whenThree != beforeThree) ui.postAtTime(checkThree, whenThree);
        });
    }

    /** The settings in a dark window over SAI, as on SAI Pen's screen,
     *  open at `tab` (TAB_GESTURES, TAB_KEYS). */
    static Dialog show(Activity activity, int tab) {
        final float dp = activity.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, (int) (16 * dp), pad, (int) (8 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(attrColor(activity, R.attr.colorPrimarySurface, Color.rgb(32, 33, 36)));
        bg.setCornerRadius(16 * dp);
        box.setBackground(bg);

        /* the tabs: the chosen one bright and underlined */
        final int textColor = attrColor(activity, R.attr.colorPrimaryText, Color.WHITE);
        final int dim = attrColor(activity, R.attr.colorSecondaryText, Color.GRAY);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] tabViews = {
            tab(activity, dp, R.string.saipen_gestures_title), tab(activity, dp, R.string.saipen_keys_title)};
        for (TextView t : tabViews) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (24 * dp));
            tabs.addView(t, lp);
        }
        box.addView(tabs);

        final ScrollView scroll = new ScrollView(activity);
        final LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        final Runnable[] select = new Runnable[1];
        final int[] chosen = {tab};
        select[0] = () -> {
            for (int i = 0; i < tabViews.length; i++) {
                boolean on = i == chosen[0];
                tabViews[i].setTextColor(on ? textColor : dim);
                GradientDrawable line = new GradientDrawable();
                line.setColor(on ? FloatingKeys.COLORS_LIT[0] : Color.TRANSPARENT);
                android.graphics.drawable.LayerDrawable under = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] {line});
                under.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
                under.setLayerHeight(0, (int) (2 * dp));
                tabViews[i].setBackground(under);
            }
            body.removeAllViews();
            if (chosen[0] == TAB_KEYS) {
                FloatingKeysSettings.build(activity, body, prefs, SaiLauncherActivity.DIALOG_THEME, () -> {});
            } else {
                GesturesSettings.build(activity, body, prefs, SaiLauncherActivity.DIALOG_THEME, () -> {});
            }
            scroll.scrollTo(0, 0);
        };
        for (int i = 0; i < tabViews.length; i++) {
            final int index = i;
            tabViews[i].setOnClickListener(v -> {
                chosen[0] = index;
                select[0].run();
            });
        }
        select[0].run();

        TextView done = new TextView(activity);
        done.setText(R.string.saipen_gestures_done);
        done.setTextColor(FloatingKeys.COLORS_LIT[0]);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        done.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        done.setMinHeight((int) (48 * dp));
        done.setOnClickListener(v -> d.dismiss());
        box.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(Math.min((int) (560 * dp), (int) (screenW * 0.92f)), (int) (screenH * 0.85f));
        }
        d.show();
        return d;
    }

    private static TextView tab(Activity a, float dp, int text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight((int) (44 * dp));
        t.setPadding(0, 0, 0, (int) (4 * dp));
        return t;
    }

    private static int attrColor(Activity a, int attr, int fallback) {
        TypedValue v = new TypedValue();
        if (!a.getTheme().resolveAttribute(attr, v, true)) return fallback;
        if (v.resourceId != 0) return a.getResources().getColor(v.resourceId, a.getTheme());
        return v.data;
    }
}
