package com.winlator.saipen;

import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ends a SAI session when SAI closes (SaiProcessWatch says when): closing
 * SAI with its own red cross used to leave an empty Wine desktop.
 */
public final class SaiSession {
    private static final String TAG = "SaiPen";

    private SaiSession() {}

    /** How often the process list is read. Cheap: the app sees only its
     *  own few dozen processes. */
    private static final long POLL_MS = 250;

    /**
     * Watches for `exeName` (sai2.exe). When SAI's main window goes, puts up
     * the closing cover at once (SaiClosing); when SAI's process has gone,
     * runs `exitSession` (Winlator's own exit, which brings back SAI Pen's
     * screen).
     */
    public static void watch(Activity activity, FrameLayout root, XServer xServer, String exeName,
                             Runnable exitSession) {
        final SaiProcessWatch watch = new SaiProcessWatch();
        final SaiClosing closing = new SaiClosing();
        final View[] cover = {null};
        final Runnable showCover = () -> {
            if (cover[0] != null) return;
            cover[0] = closingCover(activity);
            root.addView(cover[0], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        };
        final Runnable hideCover = () -> {
            if (cover[0] == null) return;
            root.removeView(cover[0]);
            cover[0] = null;
        };

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUnmapWindow(Window window) {
                if (window.isDesktopWindow() || !SaiStartup.isSaiTitle(window.getName())) return;
                if (closing.onSaiWindowGone(SystemClock.uptimeMillis())) {
                    Log.i(TAG, "SAI's window went: closing cover up");
                    activity.runOnUiThread(showCover);
                }
            }

            @Override
            public void onMapWindow(Window window) {
                if (window.isDesktopWindow() || !SaiStartup.isSaiTitle(window.getName())) return;
                if (closing.onSaiWindowBack()) {
                    Log.i(TAG, "SAI's window is back: closing cover down");
                    activity.runOnUiThread(hideCover);
                }
            }
        });

        final File drawingsDir = SaiPenSetup.drawingsFolder();
        drawingsDir.mkdirs();
        android.os.FileObserver observerTemp = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                observerTemp = new android.os.FileObserver(drawingsDir,
                    android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path == null || path.startsWith(".")) return;
                        if (DeviceDrawings.isDrawing(path) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
                            File f = new File(drawingsDir, path);
                            if (f.isFile()) SaiPenSetup.scanFile(activity, f);
                        }
                    }
                };
            } else {
                observerTemp = new android.os.FileObserver(drawingsDir.getAbsolutePath(),
                    android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path == null || path.startsWith(".")) return;
                        if (DeviceDrawings.isDrawing(path) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
                            File f = new File(drawingsDir, path);
                            if (f.isFile()) SaiPenSetup.scanFile(activity, f);
                        }
                    }
                };
            }
            observerTemp.startWatching();
        }
        catch (Throwable t) {
            Log.w(TAG, "drawings FileObserver not started", t);
        }
        final android.os.FileObserver observer = observerTemp;

        final android.app.Application.ActivityLifecycleCallbacks lifecycle =
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityPaused(Activity a) {
                    if (a == activity) SaiPenSetup.scanDrawings(activity);
                }
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            };
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);

        final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sai-process-watch");
            t.setDaemon(true);
            return t;
        });
        thread.scheduleWithFixedDelay(() -> {
            long now = SystemClock.uptimeMillis();
            boolean running = SaiProcessWatch.isRunning(new File("/proc"), exeName);
            if (closing.giveUp(running, now)) {
                Log.i(TAG, "SAI still runs " + SaiClosing.GIVE_UP_MS + " ms after its window went: closing cover down");
                activity.runOnUiThread(hideCover);
            }
            if (!watch.onPoll(running, now)) return;
            thread.shutdown();
            if (observer != null) {
                try { observer.stopWatching(); } catch (Throwable ignored) {}
            }
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            Log.i(TAG, "SAI closed: back to SAI Pen's screen");
            SpeedSession.finish();
            /* what SAI saved inside the app shows on the device at once,
             * behind the closing cover; SAI's process is gone, its files closed.
             * keepOriginals = true: a copy stays inside the app so SAI's
             * Recent list can still open it next session. The copy is harmless:
             * on the next moveDrawingsOut, sameContent() recognises it and
             * does not copy it again (it only takes space inside the app). */
            File container = SaiPenSetup.containerRoot;
            if (container != null) SaiPenSetup.moveDrawingsOut(container, true);
            SaiPenSetup.scanDrawings(activity);
            activity.runOnUiThread(() -> {
                boolean wasUp = cover[0] != null;
                showCover.run();
                /* a moment for a new cover to show before the app restarts */
                root.postDelayed(exitSession, wasUp ? 100 : 400);
            });
        }, 2000, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private static LinearLayout closingCover(Activity activity) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(StartupCover.BACKGROUND);
        box.setClickable(true);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_saipen);
        box.addView(icon, new LinearLayout.LayoutParams((int) (96 * dp), (int) (96 * dp)));

        TextView text = new TextView(activity);
        text.setText(R.string.saipen_closing);
        text.setTextColor(StartupCover.TEXT);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * dp);
        box.addView(text, lp);
        return box;
    }
}
