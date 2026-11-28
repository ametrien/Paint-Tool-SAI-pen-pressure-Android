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
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
                for (android.app.ApplicationExitInfo e : am.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 5)) {
                    if (e.getTimestamp() < m.startedMs) continue;
                    /* the newest after SAI started; the list is newest first */
                    reason = e.getReason();
                    description = e.getDescription();
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
