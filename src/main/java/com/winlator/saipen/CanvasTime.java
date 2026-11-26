package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Time spent on each drawing, for its page in All drawings: strokes, drawing
 * time (the pen on the glass plus pauses up to a minute, as DrawingStats
 * counts a day), and the first and last stroke.
 *
 * Which drawing a stroke is on comes from SAI's window title, which names the
 * open canvas: "PaintTool SAI Ver.2 (64bit) ... - My Computer /  (F:) /
 * comics / page.sai2 (*)". Drive F: is Documents/SAI Drawings, so the key is
 * "comics/page.sai2". A canvas never saved (or on another drive) has only its
 * name in the title; it is kept under ":" + the name ("/NewCanvas1"), and its
 * time moves to the file when SAI saves it under a new name: the title turns
 * from that name to a file within MERGE_MS of the file being written. A tab
 * switch changes the title too but writes no file, so it moves nothing.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class CanvasTime {
    public static final String FILE = "canvas-time.txt";
    static final String HEADER = "# SAI Pen time per drawing: key, strokes, seconds, first and last stroke (ms)";
    static final long MERGE_MS = 15_000;

    public static final class Canvas {
        public int strokes;
        public double seconds;
        /** Wall clock, milliseconds; 0 when unknown. */
        public long first, last;

        void add(Canvas o) {
            strokes += o.strokes;
            seconds += o.seconds;
            if (o.first > 0) first = first == 0 ? o.first : Math.min(first, o.first);
            last = Math.max(last, o.last);
        }
    }

    private final Map<String, Canvas> canvases = new TreeMap<>();
    private double downAt = -1, lastUpAt = -1;
    private String lastKey;
    private boolean dirty;
    /* the last title change from an unsaved canvas to a file, and the last file written */
    private String switchFrom, switchTo, savedName;
    private long switchAt = -1, savedAt = -1;

    /** The drawing SAI's title names, or null if it names none. */
    public static String key(String title) {
        if (title == null) return null;
        int dash = title.indexOf(" - ");
        if (dash < 0 || !SaiStartup.isSaiTitle(title.substring(0, dash))) return null;
        String rest = title.substring(dash + 3).trim();
        if (rest.endsWith("(*)")) rest = rest.substring(0, rest.length() - 3).trim();
        if (rest.isEmpty()) return null;
        int drive = rest.indexOf("(F:)");
        if (drive < 0) {
            if (rest.contains(":)")) return null;           /* another drive: not under SAI Drawings */
            return ":" + rest;
        }
        StringBuilder path = new StringBuilder();
        for (String part : rest.substring(drive + 4).split("/")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (path.length() > 0) path.append('/');
            path.append(p);
        }
        return path.length() == 0 ? null : path.toString();
    }

    public synchronized void penDown(double t) {
        if (downAt < 0) downAt = t;
    }

    /** Ends a stroke on `key` (null: no drawing known, nothing counted).
     *  t in seconds on a steady clock, wallMs the time of day. */
    public synchronized void penUp(double t, String key, long wallMs) {
        if (downAt < 0) return;
        double start = downAt;
        downAt = -1;
        if (key == null) {
            lastUpAt = -1;
            return;
        }
        double pen = Math.min(Math.max(0, t - start), DrawingStats.LONGEST_STROKE);
        double added = pen;
        if (lastUpAt >= 0 && key.equals(lastKey)) {
            double gap = start - lastUpAt;
            if (gap > 0 && gap <= DrawingStats.IDLE_GAP) added += gap;
        }
        Canvas c = canvases.computeIfAbsent(key, k -> new Canvas());
        c.strokes++;
        c.seconds += added;
        if (c.first == 0) c.first = wallMs;
        c.last = Math.max(c.last, wallMs);
        lastUpAt = t;
        lastKey = key;
        dirty = true;
    }

    /** SAI closed mid-stroke: drop it, and the time away is not drawing. */
    public synchronized void cancel() {
        downAt = -1;
        lastUpAt = -1;
    }

    /** SAI's title went from naming `from` to naming `to` (keys, may be null). */
    public synchronized void titleChanged(String from, String to, long nowMs) {
        if (from != null && from.startsWith(":") && to != null && !to.startsWith(":")) {
            switchFrom = from;
            switchTo = to;
            switchAt = nowMs;
            mergeIfSaved();
        }
    }

    /** A file was written under SAI Drawings: `path` relative to it, "/" between folders. */
    public synchronized void fileSaved(String path, long nowMs) {
        savedName = path;
        savedAt = nowMs;
        mergeIfSaved();
    }

    private void mergeIfSaved() {
        if (switchAt < 0 || savedAt < 0 || switchTo == null || !switchTo.equals(savedName)) return;
        if (Math.abs(switchAt - savedAt) > MERGE_MS) return;
        Canvas from = canvases.remove(switchFrom);
        if (from != null) {
            canvases.computeIfAbsent(switchTo, k -> new Canvas()).add(from);
            dirty = true;
        }
        if (switchFrom.equals(lastKey)) lastKey = switchTo;
        switchAt = savedAt = -1;
    }

    /** The time on the drawing at `path` (relative to SAI Drawings), or null. */
    public synchronized Canvas get(String path) {
        Canvas c = canvases.get(path);
        if (c == null) return null;
        Canvas copy = new Canvas();
        copy.add(c);
        return copy;
    }

    /* ---- the file ---- */

    public static CanvasTime load(File file) {
        CanvasTime t = new CanvasTime();
        try {
            if (file.isFile()) t.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException ignored) {
            /* a broken file: counting starts over, the stats of days are elsewhere */
        }
        return t;
    }

    synchronized void parse(String text) {
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length < 5) continue;
            try {
                Canvas c = new Canvas();
                c.strokes = Integer.parseInt(f[1]);
                c.seconds = Double.parseDouble(f[2]);
                c.first = Long.parseLong(f[3]);
                c.last = Long.parseLong(f[4]);
                canvases.put(f[0], c);
            }
            catch (NumberFormatException ignored) {
            }
        }
    }

    synchronized String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, Canvas> e : canvases.entrySet()) {
            Canvas c = e.getValue();
            out.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(c.strokes)
               .append('\t').append(String.format(Locale.ROOT, "%.0f", c.seconds))
               .append('\t').append(c.first).append('\t').append(c.last).append('\n');
        }
        return out.toString();
    }

    /** Saves when something new was counted. False only on a failed write. */
    public boolean saveIfChanged(File file) {
        String text;
        synchronized (this) {
            if (!dirty) return true;
            text = serialize();
            dirty = false;
        }
        if (DrawingStats.write(file, text)) return true;
        synchronized (this) { dirty = true; }
        return false;
    }
}
