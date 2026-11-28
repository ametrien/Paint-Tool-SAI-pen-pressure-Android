package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The last crashes, newest first, for the end of "Report a problem": what
 * ended, when, and what is known about it (SAI's exit code, SAI Pen's own
 * error with where it happened, Android's reason). At most MAX are kept.
 *
 * Two files in the app's own folder:
 *   saipen-crashes.txt     the entries, each a headline and indented lines,
 *                          separated by a line "--"
 *   saipen-last-error.txt  SAI Pen's last uncaught Java error with its stack,
 *                          written as the app dies (SaiEnded.catchAppErrors)
 *                          and taken into an entry on the next start
 *
 * Nothing leaves the device: the person copies the report themself, and the
 * report passes every line through BugReport.safe (licence numbers, paths).
 *
 * No Android types: the JVM tests check the rules.
 */
public final class CrashLog {
    public static final String FILE = "saipen-crashes.txt", ERROR_FILE = "saipen-last-error.txt";
    public static final int MAX = 10;
    /** lines of a stack trace kept: enough to see where */
    static final int STACK_LINES = 14;
    private static final String SEPARATOR = "--";

    private CrashLog() {}

    /** The entries, newest first. */
    public static List<String> entries(File dir) {
        return split(readText(new File(dir, FILE)));
    }

    /** Adds an entry at the front; the oldest beyond MAX go. */
    public static synchronized void add(File dir, String entry) {
        if (entry == null || entry.trim().isEmpty()) return;
        File f = new File(dir, FILE);
        writeText(f, join(withEntry(split(readText(f)), entry)));
    }

    static List<String> withEntry(List<String> old, String entry) {
        List<String> out = new ArrayList<>();
        out.add(entry.trim());
        for (String e : old) {
            if (out.size() >= MAX) break;
            out.add(e);
        }
        return out;
    }

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.equals(SEPARATOR)) {
                if (cur.length() > 0) out.add(cur.toString().trim());
                cur.setLength(0);
            }
            else cur.append(line).append('\n');
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    static String join(List<String> entries) {
        StringBuilder b = new StringBuilder();
        for (String e : entries) b.append(e).append('\n').append(SEPARATOR).append('\n');
        return b.toString();
    }

    /** "2026-11-28 13:21:06" */
    public static String when(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }

    /** SAI Pen's error, as it dies: the time, the error, where it happened. */
    public static void saveError(File dir, Thread thread, Throwable e, long nowMs) {
        writeText(new File(dir, ERROR_FILE), errorText(thread != null ? thread.getName() : "?", e, nowMs));
    }

    static String errorText(String threadName, Throwable e, long nowMs) {
        StringBuilder b = new StringBuilder();
        b.append("at ").append(when(nowMs)).append(", thread ").append(threadName).append('\n');
        Throwable t = e;
        int lines = 0;
        for (int depth = 0; t != null && depth < 3; depth++, t = t.getCause()) {
            b.append(depth == 0 ? "" : "caused by ").append(SaiEnded.oneLine(String.valueOf(t))).append('\n');
            for (StackTraceElement s : t.getStackTrace()) {
                if (lines++ >= STACK_LINES) break;
                b.append("  at ").append(s).append('\n');
            }
        }
        return b.toString();
    }

    /** The saved error's lines (then the file is gone), or null. */
    public static String takeError(File dir) {
        File f = new File(dir, ERROR_FILE);
        String t = readText(f);
        f.delete();
        return t == null || t.trim().isEmpty() ? null : t.trim();
    }

    /** An entry: the headline, then each detail line indented. */
    public static String entry(String headline, String... details) {
        StringBuilder b = new StringBuilder(headline);
        for (String d : details) {
            if (d == null || d.trim().isEmpty()) continue;
            for (String l : d.trim().split("\n")) b.append("\n  ").append(l.trim());
        }
        return b.toString();
    }

    private static String readText(File f) {
        if (!f.isFile()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String text) {
        try {
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException ignored) {}
    }
}
