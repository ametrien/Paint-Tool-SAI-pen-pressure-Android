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
    private volatile boolean touching;
    /** Frames on the glass: one for the app, made on the GL thread
     *  (GLRenderer), which may come before or after a session. */
    private static GlassTimes glass;
    /** -1 not known yet, 0 no, 1 yes */
    private static volatile int glassState = -1;
    /** The app's frame pacer (XServerView), for the log. */
    public static volatile FramePacer pacer;
    /** The pen touched since the last tick (SaiCores counts only drawing time). */
    private volatile boolean penSinceTick;
    private SaiCores cores;
    private BusySai busy;
    private CpuSamples samples;
    private java.util.function.Consumer<String> onBusy;
    private String busyWords;
    private boolean busyTold;
    private AppCores appCores;         /* speed test only (AppCores.PROP), usually null */
    private PowerWatch power;           /* battery saver and heat: both slow SAI down */
    /** How much you draw (DrawingStats), counted with the speed log on or off. */
    private volatile DrawingStats stats;
    private File statsFile;
    /** Time per drawing (CanvasTime), and SAI's window title: it names the open canvas. */
    private volatile CanvasTime canvas;
    private File canvasFile;
    private volatile String saiTitle;
    /** Seconds since the stats were last saved (saved every SAVE_EVERY ticks). */
    private int ticksSinceSave;
    private static final int SAVE_EVERY = 15;
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
            .getBoolean(PREF_SPEED_LOG, FirstRun.SPEED_LOG);
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
        s.header("  Android shows a frame a refresh or two after it: to glass, where the device says when.");
        s.header("frames on the glass: " + glassWords());
        s.header("front buffer: " + FrontBuffer.state);
        FramePacer p = pacer;
        s.header("frames: " + (p == null ? "?" : p.isPaced() ? "at most one per display refresh (vsync)" : "one per request (free)"));
        s.statsFile = new File(activity.getFilesDir(), DrawingStats.FILE);
        s.stats = DrawingStats.load(s.statsFile);
        s.canvasFile = new File(activity.getFilesDir(), CanvasTime.FILE);
        s.canvas = CanvasTime.load(s.canvasFile);
        s.cores = new SaiCores(new File("/proc"), SaiProcessWatch.commOf(exeName),
                               FastCores.find(new File("/sys/devices/system/cpu")),
                               SaiCores.readMode(activity.getFilesDir()), SaiCores::taskset, s::header);
        s.header(s.cores.describe());
        {
            /* SAI filling its cores (BusySai), and its use now and then for the report (CpuSamples) */
            FastCores fc = FastCores.find(new File("/sys/devices/system/cpu"));
            int total = fc.cores.length > 0 ? fc.cores.length : Runtime.getRuntime().availableProcessors();
            int seen = SaiProcessors.resolve(androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                .getInt(SaiProcessors.PREF, SaiProcessors.AUTO), fc.fast.length, total);
            double share = BusySai.FULL_SHARE;
            try {
                String prop = PenInput.systemProperty(BusySai.PROP_SHARE).trim();
                if (!prop.isEmpty()) share = Double.parseDouble(prop);
            }
            catch (NumberFormatException ignored) {}
            s.busy = new BusySai(seen, s.cores.mode() == SaiCores.Mode.OFF ? total : s.cores.allowedCores(), share);
            Date now = new Date();
            s.samples = new CpuSamples(activity.getFilesDir(), new java.util.Random(), SystemClock.uptimeMillis(),
                s.busy.cores(), new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now), clock(),
                "SAI sees " + (seen > 0 ? String.valueOf(seen) : "all " + total) + " cores, fast cores: "
                + s.cores.mode().name().toLowerCase(Locale.ROOT));
            /* told, not only done: SAI may be slower now, and saving is wise */
            s.onBusy = text -> activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(com.winlator.R.string.saipen_busy_title)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
                catch (RuntimeException e) {
                    Log.w(TAG, "busy message not shown: " + e);
                }
            });
            s.busyWords = activity.getString(s.cores.mode() == SaiCores.Mode.OFF
                ? com.winlator.R.string.saipen_busy_no_pin : com.winlator.R.string.saipen_busy_all_cores, s.busy.cores());
        }
        s.appCores = AppCores.create(PenInput.systemProperty(AppCores.PROP), new File("/proc/self/task"),
                                     FastCores.find(new File("/sys/devices/system/cpu")), SaiCores::taskset, s::header);
        s.header(speedTests());
        boolean[] started = {false};    /* the state now in the header, changes as notes */
        s.power = PowerWatch.start(activity, text -> {
            if (started[0]) s.log.note(SystemClock.uptimeMillis(), "power: " + text);
            else s.header("power: " + text);
        });
        started[0] = true;
        s.header("");

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (!window.isDesktopWindow() && SaiStartup.isSaiTitle(window.getName())) {
                    log.onSaiWindow(SystemClock.uptimeMillis());
                    s.titleNow(window.getName());
                }
            }

            /* SAI names the open canvas in its title: a new name, another canvas */
            @Override
            public void onModifyWindowProperty(Window window, com.winlator.xserver.Property property) {
                if (window.isDesktopWindow()) return;
                String name = window.getName();
                if (SaiStartup.isSaiTitle(name)) s.titleNow(name);
            }

            @Override
            public void onUpdateWindowContent(Window window) {
                log.onContentUpdate(SystemClock.uptimeMillis());
            }
        });
        thread.scheduleWithFixedDelay(() -> s.tick(SaiProcessWatch.commOf(exeName)), 1000, 1000, TimeUnit.MILLISECONDS);
    }

    /** The speed-test properties set for this session (adb shell setprop), so a log says what it measured. */
    private static String speedTests() {
        StringBuilder b = new StringBuilder("speed tests:");
        for (String p : new String[]{PenInput.PROP_CURSOR_MS, PenWire.PROP_KEEPALIVE_MS, AppCores.PROP, DebugEnv.PROP, BusySai.PROP_SHARE}) {
            String v = PenInput.systemProperty(p).trim();
            if (!v.isEmpty()) b.append(' ').append(p).append('=').append(v);
        }
        return b.length() == "speed tests:".length() ? "speed tests: none" : b.toString();
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
            busyTick(now, sai, all);
            cores.tick(now, drew);
            if (appCores != null) appCores.tick();
        }
        catch (RuntimeException e) {
            header("cores: " + e);
        }
        flush();
        if (++ticksSinceSave >= SAVE_EVERY) {
            ticksSinceSave = 0;
            saveStats();
        }
    }

    /** SAI filling every core it can use (BusySai): every core for it until
     *  it calms down; and the report's samples (CpuSamples). */
    private void busyTick(long now, int sai, int all) {
        if (busy == null) return;
        BusySai.Step step = busy.tick(now, sai);
        if (step == BusySai.Step.OPEN_ALL) {
            log.note(now, "SAI used " + sai + "% for " + BusySai.FULL_FOR_MS / 1000 + " s, all it can use: every core for it now");
            if (samples != null) samples.note(clock(), now, "SAI full (" + sai + "%) for "
                + BusySai.FULL_FOR_MS / 1000 + " s" + (cores.mode() == SaiCores.Mode.OFF ? "" : ": let onto every core"));
            cores.openAll();
            /* once a session: a second time it would only be in the way */
            if (onBusy != null && !busyTold) onBusy.accept(busyWords);
            busyTold = true;
        }
        else if (step == BusySai.Step.BACK) {
            log.note(now, "SAI calm again (" + sai + "%)");
            if (samples != null) samples.note(clock(), now, "SAI calm again (" + sai + "%)"
                + (cores.mode() == SaiCores.Mode.OFF ? "" : ": back on the fast cores"));
            cores.back();
        }
        if (samples != null) samples.tick(now, sai, all, clock());
    }

    private static String clock() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private void titleNow(String title) {
        String was = saiTitle;
        if (title.equals(was)) return;
        saiTitle = title;
        Log.i(TAG, "SAI title: " + title);
        CanvasTime ct = canvas;
        if (ct != null) ct.titleChanged(CanvasTime.key(was), CanvasTime.key(title), System.currentTimeMillis());
    }

    /** SAI is running: its time per drawing is counted in memory and saved
     *  from there, so nothing else may change canvas-time.txt now. */
    public static synchronized boolean running() {
        return current != null;
    }

    /** SAI wrote a file under SAI Drawings (SaiSession's watcher): `path`
     *  relative to it. A new canvas saved under a name keeps its time. */
    public static void fileSaved(String path) {
        SpeedSession s = current;
        CanvasTime ct = s == null ? null : s.canvas;
        if (ct != null) ct.fileSaved(path, System.currentTimeMillis());
    }

    private void saveStats() {
        if (canvas != null && !canvas.saveIfChanged(canvasFile)) Log.w(TAG, "time per drawing not saved");
        if (stats != null && !stats.saveIfChanged(statsFile)) Log.w(TAG, "drawing stats not saved");
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
        CanvasTime ct = s.canvas;
        if (ct != null) {
            if (nowTouching) ct.penDown(now / 1000.0);
            else ct.penUp(now / 1000.0, CanvasTime.key(s.saiTitle), System.currentTimeMillis());
        }
        DrawingStats st = s.stats;
        if (st != null) {
            if (nowTouching) st.penDown(now / 1000.0);
            else {
                java.time.LocalDateTime wall = java.time.LocalDateTime.now();
                double clock = wall.getHour() * 3600 + wall.getMinute() * 60 + wall.getSecond()
                             + wall.getNano() / 1_000_000_000.0;
                st.penUp(now / 1000.0, wall.toLocalDate(), clock);
            }
        }
    }

    private static String glassWords() {
        return glassState < 0 ? "not known yet" : glassState == 1 ? "Android's frame timestamps, on" : "this device does not say";
    }

    /** GLRenderer's surface is (re)made, on the GL thread. */
    public static void surfaceReady() {
        try {
            if (glass == null) glass = new GlassTimes(GlassTimes.android());
            int was = glassState;
            glassState = glass.surfaceReady() ? 1 : 0;
            SpeedSession s = current;
            if (s != null && was != glassState) s.log.note(SystemClock.uptimeMillis(), "frames on the glass: " + glassWords());
            if (s != null) s.log.note(SystemClock.uptimeMillis(), "front buffer: " + FrontBuffer.state);
        }
        catch (RuntimeException e) {
            glassState = 0;
            Log.w(TAG, "frame timestamps: " + e);
        }
    }

    /** The end of a frame, before its swap (GLRenderer, GL thread). */
    public static void beforeSwap(long frameStart, long frameEnd) {
        GlassTimes g = glass;
        SpeedSession s = current;
        if (g == null || s == null) return;
        /* with the front buffer Android's frame times say nothing true: none
         * in single-buffer mode, and confused ones around a switch (764 ms
         * seen with Front buffer while drawing) */
        if (FrontBuffer.offscreen()) return;
        g.onFrame(frameStart, frameEnd, s.touching, s.log::onFrameFate);
    }

    /** A window's picture went to the GPU (Texture.updateFromDrawable). */
    public static void upload(long nanos, long bytes) {
        SpeedSession s = current;
        if (s != null) s.log.onUpload(nanos / 1000, bytes);
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

    /** A line of its own in the log (which also goes to logcat); logcat alone without a session. */
    public static void note(String text) {
        SpeedSession s = current;
        if (s != null) s.log.note(SystemClock.uptimeMillis(), text);
        else Log.i(TAG, text);
    }

    /** SAI closed or the session ends: the summary, and the file is done. */
    public static synchronized void finish() {
        SpeedSession s = current;
        if (s == null) return;
        current = null;
        s.thread.shutdownNow();
        if (s.power != null) s.power.stop();
        FramePacer p = pacer;
        if (p != null) s.log.note(SystemClock.uptimeMillis(), "frames: " + p.counts());
        String fb = FrontBuffer.switchesWords();
        if (fb != null) s.log.note(SystemClock.uptimeMillis(), "front buffer: " + fb);
        s.log.finish(SystemClock.uptimeMillis());
        if (s.samples != null && s.busy != null)
            s.samples.finish(SystemClock.uptimeMillis(), clock(), s.busy.fullMs(), s.busy.longestFullMs());
        if (s.canvas != null) s.canvas.cancel();
        if (s.stats != null) s.stats.cancel();
        s.saveStats();
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
