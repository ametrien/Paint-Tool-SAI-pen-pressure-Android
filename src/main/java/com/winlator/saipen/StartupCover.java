package com.winlator.saipen;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

/**
 * "Starting SAI" over the session until SAI's window is up and done drawing
 * (the decision is SaiStartup's). Before, the first seconds showed a grey,
 * half-drawn SAI and Wine's empty desktop.
 *
 * The cover takes the touches meant for the screen under it, so a tap while
 * SAI starts cannot land somewhere unexpected. Its decisions go to the log
 * (adb logcat -s SaiPen) for tuning SaiStartup's times on a device.
 */
public final class StartupCover {
    private static final String TAG = "SaiPen";
    private static final String PREF_MENU_HINTS = "saipen_menu_hints_shown";
    private static final int MENU_HINTS = 3;
    /* dark: the owner found the light cover too bright (2026-11-19) */
    static final int BACKGROUND = Color.rgb(0x1E, 0x1E, 0x1E);
    static final int TEXT = Color.rgb(0xEE, 0xEE, 0xEE);
    static final int TEXT_DIM = Color.rgb(0xAA, 0xAA, 0xAA);

    private StartupCover() {}

    /** Which SAI this start is (set by SaiLauncherActivity before it starts
     *  SAI); remembered once SAI has come up, so the next start of the same
     *  SAI does not say "The first start takes longer". */
    static volatile String saiIdentity = "";
    private static final String PREF_STARTED_SAI = "saipen_started_sai";

    /** A SAI file as its path, size and date: another SAI, or the same one
     *  changed, reads as not started yet. */
    static String identity(java.io.File exe) {
        return exe.getAbsolutePath() + "|" + exe.length() + "|" + exe.lastModified();
    }

    public static void show(Activity activity, FrameLayout root, XServer xServer, String exeName) {
        final SharedPreferences startPrefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final String thisSai = saiIdentity;
        final boolean firstStart = thisSai.isEmpty() || !thisSai.equals(startPrefs.getString(PREF_STARTED_SAI, ""));
        SpeedSession.start(activity, xServer, exeName);
        final SaiStartup startup = new SaiStartup(SystemClock.uptimeMillis());
        final View cover = build(activity);
        final TextView stageText = cover.findViewWithTag("stage");
        final ProgressBar bar = cover.findViewWithTag("bar");
        /* above the floating keys and the red Exit SAI, which have a raised elevation */
        cover.setElevation(64 * activity.getResources().getDisplayMetrics().density);
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.MATCH_PARENT));

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (window.isDesktopWindow()) return;
                String title = window.getName();
                startup.onWindowMapped(title, SystemClock.uptimeMillis());
                if (!title.isEmpty()) Log.i(TAG, "window mapped: " + title);
            }

            @Override
            public void onUpdateWindowContent(Window window) {
                startup.onContentUpdate(SystemClock.uptimeMillis());
            }
        });

        /* SAI's processor time, 4 times a second, for SaiStartup's "settled"
         * rule; on its own thread: reading /proc is not for the UI thread */
        final java.util.concurrent.ScheduledExecutorService cpu =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sai-startup-cpu");
                t.setDaemon(true);
                return t;
            });
        cpu.scheduleWithFixedDelay(() -> startup.onSaiCpuTicks(
            SaiProcessWatch.cpuTicks(new java.io.File("/proc"), exeName), SystemClock.uptimeMillis()),
            0, 250, java.util.concurrent.TimeUnit.MILLISECONDS);

        final Handler ui = new Handler(Looper.getMainLooper());
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cover.getParent() == null) return;
                long now = SystemClock.uptimeMillis();
                bar.setProgress(startup.progress(now));
                if (!startup.ready(now)) {
                    stageText.setText(activity.getString(
                        startup.stage() != 0 ? R.string.saipen_starting_drawing
                            : firstStart ? R.string.saipen_starting_wine_first : R.string.saipen_starting_wine,
                        startup.secondsSince(now)));
                    ui.postDelayed(this, 150);
                    return;
                }
                Log.i(TAG, "cover lifted; SAI's window came " + startup.saiWindowAfterMs() + " ms after the start");
                SpeedSession.coverLifted();
                if (firstStart && !thisSai.isEmpty()) startPrefs.edit().putString(PREF_STARTED_SAI, thisSai).apply();
                cpu.shutdown();
                cover.animate().alpha(0f).setDuration(250).withEndAction(() -> root.removeView(cover)).start();
                showMenuHint(activity);
                /* Ctrl, Alt and Shift for SAI without a keyboard, once SAI is up */
                FloatingKeys.show(activity, root, xServer);
                /* four fingers held still: the gestures' settings over SAI */
                GesturesOverlay.attach(activity);
            }
        }, 150);
    }

    /** One loading-screen tip, picked at random from those true for the
     *  gestures as they are set now (LoadingTips). */
    static String tip(Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        Gestures.Action[] actions = Gestures.actions(prefs.getAll());
        boolean keysGesture = prefs.getBoolean(FloatingKeys.PREF_GESTURE, FloatingKeys.GESTURE_BY_DEFAULT);
        java.util.List<LoadingTips.Tip> tips = LoadingTips.tips(actions, keysGesture);
        LoadingTips.Tip t = tips.get(new java.util.Random().nextInt(tips.size()));
        switch (t.kind) {
            case KEYBOARD:
                /* the standard gesture keeps its own, smoother sentence */
                return t.input == Gestures.Input.THREE_TAP ? activity.getString(R.string.saipen_tip_keyboard)
                    : activity.getString(R.string.saipen_tip_keyboard_any, gestureName(activity, t.input));
            case KEYS_HOLD:
                return activity.getString(R.string.saipen_tip_keys);
            case KEYS:
                return activity.getString(R.string.saipen_tip_keys_any, gestureName(activity, t.input));
            case SETTINGS:
                return activity.getString(R.string.saipen_tip_settings);
            case DRIVES:
                return activity.getString(R.string.saipen_tip_drives);
            case CHANGE_GESTURES:
            default:
                return activity.getString(R.string.saipen_tip_change_gestures);
        }
    }

    /** A gesture's name inside a sentence: "two-finger double tap". */
    private static String gestureName(Activity activity, Gestures.Input in) {
        String n = activity.getString(GesturesSettings.inputName(in));
        if (n.isEmpty()) return n;
        java.util.Locale l = activity.getResources().getConfiguration().getLocales().get(0);
        return n.substring(0, 1).toLowerCase(l) + n.substring(1);
    }

    private static View build(Activity activity) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(BACKGROUND);
        box.setClickable(true);       /* eat the touches meant for SAI below */

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_saipen);
        box.addView(icon, new LinearLayout.LayoutParams((int) (96 * dp), (int) (96 * dp)));

        TextView text = new TextView(activity);
        text.setText(R.string.saipen_starting);
        text.setTextColor(TEXT);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * dp);
        box.addView(text, lp);

        /* a bar that fills (SaiStartup.progress), not the round spinner: on
         * the tablet the spinner read as a "refresh" button and the start
         * looked frozen */
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setTag("bar");
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams((int) (240 * dp),
                                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = (int) (20 * dp);
        box.addView(progress, pp);

        /* what is happening and for how long: "Windows and SAI are starting, 7 s" */
        TextView stage = new TextView(activity);
        stage.setTag("stage");
        stage.setTextColor(TEXT_DIM);
        stage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (12 * dp);
        box.addView(stage, sp);

        /* a gesture worth knowing, as it is set now, picked at random each start */
        TextView tip = new TextView(activity);
        tip.setText(tip(activity));
        tip.setTextColor(TEXT);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tip.setGravity(Gravity.CENTER);
        tip.setMaxWidth((int) (420 * dp));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (36 * dp);
        box.addView(tip, tp);
        return box;
    }

    /** The first few times: where SAI Pen's menu is (Winlator's drawer). */
    private static void showMenuHint(Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        int shown = prefs.getInt(PREF_MENU_HINTS, 0);
        if (shown >= MENU_HINTS) return;
        prefs.edit().putInt(PREF_MENU_HINTS, shown + 1).apply();
        /* the menu's gestures cannot be changed; the keyboard's can */
        String hint = activity.getString(R.string.saipen_menu_hint);
        Gestures.Input keyboard = LoadingTips.first(Gestures.actions(prefs.getAll()), Gestures.Type.KEYBOARD);
        if (keyboard != null) hint += " " + activity.getString(R.string.saipen_menu_hint_keyboard, gestureName(activity, keyboard));
        Toast.makeText(activity, hint, Toast.LENGTH_LONG).show();
    }
}
