package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * SAI's processor use now and then, for "Report a problem": a line at a
 * random moment every MIN_GAP_MS to MAX_GAP_MS of a session, a line when
 * SAI filled its cores (BusySai) and when that ended, and a line when the
 * session ends. With the speed log off there was nothing to look back at
 * after a freeze (2026-11-28).
 *
 * In the app's own folder, saipen-cpu.txt, at most MAX_LINES lines, the
 * newest last. Numbers and times only: nothing private.
 *
 * Percent is of one core, as in the speed log: 200% is two whole cores.
 *
 * No Android types: the JVM tests feed it numbers.
 */
public final class CpuSamples {
    public static final String FILE = "saipen-cpu.txt";
    public static final int MAX_LINES = 60;
    static final long MIN_GAP_MS = 60_000, MAX_GAP_MS = 300_000;

    private final File file;
    private final Random random;
    private final long startedAt;
    private final int cores;
    private long nextAt;
    /* since the last line */
    private int peak = -1, appPeak = -1;
    private long sum, appSum;
    private int count;
    /* the whole session */
    private int sessionPeak = -1;
    private long sessionSum;
    private int sessionCount;

    /**
     * @param cores cores' worth SAI can use (BusySai.cores()), said once at the start
     * @param setup the session's set-up in a few words ("SAI sees 2, fast cores: all")
     * @param clock "14:33", the wall clock as the session starts
     */
    public CpuSamples(File dir, Random random, long now, int cores, String day, String clock, String setup) {
        this.file = new File(dir, FILE);
        this.random = random;
        this.startedAt = now;
        this.cores = cores;
        this.nextAt = now + gap();
        append("session " + day + " " + clock + ": " + setup + ", all SAI can use " + cores * 100 + "%");
    }

    private long gap() {
        return MIN_GAP_MS + (long) (random.nextDouble() * (MAX_GAP_MS - MIN_GAP_MS));
    }

    /** Once a second; -1 when not known. clock: "14:39". */
    public void tick(long now, int saiPercent, int appPercent, String clock) {
        if (saiPercent >= 0) {
            peak = Math.max(peak, saiPercent);
            sum += saiPercent;
            count++;
            sessionPeak = Math.max(sessionPeak, saiPercent);
            sessionSum += saiPercent;
            sessionCount++;
        }
        if (appPercent >= 0) {
            appPeak = Math.max(appPeak, appPercent);
            appSum += appPercent;
        }
        if (now < nextAt) return;
        nextAt = now + gap();
        if (count == 0) return;
        append("  " + clock + ", " + minutes(now) + " in: SAI " + saiPercent + "% now, "
               + sum / count + "% on average and " + peak + "% at most since the last line"
               + (appPeak >= 0 ? "; all of SAI Pen " + appSum / count + "% on average" : ""));
        peak = appPeak = -1;
        sum = appSum = 0;
        count = 0;
    }

    /** A line of its own (BusySai's steps). */
    public void note(String clock, long now, String text) {
        append("  " + clock + ", " + minutes(now) + " in: " + text);
    }

    /** The session's last line. */
    public void finish(long now, String clock, long fullMs, long longestFullMs) {
        String s = "  " + clock + ", ended after " + minutes(now) + ": SAI "
                   + (sessionCount > 0 ? sessionSum / sessionCount + "% on average, " + sessionPeak + "% at most" : "not measured");
        if (fullMs >= 1000)
            s += ", all it can use for " + fullMs / 1000 + " s (longest " + longestFullMs / 1000 + " s in one go)";
        append(s);
    }

    private String minutes(long now) {
        long s = Math.max(0, now - startedAt) / 1000;
        return s < 60 ? s + " s" : String.format(Locale.ROOT, "%d min", s / 60);
    }

    private synchronized void append(String line) {
        List<String> lines = read(file);
        lines.add(line);
        if (lines.size() > MAX_LINES) lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
        try {
            Files.write(file.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException ignored) {}
    }

    /** The lines, oldest first (for the report). */
    public static List<String> lines(File dir) {
        return read(new File(dir, FILE));
    }

    private static List<String> read(File f) {
        if (!f.isFile()) return new ArrayList<>();
        try {
            List<String> out = new ArrayList<>(Arrays.asList(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")));
            out.removeIf(l -> l.trim().isEmpty());
            return out;
        }
        catch (IOException e) {
            return new ArrayList<>();
        }
    }
}
