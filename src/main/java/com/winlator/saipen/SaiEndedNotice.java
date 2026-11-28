package com.winlator.saipen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.R;

import java.io.File;

/**
 * SAI Pen's screen side of SaiEnded: once SAI did not close by itself, a
 * message says what happened and what helps, then the mark is gone.
 */
public final class SaiEndedNotice {
    private static final String TAG = "SaiPen";

    /** The last ending, for a bug report: "low memory, 2026-11-27 14:05". */
    public static final String PREF_LAST = "saipen_sai_ended_last";

    private SaiEndedNotice() {}

    public static long versionCode(Context context) {
        try {
            android.content.pm.PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        }
        catch (Exception e) {
            return 0;
        }
    }

    /** Is a mark left and SAI not running now? (The screen can open while a
     *  session is still alive, from the app's icon.) */
    public static boolean pending(Context context) {
        return SaiEnded.left(context.getFilesDir()) != null
            && !SaiProcessWatch.isRunning(new File("/proc"), "sai2.exe");
    }

    /** Shows the message if SAI did not close by itself last time. */
    public static void showIfPending(Activity activity, SharedPreferences prefs, int dialogTheme, Runnable report) {
        File dir = activity.getFilesDir();
        SaiEnded.Marker m = SaiEnded.left(dir);
        if (m == null || SaiProcessWatch.isRunning(new File("/proc"), "sai2.exe")) return;
        SaiEnded.forget(dir);

        int reason = -1;
        String description = null;
        long exitMs = -1;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
                for (android.app.ApplicationExitInfo e : am.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 5)) {
                    if (e.getTimestamp() < m.startedMs) continue;
                    /* the newest after SAI started; the list is newest first */
                    reason = e.getReason();
                    description = e.getDescription();
                    exitMs = e.getTimestamp();
                    break;
                }
            }
            catch (RuntimeException e) {
                Log.w(TAG, "exit reasons not read", e);
            }
        }
        long bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        SaiEnded.Why why = SaiEnded.why(m, versionCode(activity), bootMs, reason, description);
        Log.i(TAG, "SAI did not close by itself last time: " + why + " (reason " + reason + ", " + description + ")");
        prefs.edit().putString(PREF_LAST, SaiEnded.summary(why, m)
            + (reason >= 0 ? ", Android: " + reason + (description != null ? " " + description : "") : "")).apply();

        /* the crash log for the report; Android's exit told here is not told again */
        CrashLog.add(dir, CrashLog.entry("SAI did not close by itself: " + SaiEnded.summary(why, m),
            reason >= 0 ? "Android: " + androidReason(reason) + (description != null ? ", " + description : "")
                + (exitMs > 0 ? ", " + CrashLog.when(exitMs) : "") : null,
            "SAI Pen version code " + m.versionCode,
            why == SaiEnded.Why.CRASHED ? CrashLog.takeError(dir) : null));
        if (exitMs > prefs.getLong(PREF_EXIT_SEEN, 0)) prefs.edit().putLong(PREF_EXIT_SEEN, exitMs).apply();

        int what;
        switch (why) {
            case LOW_MEMORY: what = R.string.saipen_ended_low_memory; break;
            case IN_BACKGROUND: what = R.string.saipen_ended_background; break;
            case SWIPED: what = R.string.saipen_ended_swiped; break;
            case CRASHED: what = R.string.saipen_ended_crashed; break;
            case SAI_CRASHED: what = R.string.saipen_ended_sai_crashed; break;
            case UPDATED: what = R.string.saipen_ended_updated; break;
            case RESTARTED: what = R.string.saipen_ended_restarted; break;
            default: what = R.string.saipen_ended_unknown;
        }
        String message = (why == SaiEnded.Why.SAI_CRASHED ? activity.getString(what, m.detail) : activity.getString(what)) + "\n\n" + activity.getString(R.string.saipen_ended_lost);
        if (SaiEnded.batteryHelps(why)) message += "\n\n" + activity.getString(R.string.saipen_ended_battery_help);

        AlertDialog.Builder b = new AlertDialog.Builder(activity, dialogTheme)
            .setTitle(R.string.saipen_ended_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null);
        if (SaiEnded.batteryHelps(why)) b.setNeutralButton(R.string.saipen_ended_open_app_settings, (d, w) -> openAppSettings(activity));
        else if (why == SaiEnded.Why.CRASHED || why == SaiEnded.Why.SAI_CRASHED) b.setNeutralButton(R.string.saipen_report, (d, w) -> report.run());
        b.show();
    }

    static final String PREF_EXIT_SEEN = "saipen_exit_seen_ms";

    /** "crash native (5)" */
    static String androidReason(int reason) {
        switch (reason) {
            case 1: return "exit self (1)";
            case 2: return "signaled (2)";
            case 3: return "low memory (3)";
            case 4: return "crash (4)";
            case 5: return "crash native (5)";
            case 6: return "ANR, not responding (6)";
            case 9: return "excessive resource usage (9)";
            case 10: return "user requested (10)";
            case 11: return "user stopped (11)";
            case 13: return "other kills by system (13)";
            case 16: return "package updated (16)";
            default: return "reason " + reason;
        }
    }

    /**
     * Crashes outside what showIfPending told (SAI not open, or SAI Pen's
     * screen alone): Android's own crash, native crash and ANR records
     * (Android 11 and newer), and SAI Pen's last Java error with its stack.
     * Into the crash log for the report; each told once.
     */
    public static void collectCrashes(Activity activity, SharedPreferences prefs) {
        File dir = activity.getFilesDir();
        String error = CrashLog.takeError(dir);
        long seen = prefs.getLong(PREF_EXIT_SEEN, 0), newest = seen;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(activity.getPackageName(), 0, CrashLog.MAX);
                /* oldest first, so the log ends newest first */
                for (int i = list.size() - 1; i >= 0; i--) {
                    android.app.ApplicationExitInfo e = list.get(i);
                    if (e.getTimestamp() <= seen) continue;
                    newest = Math.max(newest, e.getTimestamp());
                    int r = e.getReason();
                    /* a Java crash with its stack saved is told by that, below */
                    boolean crash = r == 5 || r == 6 || (r == 4 && error == null);
                    if (!crash) continue;
                    CrashLog.add(dir, CrashLog.entry("SAI Pen stopped (Android: " + androidReason(r) + ")",
                        CrashLog.when(e.getTimestamp()) + (e.getDescription() != null ? ", " + e.getDescription() : ""),
                        "process " + e.getProcessName() + ", importance " + e.getImportance()));
                }
            }
            catch (RuntimeException e) {
                Log.w(TAG, "exit reasons not read", e);
            }
        }
        if (error != null) CrashLog.add(dir, CrashLog.entry("SAI Pen stopped with an error", error,
            "SAI Pen version code " + versionCode(activity)));
        if (newest > seen) prefs.edit().putLong(PREF_EXIT_SEEN, newest).apply();
    }

    /** Android's page for SAI Pen, where Battery > Unrestricted is. */
    public static void openAppSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null)));
        }
        catch (android.content.ActivityNotFoundException e) {
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
            catch (android.content.ActivityNotFoundException ignored) {}
        }
    }
}
