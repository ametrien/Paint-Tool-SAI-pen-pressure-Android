package com.winlator.saipen;

import android.util.Log;

import java.io.File;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * While SAI runs: packs each frame the DLL writes (TimelapsePack) into this
 * SAI start's own folder, about once a second, so the DLL's folder holds a
 * frame or two at most and nothing in it is ever thinned or replaced.
 *
 * It must not slow the drawing (the owner, 2026-11-23): one thread at the
 * lowest priority, kept on the slow cores when the device has any
 * (FastCores; SAI's drawing threads are on the fast ones), and packing a
 * 4 MB frame is one pass of deflate's quickest level.
 */
public final class TimelapseRecorder {
    private static final String TAG = "SaiPen";
    private static final long POLL_MS = 1000;
    /** Packed frames on the device before the longest session gets
     *  coarser: the artist's "Room for frames" (TimelapseBudget), set by
     *  SaiLauncherActivity before SAI starts; 2 GB until then. It was a fixed
     *  1 GB, which a 30-minute drawing went past (furisode, 2026-11-26). */
    public static volatile long budgetBytes = TimelapseBudget.MIN_MB << 20;

    private final File frames, sessionDir, packedRoot;
    private final ScheduledExecutorService thread;
    private final TimelapsePack.Result total = new TimelapsePack.Result();

    private TimelapseRecorder(File containerRoot) {
        frames = TimelapseVideo.framesDir(containerRoot);
        packedRoot = TimelapsePack.packedDir(containerRoot);
        sessionDir = TimelapsePack.newSessionDir(packedRoot, new Date());
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sai-timelapse-pack");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** Starts packing for one SAI start; null without a container. */
    public static TimelapseRecorder start(File containerRoot) {
        if (containerRoot == null) return null;
        TimelapseRecorder r = new TimelapseRecorder(containerRoot);
        r.thread.execute(r::onSlowCores);
        r.thread.scheduleWithFixedDelay(r::packNow, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        Log.i(TAG, "timelapse: packing frames into " + r.sessionDir.getName());
        return r;
    }

    private void onSlowCores() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
            FastCores fc = FastCores.find(new File("/sys/devices/system/cpu"));
            if (fc == null || fc.fast == null || fc.fast.length == 0 || fc.fast.length >= fc.cores.length) return;
            int[] slow = new int[fc.cores.length - fc.fast.length];
            int n = 0;
            for (int c : fc.cores) {
                boolean isFast = false;
                for (int f : fc.fast) isFast |= f == c;
                if (!isFast) slow[n++] = c;
            }
            boolean ok = SaiCores.taskset(android.os.Process.myTid(), FastCores.mask(slow));
            Log.i(TAG, "timelapse: packing on the slow cores " + java.util.Arrays.toString(slow) + (ok ? "" : " (taskset failed)"));
        }
        catch (Throwable t) {
            Log.w(TAG, "timelapse: packing thread left on any core", t);
        }
    }

    private synchronized void packNow() {
        try {
            TimelapsePack.Result r = TimelapsePack.packAll(frames, sessionDir);
            if (r.frames == 0) return;
            total.frames += r.frames;
            total.rawBytes += r.rawBytes;
            total.packedBytes += r.packedBytes;
            /* the video as it goes, like the Mac's encoder: a canvas with
               enough frames gets a chunk of video now, and they go */
            try {
                int encoded = TimelapseLive.encodeReady(sessionDir, TimelapseVideo.LIVE);
                if (encoded > 0) Log.i(TAG, "timelapse: " + encoded + " frame(s) into video while drawing");
            }
            catch (Throwable t) {
                /* the encoder busy or failing: the frames wait for the video after SAI */
                Log.w(TAG, "timelapse: no video while drawing this time: " + t.getMessage());
            }
            long budget = budgetBytes;
            int thinned = TimelapsePack.enforceBudget(packedRoot, budget);
            if (thinned > 0) Log.w(TAG, "timelapse: over " + (budget >> 20) + " MB packed, " + thinned + " frame(s) thinned");
        }
        catch (Throwable t) {
            /* the frames stay in the DLL's folder: packLeftovers takes them later */
            Log.w(TAG, "timelapse: packing failed", t);
        }
    }

    /** SAI has gone: the last frames are packed here, before the app
     *  restarts, so they stay in this SAI start's folder. */
    public void finish() {
        thread.shutdown();
        try {
            thread.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        packNow();
        TimelapsePack.removeEmptySessions(packedRoot);
        Log.i(TAG, "timelapse: this SAI start, " + total);
    }
}
