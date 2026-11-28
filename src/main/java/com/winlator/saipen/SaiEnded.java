package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Did SAI close by itself last time, or did something end it from outside?
 *
 * SAI saves its own settings (new tools, the panel layout) only when it
 * closes itself, and it has no autosave. So when Android ends SAI Pen while
 * SAI is open, unsaved work and SAI's changed settings are gone, and the
 * next start looks like SAI "forgot" everything (issue #3). SAI Pen's screen
 * then says what happened and what helps, once.
 *
 * How it knows: when a SAI session starts, a small file says so (OPEN).
 * Every way of closing SAI on purpose ends in the session's exit (SAI's red
 * cross, File > Exit, the menu's Exit and "SAI Pen: licence and settings"),
 * and that removes the file. If the file is still there when SAI Pen's
 * screen opens, SAI did not close by itself. Android 11 and newer also say
 * why the app last ended (ApplicationExitInfo), which tells a low-memory
 * kill from a swipe in the recent apps or a crash.
 *
 * THE TRAP: Android may end only SAI's own process (Wine's processes are
 * children of the app) while SAI Pen lives on in the background. The session
 * then sees SAI gone and ends normally, as if SAI was closed. So a SAI that
 * goes away while SAI Pen is not on the screen is written down as
 * IN_BACKGROUND: nobody closes SAI with its cross while looking at another
 * app.
 *
 * A SWIPE IS NOT A KILL: swiped away in the recent apps, SAI Pen is not
 * killed by Android. Its screen is destroyed, which stops Wine, and Wine's
 * end used to run the same exit as SAI's red cross: the file was removed
 * and SAI Pen's screen reopened by itself. Now the screen's onDestroy and
 * the foreground service's onTaskRemoved write REMOVED first, and the exit
 * does nothing after that. SAI Pen then ends itself, so Android says
 * EXIT_SELF, not "remove task".
 *
 * A CRASH IS NOT A CLOSE EITHER: SAI's process going away is all the
 * session sees, whether SAI closed itself or crashed. winhandler.exe
 * started SAI, waits for it and sends its exit code (PROGRAM_EXIT): 0 when
 * SAI closed itself, an exception code such as 0xC0000005 when it crashed
 * (SAI_ERROR). SAI Pen's own Java errors write APP_ERROR before Android's
 * crash (catchAppErrors); a native crash or an ANR leaves OPEN, which
 * ApplicationExitInfo then explains on Android 11 and newer.
 *
 * No Android types: the launcher passes Android's numbers in, the JVM tests
 * check the rules.
 */
public final class SaiEnded {
    public static final String FILE = "saipen-sai-open.txt";

    /** What ended SAI, as told on SAI Pen's screen. */
    public enum Why {
        /** Android ended the app to free memory */
        LOW_MEMORY,
        /** SAI went away while SAI Pen was in the background */
        IN_BACKGROUND,
        /** SAI Pen was swiped away in the recent apps, or force stopped */
        SWIPED,
        /** SAI Pen stopped with an error */
        CRASHED,
        /** SAI itself stopped with an error (its exit code says) */
        SAI_CRASHED,
        /** a new SAI Pen was installed while SAI was open */
        UPDATED,
        /** the device was turned off or restarted */
        RESTARTED,
        /** something else ended it (Android said nothing useful) */
        UNKNOWN
    }

    /* Android's ApplicationExitInfo.REASON_* numbers */
    static final int REASON_EXIT_SELF = 1, REASON_SIGNALED = 2, REASON_LOW_MEMORY = 3, REASON_CRASH = 4,
        REASON_CRASH_NATIVE = 5, REASON_ANR = 6, REASON_EXCESSIVE_RESOURCE_USAGE = 9,
        REASON_USER_REQUESTED = 10, REASON_USER_STOPPED = 11, REASON_PACKAGE_UPDATED = 16;

    static final String OPEN = "open", IN_BACKGROUND = "in-background", REMOVED = "removed",
        SAI_ERROR = "sai-error", APP_ERROR = "app-error";

    /** What the file says. */
    public static final class Marker {
        public final String state;
        public final long versionCode;
        /** wall clock, ms */
        public final long startedMs;
        /** when it was seen to end (wall clock, ms), or -1 */
        public final long endedMs;
        /** SAI's exit code (0xC0000005) or SAI Pen's error, or "" */
        public final String detail;

        Marker(String state, long versionCode, long startedMs, long endedMs, String detail) {
            this.state = state;
            this.versionCode = versionCode;
            this.startedMs = startedMs;
            this.endedMs = endedMs;
            this.detail = detail;
        }
    }

    private SaiEnded() {}

    static String text(String state, long versionCode, long startedMs) {
        return text(state, versionCode, startedMs, -1, "");
    }

    static String text(String state, long versionCode, long startedMs, long endedMs, String detail) {
        String t = "state=" + state + "\nversion=" + versionCode + "\nstarted=" + startedMs + "\n";
        if (endedMs >= 0) t += "ended=" + endedMs + "\n";
        if (detail != null && !detail.isEmpty()) t += "detail=" + oneLine(detail) + "\n";
        return t;
    }

    /** one line of at most 200 characters */
    static String oneLine(String s) {
        String t = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    /** null for anything that isn't a whole marker */
    static Marker parse(String text) {
        if (text == null) return null;
        String state = null, detail = "";
        long version = -1, started = -1, ended = -1;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            try {
                if (key.equals("state")) state = value;
                else if (key.equals("version")) version = Long.parseLong(value);
                else if (key.equals("started")) started = Long.parseLong(value);
                else if (key.equals("ended")) ended = Long.parseLong(value);
                else if (key.equals("detail")) detail = value;
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
        if (!OPEN.equals(state) && !IN_BACKGROUND.equals(state) && !REMOVED.equals(state)
                && !SAI_ERROR.equals(state) && !APP_ERROR.equals(state)) return null;
        if (version < 0 || started < 0) return null;
        return new Marker(state, version, started, ended, detail);
    }

    /* SAI's exit code from winhandler.exe (PROGRAM_EXIT), -1 until it comes */
    private static volatile long programExit = -1;

    /** A SAI session starts. */
    public static void started(File filesDir, long versionCode, long nowMs) {
        programExit = -1;
        write(new File(filesDir, FILE), text(OPEN, versionCode, nowMs));
    }

    /** winhandler.exe: the program it started (SAI) ended with this exit code. */
    public static void programExited(int code) {
        programExit = code & 0xFFFFFFFFL;
    }

    /** SAI's exit code, or -1 if winhandler.exe has not told it (yet). */
    public static long programExitCode() {
        return programExit;
    }

    /** Did SAI end by an error of its own? 0 is SAI closing itself; -1 is
     *  not known (an older winhandler, or the message lost): not a crash. */
    public static boolean saiCrashed(long exitCode) {
        return exitCode > 0;
    }

    /** 0xC0000005 */
    public static String code(long exitCode) {
        return String.format(java.util.Locale.ROOT, "0x%08X", exitCode & 0xFFFFFFFFL);
    }

    /* Only an OPEN mark changes: what ended SAI first is what it says. A
     * swipe after SAI went in the background stays "in the background", a
     * crash after a swipe stays a swipe. */
    private static void become(File filesDir, String state, long nowMs, String detail) {
        File f = new File(filesDir, FILE);
        Marker m = read(f);
        if (m != null && OPEN.equals(m.state))
            write(f, text(state, m.versionCode, m.startedMs, nowMs, detail));
    }

    /** SAI went away while SAI Pen was not on the screen: keep that, the
     *  session's own exit must not remove it. */
    public static void goneInBackground(File filesDir) {
        become(filesDir, IN_BACKGROUND, System.currentTimeMillis(), "");
    }

    /** SAI Pen's screen is destroyed, or its task removed, without SAI being
     *  closed first: swiped away in the recent apps (or ended by Android).
     *  Kept like IN_BACKGROUND; nothing without a session. */
    public static void taskRemoved(File filesDir) {
        become(filesDir, REMOVED, System.currentTimeMillis(), "");
    }

    /** SAI itself ended with an error (its exit code, saiCrashed()). */
    public static void saiError(File filesDir, long exitCode) {
        become(filesDir, SAI_ERROR, System.currentTimeMillis(), code(exitCode));
    }

    /** SAI Pen stopped with an error while SAI was open (its Java side). */
    public static void appError(File filesDir, Throwable e) {
        String what = e.getClass().getName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        become(filesDir, APP_ERROR, System.currentTimeMillis(), what);
    }

    private static volatile boolean handlerOn;

    /** From now on an uncaught error in SAI Pen writes APP_ERROR first (if
     *  SAI is open) and the error with its stack (CrashLog, for the report),
     *  then goes on as before (Android's own crash). Once per process. */
    public static synchronized void catchAppErrors(File filesDir) {
        if (handlerOn) return;
        handlerOn = true;
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { CrashLog.saveError(filesDir, t, e, System.currentTimeMillis()); } catch (Throwable ignored) {}
            try { appError(filesDir, e); } catch (Throwable ignored) {}
            if (before != null) before.uncaughtException(t, e);
        });
    }

    /** The session ends on purpose: SAI closed itself, or was closed from the menu. */
    public static void closed(File filesDir) {
        File f = new File(filesDir, FILE);
        Marker m = read(f);
        if (m == null || OPEN.equals(m.state)) f.delete();
    }

    /** What the file says now, or null; it stays until forget(). */
    public static Marker left(File filesDir) {
        return read(new File(filesDir, FILE));
    }

    public static void forget(File filesDir) {
        new File(filesDir, FILE).delete();
    }

    private static Marker read(File f) {
        if (!f.isFile()) return null;
        try {
            return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, String text) {
        try {
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException ignored) {}
    }

    /**
     * Why SAI ended.
     *
     * @param m         the marker that was left
     * @param versionNow this SAI Pen's version code
     * @param bootMs    when the device last started, wall clock ms
     * @param reason    Android's newest exit reason after m.startedMs, or -1 (none, or before Android 11)
     * @param description Android's words with it, or null
     */
    public static Why why(Marker m, long versionNow, long bootMs, int reason, String description) {
        if (m.versionCode != versionNow || reason == REASON_PACKAGE_UPDATED) return Why.UPDATED;
        if (REMOVED.equals(m.state)) return Why.SWIPED;
        if (SAI_ERROR.equals(m.state)) return Why.SAI_CRASHED;
        if (APP_ERROR.equals(m.state)) return Why.CRASHED;
        if (IN_BACKGROUND.equals(m.state)) return Why.IN_BACKGROUND;
        switch (reason) {
            case REASON_LOW_MEMORY:
                return Why.LOW_MEMORY;
            case REASON_CRASH:
            case REASON_CRASH_NATIVE:
            case REASON_ANR:
                return Why.CRASHED;
            case REASON_USER_REQUESTED:
            case REASON_USER_STOPPED:
                return Why.SWIPED;
            default:
                break;
        }
        /* a swipe in the recent apps comes as REASON_OTHER, "remove task" */
        if (description != null && description.toLowerCase(java.util.Locale.ROOT).contains("remove task")) return Why.SWIPED;
        if (bootMs > 0 && m.startedMs < bootMs) return Why.RESTARTED;
        if (reason == REASON_EXCESSIVE_RESOURCE_USAGE) return Why.IN_BACKGROUND;
        return Why.UNKNOWN;
    }

    /** Does a battery setting help against it? */
    public static boolean batteryHelps(Why why) {
        return why == Why.LOW_MEMORY || why == Why.IN_BACKGROUND || why == Why.UNKNOWN;
    }

    /** For a bug report: "sai crashed (0xC0000005), SAI started 2026-11-28 13:00, ended 13:21". */
    public static String summary(Why why, Marker m) {
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
        java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT);
        String s = word(why);
        if (!m.detail.isEmpty()) s += " (" + m.detail + ")";
        s += ", SAI started " + day.format(new java.util.Date(m.startedMs));
        if (m.endedMs >= 0) s += ", ended " + time.format(new java.util.Date(m.endedMs));
        return s;
    }

    /** Short words for a bug report. */
    public static String word(Why why) {
        return why.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
