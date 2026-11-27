package com.winlator.saipen;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * One drawing's statistics by day, made from its sittings (CanvasTime): the
 * days worked on it, the time and strokes of each, for the line under its
 * card and its own Statistics window.
 *
 * The days come out as DrawingStats.Day, so StatisticsDialog shows a drawing
 * as it shows all drawing: the same charts and calendar. Nothing new is
 * written anywhere: canvas-time.txt has kept the sittings since 0.5.0, and the
 * general stats file is not touched.
 *
 * Time counted before sittings were kept (early 0.5.0 builds) has no day; it
 * stays in the drawing's total and is shown apart ("earlier").
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class DrawingDays {
    private DrawingDays() {}

    /** The short line under a card: how many days, how long, the last time. */
    public static final class Summary {
        /** Days with at least one stroke, from the sittings. */
        public int days;
        public int strokes;
        /** All drawing time on it, the days' and the earlier one. */
        public double seconds;
        /** Time with no day: counted before sittings were kept. */
        public double earlierSeconds;
        /** The last stroke, wall clock ms; 0 when unknown. */
        public long last;
    }

    public static Summary summary(CanvasTime.Canvas c, ZoneId zone) {
        Summary s = new Summary();
        if (c == null) return s;
        Map<String, DrawingStats.Day> days = days(c, zone);
        double dated = 0;
        for (DrawingStats.Day d : days.values()) {
            if (d.strokes > 0) s.days++;
            dated += d.activeSeconds;
        }
        s.strokes = c.strokes;
        s.seconds = c.seconds;
        s.earlierSeconds = Math.max(0, c.seconds - dated);
        /* under a minute of rounding is not "earlier" time */
        if (s.earlierSeconds < 60) s.earlierSeconds = 0;
        s.last = c.last;
        return s;
    }

    /**
     * The drawing's days, keyed "2026-11-26" as DrawingStats' file. A sitting
     * goes to the day it began (CanvasTime splits them at midnight); its time
     * is spread evenly over its hours, which is as close as the sittings
     * know.
     */
    public static Map<String, DrawingStats.Day> days(CanvasTime.Canvas c, ZoneId zone) {
        TreeMap<String, DrawingStats.Day> out = new TreeMap<>();
        if (c == null) return out;
        for (CanvasTime.Session s : c.sessions) {
            if (s.strokes <= 0 || s.start <= 0) continue;
            ZonedDateTime start = Instant.ofEpochMilli(s.start).atZone(zone);
            ZonedDateTime end = Instant.ofEpochMilli(Math.max(s.start, s.end)).atZone(zone);
            LocalDate day = start.toLocalDate();
            /* an older sitting run past midnight: it ends with its day */
            if (!end.toLocalDate().equals(day)) end = day.plusDays(1).atStartOfDay(zone).minusNanos(1);
            DrawingStats.Day d = out.computeIfAbsent(day.toString(), k -> new DrawingStats.Day());
            double seconds = Math.max(0, s.seconds);
            d.strokes += s.strokes;
            d.activeSeconds += seconds;
            d.penSeconds += seconds;
            d.sessions++;
            d.longestSession = Math.max(d.longestSession, seconds);
            double from = clock(start), to = clock(end);
            d.firstAt = d.firstAt < 0 ? from : Math.min(d.firstAt, from);
            d.lastAt = Math.max(d.lastAt, to);
            if (d.hourly == null) d.hourly = new double[24];
            spread(d.hourly, from, to, seconds);
        }
        return out;
    }

    /** `seconds` laid over the hours from `from` to `to` (seconds after midnight). */
    static void spread(double[] hourly, double from, double to, double seconds) {
        double span = to - from;
        if (span <= 0) {
            hourly[DrawingStats.hour(from)] += seconds;
            return;
        }
        for (int h = DrawingStats.hour(from); h <= DrawingStats.hour(to); h++) {
            double a = Math.max(from, h * 3600.0), b = Math.min(to, (h + 1) * 3600.0);
            if (b > a) hourly[h] += seconds * (b - a) / span;
        }
    }

    private static double clock(ZonedDateTime t) {
        return t.getHour() * 3600 + t.getMinute() * 60 + t.getSecond() + t.getNano() / 1e9;
    }

    /** Every drawing's summary, by the key CanvasTime files it under. */
    public static Map<String, Summary> summaries(Map<String, CanvasTime.Canvas> all, ZoneId zone) {
        Map<String, Summary> out = new TreeMap<>();
        for (Map.Entry<String, CanvasTime.Canvas> e : all.entrySet()) {
            if (e.getValue().strokes > 0) out.put(e.getKey(), summary(e.getValue(), zone));
        }
        return out;
    }

    /**
     * One drawing's days as a spreadsheet, for Export CSV in its Statistics
     * window. Like DrawingStats.csv, one row a day with the same clock and
     * hour columns, but only what the sittings know: no pen-only time. The
     * drawing's name is the first column, so several exports can be pasted
     * into one sheet. Time counted before days were kept is a last row of its
     * own, with no date.
     */
    public static String csv(String drawing, Map<String, DrawingStats.Day> days, double earlierSeconds) {
        String name = quote(drawing == null ? "" : drawing);
        StringBuilder out = new StringBuilder("Drawing,Date,Weekday,Strokes,Drawing minutes,Sittings,"
            + "Longest sitting minutes,First stroke,Last stroke");
        for (int h = 0; h < 24; h++) out.append(",Minutes ").append(String.format(java.util.Locale.US, "%02d", h)).append(":00");
        out.append('\n');
        for (Map.Entry<String, DrawingStats.Day> e : new TreeMap<>(days).entrySet()) {
            DrawingStats.Day d = e.getValue();
            if (d.strokes <= 0) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(e.getKey());
            }
            catch (RuntimeException bad) {
                continue;
            }
            out.append(name).append(',').append(e.getKey()).append(',')
               .append(date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)).append(',')
               .append(d.strokes).append(',').append(DrawingStats.number(d.activeSeconds / 60)).append(',')
               .append(d.sessions).append(',').append(DrawingStats.number(d.longestSession / 60)).append(',')
               .append(DrawingStats.clock(d.firstAt)).append(',').append(DrawingStats.clock(d.lastAt));
            for (int h = 0; h < 24; h++) out.append(',').append(d.hourly == null ? "" : DrawingStats.number(d.hourly[h] / 60));
            out.append('\n');
        }
        if (earlierSeconds >= 60) {
            out.append(name).append(",,Before days were kept,,").append(DrawingStats.number(earlierSeconds / 60)).append(",,,,");
            for (int h = 0; h < 24; h++) out.append(',');
            out.append('\n');
        }
        return out.toString();
    }

    /** The file name the export suggests: "portrait.sai2" -> "portrait statistics.csv". */
    public static String csvName(String drawing) {
        String t = drawing == null ? "" : drawing.trim();
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        t = t.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return (t.isEmpty() ? "Drawing" : t) + " statistics.csv";
    }

    /** A CSV field: quoted when it holds a comma, a quote or a line break. */
    static String quote(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** The key of a drawing: its path under SAI Drawings, "/" between folders. */
    public static String key(String folder, String name) {
        return folder == null || folder.isEmpty() ? name : folder + "/" + name;
    }
}
