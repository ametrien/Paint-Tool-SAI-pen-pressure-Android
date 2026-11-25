package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
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
    /** A longer break starts a new drawing session. */
    static final double SESSION_GAP = 600;
    public static final String FILE = "saipen-drawing-stats.txt";
    static final String HEADER = "# day strokes pen-seconds active-seconds sessions longest-session first-at last-at [24 hourly seconds] (-1 = unknown)";

    /** One day of drawing. */
    public static final class Day {
        public int strokes;
        /** Time the pen was on the screen. */
        public double penSeconds;
        /** Pen time plus the short pauses between strokes. */
        public double activeSeconds;
        /** Stretches of drawing with no break over {@link #SESSION_GAP}. */
        public int sessions;
        /** The longest drawing session, in drawing time. */
        public double longestSession;
        /** First and last stroke times, seconds after local midnight; -1 is unknown. */
        public double firstAt = -1, lastAt = -1;
        /** Drawing time by hour (0 to 23), or null for older entries. */
        public double[] hourly;
    }

    private final TreeMap<String, Day> days;
    private double downAt = -1, lastUpAt = -1;
    private String lastUpDay;
    /** The pause before the next stroke is not drawing (SAI closed between). */
    private boolean skipNextGap;
    private double sessionSoFar;
    private boolean dirty;

    public DrawingStats(Map<String, Day> days) {
        this.days = new TreeMap<>(days);
    }

    public synchronized void penDown(double t) {
        if (downAt < 0) downAt = t;
    }

    /** Ends the stroke and files it under the day it ended. t in seconds. */
    public synchronized boolean penUp(double t, LocalDate day) {
        return penUp(t, day, -1);
    }

    /** Ends a stroke. clock is seconds after local midnight, or -1 when unavailable. */
    public synchronized boolean penUp(double t, LocalDate day, double clock) {
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
        String key = day.toString();
        double gap = lastUpAt < 0 ? -1 : start - lastUpAt;
        boolean newSession = lastUpAt < 0 || gap > SESSION_GAP || !key.equals(lastUpDay);
        if (newSession) {
            d.sessions++;
            sessionSoFar = 0;
        }
        sessionSoFar += added;
        d.longestSession = Math.max(d.longestSession, sessionSoFar);
        if (clock >= 0) {
            double began = Math.max(0, clock - pen);
            d.firstAt = d.firstAt < 0 ? began : Math.min(d.firstAt, began);
            d.lastAt = Math.max(d.lastAt, clock);
            if (d.hourly == null) d.hourly = new double[24];
            d.hourly[hour(clock)] += added;
        }
        lastUpAt = t;
        lastUpDay = key;
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
            d.sessions = e.getValue().sessions;
            d.longestSession = e.getValue().longestSession;
            d.firstAt = e.getValue().firstAt;
            d.lastAt = e.getValue().lastAt;
            d.hourly = e.getValue().hourly == null ? null : e.getValue().hourly.clone();
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
               .append(Math.round(d.penSeconds)).append(' ').append(Math.round(d.activeSeconds)).append(' ')
               .append(d.sessions).append(' ').append(Math.round(d.longestSession)).append(' ')
               .append(Math.round(d.firstAt)).append(' ').append(Math.round(d.lastAt));
            if (d.hourly != null && d.hourly.length == 24) {
                for (double h : d.hourly) out.append(' ').append(Math.round(h));
            }
            out.append('\n');
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
                if (f.length >= 8) {
                    d.sessions = Math.max(0, Integer.parseInt(f[4]));
                    d.longestSession = Math.max(0, Double.parseDouble(f[5]));
                    d.firstAt = Double.parseDouble(f[6]);
                    d.lastAt = Double.parseDouble(f[7]);
                }
                if (f.length == 32) {
                    d.hourly = new double[24];
                    for (int i = 0; i < 24; i++) {
                        d.hourly[i] = Double.parseDouble(f[8 + i]);
                        if (d.hourly[i] < 0) throw new IllegalArgumentException();
                    }
                }
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

    /**
     * How many days from `from` to `to` counting could see. Days before the
     * first day counted are not days off: a year's calendar started on 20
     * November reads "2 of 2", not "2 of 325". With nothing counted yet,
     * counting starts today. A range wholly before counting began has none.
     */
    public static int countedDays(Map<String, Day> days, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate first = today;
        for (String key : days.keySet()) {
            try {
                LocalDate d = LocalDate.parse(key);
                if (d.isBefore(first)) first = d;
            }
            catch (RuntimeException ignored) {}
        }
        LocalDate start = from.isBefore(first) ? first : from;
        return start.isAfter(to) ? 0 : (int) (to.toEpochDay() - start.toEpochDay() + 1);
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
        sum.sessions += d.sessions;
        sum.longestSession = Math.max(sum.longestSession, d.longestSession);
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

    static int hour(double secondsAfterMidnight) {
        return Math.max(0, Math.min(23, (int) (secondsAfterMidnight / 3600)));
    }

    /** The same spreadsheet export as the macOS Statistics tab, oldest day first. */
    public static String csv(Map<String, Day> days) {
        StringBuilder out = new StringBuilder("Date,Weekday,Strokes,Drawing minutes,Pen minutes,"
            + "Average stroke seconds,Strokes per drawing minute,Sessions,Longest session minutes,"
            + "First stroke,Last stroke");
        for (int h = 0; h < 24; h++) out.append(",Minutes ").append(String.format(Locale.US, "%02d", h)).append(":00");
        out.append('\n');
        for (Map.Entry<String, Day> e : new TreeMap<>(days).entrySet()) {
            Day d = e.getValue();
            LocalDate date = LocalDate.parse(e.getKey());
            out.append(e.getKey()).append(',')
               .append(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US)).append(',')
               .append(d.strokes).append(',').append(number(d.activeSeconds / 60)).append(',')
               .append(number(d.penSeconds / 60)).append(',')
               .append(d.strokes == 0 ? "" : number(d.penSeconds / d.strokes)).append(',')
               .append(d.activeSeconds == 0 ? "" : number(d.strokes * 60 / d.activeSeconds)).append(',')
               .append(d.sessions == 0 ? "" : d.sessions).append(',')
               .append(d.sessions == 0 ? "" : number(d.longestSession / 60)).append(',')
               .append(clock(d.firstAt)).append(',').append(clock(d.lastAt));
            for (int h = 0; h < 24; h++) out.append(',').append(d.hourly == null ? "" : number(d.hourly[h] / 60));
            out.append('\n');
        }
        return out.toString();
    }

    private static String number(double x) {
        return String.format(Locale.US, "%.1f", x);
    }

    private static String clock(double seconds) {
        if (seconds < 0) return "";
        int s = (int) Math.round(seconds);
        return String.format(Locale.US, "%02d:%02d", (s / 3600) % 24, (s / 60) % 60);
    }
}
