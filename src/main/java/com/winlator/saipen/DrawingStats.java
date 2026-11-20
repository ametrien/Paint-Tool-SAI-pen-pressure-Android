package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * How much you draw: strokes and drawing time per day, for the card on SAI
 * Pen's screen. The counting is the macOS version's (StatsCore.swift), and
 * the file is in its format, so the numbers mean the same on both.
 *
 * Fed only when the pen goes down or up (SpeedSession.pen), never per
 * sample, so the pen path pays one comparison per sample. Saving happens on
 * the speed log's own thread, off the pen's path.
 *
 * Drawing time is the time the pen is down plus the pauses between strokes;
 * a pause over a minute is a break and is left out.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class DrawingStats {
    /** A pause longer than this between strokes is a break, not drawing. */
    static final double IDLE_GAP = 60;
    /** A single stroke counts for at most this long: a lost pen up must not
     *  book the whole evening. */
    static final double LONGEST_STROKE = 600;
    public static final String FILE = "saipen-drawing-stats.txt";
    static final String HEADER = "# day strokes pen-seconds active-seconds";

    /** One day of drawing. */
    public static final class Day {
        public int strokes;
        /** Time the pen was on the screen. */
        public double penSeconds;
        /** Pen time plus the short pauses between strokes. */
        public double activeSeconds;
    }

    private final TreeMap<String, Day> days;
    private double downAt = -1, lastUpAt = -1;
    /** The pause before the next stroke is not drawing (SAI closed between). */
    private boolean skipNextGap;
    private boolean dirty;

    public DrawingStats(Map<String, Day> days) {
        this.days = new TreeMap<>(days);
    }

    public synchronized void penDown(double t) {
        if (downAt < 0) downAt = t;
    }

    /** Ends the stroke and files it under the day it ended. t in seconds. */
    public synchronized boolean penUp(double t, LocalDate day) {
        if (downAt < 0) return false;
        double start = downAt;
        downAt = -1;
        /* a clock going backwards or a lost pen up must not book hours */
        double pen = Math.min(Math.max(0, t - start), LONGEST_STROKE);
        Day d = days.get(day.toString());
        if (d == null) days.put(day.toString(), d = new Day());
        d.strokes++;
        d.penSeconds += pen;
        double added = pen;
        if (lastUpAt >= 0 && !skipNextGap) {
            double gap = start - lastUpAt;
            if (gap > 0 && gap <= IDLE_GAP) added += gap;
        }
        d.activeSeconds += added;
        lastUpAt = t;
        skipNextGap = false;
        dirty = true;
        return true;
    }

    /** SAI closed mid-stroke: drop the open stroke, and the time away is not drawing. */
    public synchronized void cancel() {
        downAt = -1;
        skipNextGap = true;
    }

    public synchronized Map<String, Day> days() {
        TreeMap<String, Day> copy = new TreeMap<>();
        for (Map.Entry<String, Day> e : days.entrySet()) {
            Day d = new Day();
            d.strokes = e.getValue().strokes;
            d.penSeconds = e.getValue().penSeconds;
            d.activeSeconds = e.getValue().activeSeconds;
            copy.put(e.getKey(), d);
        }
        return copy;
    }

    /** Saves when something new was counted. Returns false only on a failed write. */
    public boolean saveIfChanged(File file) {
        String text;
        synchronized (this) {
            if (!dirty) return true;
            text = serialize(days);
            dirty = false;
        }
        if (write(file, text)) return true;
        synchronized (this) { dirty = true; }
        return false;
    }

    /* ---- the file ---- */

    static String serialize(Map<String, Day> days) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, Day> e : new TreeMap<>(days).entrySet()) {
            Day d = e.getValue();
            out.append(e.getKey()).append(' ').append(d.strokes).append(' ')
               .append(Math.round(d.penSeconds)).append(' ').append(Math.round(d.activeSeconds)).append('\n');
        }
        return out.toString();
    }

    /** What a file holds, and how many lines that should have been a day were not. */
    static final class Parsed {
        final TreeMap<String, Day> days = new TreeMap<>();
        int skipped;
    }

    /**
     * Unreadable lines are skipped, not fatal: one bad line must not wipe a
     * year of stats. Reads the macOS file too: 4, 8 or 32 fields a line,
     * the first four the same.
     */
    static Parsed parse(String text) {
        Parsed p = new Parsed();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(" +");
            try {
                if (f.length != 4 && f.length != 8 && f.length != 32) throw new IllegalArgumentException();
                LocalDate.parse(f[0]);
                Day d = new Day();
                d.strokes = Integer.parseInt(f[1]);
                d.penSeconds = Double.parseDouble(f[2]);
                d.activeSeconds = Math.max(Double.parseDouble(f[3]), d.penSeconds);
                if (d.strokes < 0 || d.penSeconds < 0) throw new IllegalArgumentException();
                p.days.put(f[0], d);
            }
            catch (RuntimeException e) {
                p.skipped++;
            }
        }
        return p;
    }

    /**
     * Reads the file; none is simply no stats yet. THE TRAP: the next save
     * writes what is in memory over the file, so a file with lines that do
     * not read would lose them for good. Such a file is copied aside first.
     */
    public static DrawingStats load(File file) {
        if (!file.isFile()) return new DrawingStats(new TreeMap<>());
        Parsed p;
        try {
            p = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            p = new Parsed();
            p.skipped = 1;
        }
        if (p.skipped > 0) {
            try {
                Files.copy(file.toPath(), new File(file.getPath() + ".damaged-" + System.currentTimeMillis()).toPath(),
                           StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException ignored) {}
        }
        return new DrawingStats(p.days);
    }

    /** Written next to the file and renamed over it: a cut-off write never leaves half a file. */
    static boolean write(File file, String text) {
        File tmp = new File(file.getPath() + ".new");
        try {
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        catch (IOException | RuntimeException e) {
            tmp.delete();
            return false;
        }
    }

    /* ---- what the card shows ---- */

    /** Added up over the last `count` days ending with today. */
    public static Day lastDays(Map<String, Day> days, LocalDate today, int count) {
        Day sum = new Day();
        for (int back = 0; back < count; back++) add(sum, days.get(today.minusDays(back).toString()));
        return sum;
    }

    public static Day total(Map<String, Day> days) {
        Day sum = new Day();
        for (Day d : days.values()) add(sum, d);
        return sum;
    }

    private static void add(Day sum, Day d) {
        if (d == null) return;
        sum.strokes += d.strokes;
        sum.penSeconds += d.penSeconds;
        sum.activeSeconds += d.activeSeconds;
    }

    /**
     * Days in a row with at least one stroke. A streak still counts while
     * today is empty, as long as yesterday was not: the day is not over.
     */
    public static int streak(Map<String, Day> days, LocalDate today) {
        int back = drew(days, today) ? 0 : 1;
        int n = 0;
        while (drew(days, today.minusDays(back))) {
            n++;
            back++;
        }
        return n;
    }

    private static boolean drew(Map<String, Day> days, LocalDate day) {
        Day d = days.get(day.toString());
        return d != null && d.strokes > 0;
    }
}
