package com.winlator.saipen;

import android.app.Activity;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The speed log of one SAI session (SpeedLog says what is in it), written to
 * Documents/SAI Pen logs/speed <date> <screen>.txt and to logcat (SaiPenSpeed).
 * One session at a time; every hook is a no-op without one.
 */
public final class SpeedSession {
    private static final String TAG = "SaiPenSpeed";
    public static final String FOLDER = "SAI Pen logs";
    /** Older logs beyond this many are deleted. */
    private static final int KEEP = 30;
    /** Linux counts processor time in 1/100 s. */
    private static final int TICKS_PER_SECOND = 100;

    private static SpeedSession current;
    /** SAI Pen's screen: write the speed log file or not (logcat gets it
     *  either way). Off still pins the fast cores: SaiCores lives here. */
    public static final String PREF_SPEED_LOG = "saipen_speed_log";

    private final SpeedLog log;
    private final BufferedWriter file;
    private final ScheduledExecutorService thread;
    private boolean touching;
    /** The pen touched since the last tick (SaiCores counts only drawing time). */
    private volatile boolean penSinceTick;
    private SaiCores cores;
    private long lastSai = -1, lastAll = -1, lastTickAt = -1;

    private SpeedSession(SpeedLog log, BufferedWriter file, ScheduledExecutorService thread) {
        this.log = log;
        this.file = file;
        this.thread = thread;
    }

    public static synchronized void start(Activity activity, XServer xServer, String exeName) {
        finish();
        int w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date());
        BufferedWriter writer = null;
        boolean keep = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean(PREF_SPEED_LOG, true);
        if (keep) try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER);
            if (dir.isDirectory() || dir.mkdirs()) {
                prune(dir);
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
                    new File(dir, "speed " + stamp + " " + w + "x" + h + ".txt")), StandardCharsets.UTF_8));
            }
        }
        catch (IOException e) {
            Log.w(TAG, "no log file: " + e);
        }
        final BufferedWriter out = writer;
        SpeedLog log = new SpeedLog(SystemClock.uptimeMillis(), line -> {
            Log.i(TAG, line);
            if (out == null) return;
            try {
                out.write(line);
                out.write('\n');
            }
            catch (IOException ignored) {}
        });
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sai-speed-log");
            t.setDaemon(true);
            return t;
        });
        SpeedSession s = new SpeedSession(log, writer, thread);
        current = s;

        String version = "?";
        try {
            version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        }
        catch (Exception ignored) {}
        s.header("SAI Pen " + version + " speed log, " + stamp);
        s.header(Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                 + ", " + Runtime.getRuntime().availableProcessors() + " cores");
        s.header("SAI's screen " + w + "x" + h + ", display " + Math.round(refreshRate(activity)) + " Hz");
        s.header("cpu: percent of one core; draws: window updates in that second");
        s.header("to screen: the tablet feeling the pen to the app's frame with SAI's first drawing;");
        s.header("  input: tablet to app, SAI: app to SAI's drawing (ink), frame: SAI's drawing to the frame.");
        s.header("  Android shows a frame a refresh or two after it: not measured here.");
        s.cores = new SaiCores(new File("/proc"), SaiProcessWatch.commOf(exeName),
                               FastCores.find(new File("/sys/devices/system/cpu")),
                               SaiCores.readMode(activity.getFilesDir()), SaiCores::taskset, s::header);
        s.header(s.cores.describe());
        s.header("");

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (!window.isDesktopWindow() && SaiStartup.isSaiTitle(window.getName()))
                    log.onSaiWindow(SystemClock.uptimeMillis());
            }

            @Override
            public void onUpdateWindowContent(Window window) {
                log.onContentUpdate(SystemClock.uptimeMillis());
            }
        });
        thread.scheduleWithFixedDelay(() -> s.tick(SaiProcessWatch.commOf(exeName)), 1000, 1000, TimeUnit.MILLISECONDS);
    }

    private void header(String text) {
        Log.i(TAG, text);
        if (file == null) return;
        try {
            file.write(text);
            file.write('\n');
        }
        catch (IOException ignored) {}
    }

    private void tick(String saiComm) {
        long now = SystemClock.uptimeMillis();
        long[] cpu = cpuNow(saiComm);
        int sai = -1, all = -1;
        if (lastTickAt >= 0 && now > lastTickAt) {
            long elapsed = now - lastTickAt;
            if (cpu[0] >= 0 && lastSai >= 0) sai = percent(cpu[0] - lastSai, elapsed);
            if (lastAll >= 0) all = percent(cpu[1] - lastAll, elapsed);
        }
        lastSai = cpu[0];
        lastAll = cpu[1];
        lastTickAt = now;
        log.tick(now, sai, all);
        boolean drew = touching || penSinceTick;
        penSinceTick = false;
        try {
            cores.tick(now, drew);
        }
        catch (RuntimeException e) {
            header("cores: " + e);
        }
        flush();
    }

    private static int percent(long ticks, long elapsedMs) {
        if (ticks < 0) return -1;
        return (int) (ticks * 1000L * 100 / TICKS_PER_SECOND / elapsedMs);
    }

    /** {SAI's ticks or -1, all the app's processes' ticks}. An app sees only
     *  its own processes in /proc, and Wine, Box64 and SAI are the app's. */
    private static long[] cpuNow(String saiComm) {
        long sai = -1, all = 0;
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File e : entries) {
                String n = e.getName();
                if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
                try {
                    long t = SpeedLog.cpuTicks(new String(Files.readAllBytes(new File(e, "stat").toPath()),
                                                          StandardCharsets.UTF_8));
                    if (t < 0) continue;
                    all += t;
                    String comm = new String(Files.readAllBytes(new File(e, "comm").toPath()),
                                             StandardCharsets.UTF_8).trim();
                    if (comm.equalsIgnoreCase(saiComm)) sai = t;
                }
                catch (IOException ignored) {
                    /* the process ended while we looked */
                }
            }
        }
        return new long[] {sai, all};
    }

    private void flush() {
        if (file == null) return;
        try {
            file.flush();
        }
        catch (IOException ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static float refreshRate(Activity activity) {
        try {
            return activity.getWindowManager().getDefaultDisplay().getRefreshRate();
        }
        catch (Exception e) {
            return 0;
        }
    }

    /**
     * A pen event (TouchpadView, after PenInput). touchedAt: the tablet's
     * time of it, MotionEvent.getEventTime(), the same clock as uptimeMillis.
     */
    public static void pen(boolean nowTouching, long touchedAt) {
        SpeedSession s = current;
        if (s == null) return;
        long now = SystemClock.uptimeMillis();
        if (s.touching == nowTouching) {
            if (nowTouching) s.log.onPenMove(now, touchedAt);
            return;
        }
        s.touching = nowTouching;
        if (nowTouching) s.penSinceTick = true;
        if (nowTouching) s.log.onPenDown(now, touchedAt);
        else s.log.onPenUp(now);
    }

    /** The app drew a frame for Android (GLRenderer.onDrawFrame), uptimeMillis. */
    public static void frame(long start, long end) {
        SpeedSession s = current;
        if (s != null) s.log.onFrame(start, end);
    }

    public static void coverLifted() {
        SpeedSession s = current;
        if (s != null) s.log.onCoverLifted(SystemClock.uptimeMillis());
    }

    /** SAI closed or the session ends: the summary, and the file is done. */
    public static synchronized void finish() {
        SpeedSession s = current;
        if (s == null) return;
        current = null;
        s.thread.shutdownNow();
        s.log.finish(SystemClock.uptimeMillis());
        if (s.file != null) {
            try {
                s.file.close();
            }
            catch (IOException ignored) {}
        }
    }

    private static void prune(File dir) {
        File[] logs = dir.listFiles((d, n) -> n.startsWith("speed ") && n.endsWith(".txt"));
        if (logs == null || logs.length < KEEP) return;
        Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i <= logs.length - KEEP; i++) logs[i].delete();
    }
}
