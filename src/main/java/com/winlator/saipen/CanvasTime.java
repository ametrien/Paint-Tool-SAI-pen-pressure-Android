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
 * Renames (2026-11-27, the owner: "what if we rename the file?"):
 * - Save As of a drawing already saved ("art.sai2" to "art-v2.sai2"): the
 *   title turns to a name not seen in SAI's title before in this SAI start,
 *   and that file is written within MERGE_MS. The new file gets a COPY of the
 *   history: the old file is still there, and its time stays true too. A
 *   switch to a document open before (and Ctrl+S on it) copies nothing: its
 *   name was in the title already.
 * - A rename or move in a file manager, with SAI closed: the file keeps its
 *   size and its time of change. follow() is given the files under SAI
 *   Drawings now; a drawing whose file is gone moves to the one file with no
 *   time of its own that has exactly the size and time its file last had.
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
        /** Each sitting on this drawing, oldest first: its statistics by
         *  day (DrawingDays). Empty for time counted before sessions were
         *  kept. A sitting never spans midnight: it is split there, as
         *  DrawingStats starts a new session on a new day. */
        public final java.util.List<Session> sessions = new java.util.ArrayList<>();

        void add(Canvas o) {
            strokes += o.strokes;
            seconds += o.seconds;
            if (o.first > 0) first = first == 0 ? o.first : Math.min(first, o.first);
            last = Math.max(last, o.last);
            for (Session s : o.sessions) sessions.add(s.copy());
            sessions.sort((a, b) -> Long.compare(a.start, b.start));
        }
    }

    /** One sitting on a drawing: from its first to its last stroke, with no
     *  break over DrawingStats.SESSION_GAP and no SAI restart between. */
    public static final class Session {
        /** Wall clock, milliseconds. */
        public long start, end;
        public int strokes;
        public double seconds;

        Session copy() {
            Session s = new Session();
            s.start = start;
            s.end = end;
            s.strokes = strokes;
            s.seconds = seconds;
            return s;
        }
    }

    private final Map<String, Canvas> canvases = new TreeMap<>();
    /** The size and time of change each drawing's file had when last seen
     *  (follow): how a file renamed in a file manager is found again. */
    private final Map<String, long[]> marks = new TreeMap<>();
    /** Keys SAI's title named in this SAI start: a Save As goes to a new one. */
    private final java.util.Set<String> seenKeys = new java.util.HashSet<>();
    /** The pending title change is a Save As of a saved drawing: copy, not move. */
    private boolean copySwitch;

    /** A new canvas SAI saved under a name: "NewCanvas1" became "hand.sai2"
     *  at `atMs`. The timelapse packer (TimelapseVideo) files a session of
     *  "NewCanvas1" under the name it got, when the DLL's frames never saw it. */
    public static final class Rename {
        public final String from, to;
        public final long atMs;

        Rename(String from, String to, long atMs) {
            this.from = from;
            this.to = to;
            this.atMs = atMs;
        }
    }

    private final java.util.List<Rename> renames = new java.util.ArrayList<>();
    static final int KEEP_RENAMES = 200;
    private double downAt = -1, lastUpAt = -1;
    private String lastKey;
    /** SAI was closed (cancel): the next stroke on any drawing opens a session. */
    private boolean freshStart = true;
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
        Session s = c.sessions.isEmpty() ? null : c.sessions.get(c.sessions.size() - 1);
        if (s == null || freshStart || wallMs - s.end > DrawingStats.SESSION_GAP * 1000
            || !sameDay(s.start, wallMs)) {
            s = new Session();
            s.start = wallMs;
            c.sessions.add(s);
        }
        s.end = Math.max(s.end, wallMs);
        s.strokes++;
        s.seconds += added;
        freshStart = false;
        lastUpAt = t;
        lastKey = key;
        dirty = true;
    }

    /** Same local calendar day: a sitting past midnight goes on as a new one,
     *  so each day of a drawing is its own. */
    static boolean sameDay(long aMs, long bMs) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        return java.time.Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate()
            .equals(java.time.Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate());
    }

    /** SAI closed mid-stroke: drop it, and the time away is not drawing. */
    public synchronized void cancel() {
        downAt = -1;
        lastUpAt = -1;
        freshStart = true;
    }

    /** SAI's title went from naming `from` to naming `to` (keys, may be null). */
    public synchronized void titleChanged(String from, String to, long nowMs) {
        boolean fresh = to != null && !seenKeys.contains(to);
        /* on to another drawing: what waited for a save is over */
        if (from != null && to != null && !from.equals(to)) switchAt = -1;
        if (from != null) seenKeys.add(from);
        if (to != null) seenKeys.add(to);
        if (from != null && from.startsWith(":") && to != null && !to.startsWith(":")) {
            switchFrom = from;
            switchTo = to;
            switchAt = nowMs;
            copySwitch = false;
            mergeIfSaved();
        }
        else if (from != null && !from.startsWith(":") && to != null && !to.startsWith(":")
                 && !from.equals(to) && fresh) {
            switchFrom = from;
            switchTo = to;
            switchAt = nowMs;
            copySwitch = true;
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
        if (copySwitch) {
            /* Save As of a saved drawing: both files keep the history */
            Canvas from = canvases.get(switchFrom);
            if (from != null) {
                Canvas to = canvases.computeIfAbsent(switchTo, k -> new Canvas());
                Canvas copy = new Canvas();
                copy.add(from);
                to.add(copy);
                dirty = true;
            }
            if (switchFrom.equals(lastKey)) lastKey = switchTo;
            switchAt = savedAt = -1;
            return;
        }
        Canvas from = canvases.remove(switchFrom);
        if (from != null) canvases.computeIfAbsent(switchTo, k -> new Canvas()).add(from);
        renames.add(new Rename(switchFrom.substring(1), switchTo, savedAt));
        while (renames.size() > KEEP_RENAMES) renames.remove(0);
        dirty = true;
        if (switchFrom.equals(lastKey)) lastKey = switchTo;
        switchAt = savedAt = -1;
    }

    /** The name a new canvas called `unsaved` ("NewCanvas1") was saved
     *  under, the last time it happened at or after `sinceMs`; null if none.
     *  The file's name only, its folder left out, as SAI's canvas title. */
    public synchronized String savedAs(String unsaved, long sinceMs) {
        Rename best = null;
        for (Rename r : renames) {
            if (!r.from.equals(unsaved) || r.atMs < sinceMs) continue;
            if (best == null || r.atMs > best.atMs) best = r;
        }
        if (best == null) return null;
        int slash = best.to.lastIndexOf('/');
        return slash < 0 ? best.to : best.to.substring(slash + 1);
    }

    /**
     * The files under SAI Drawings now, by key ("comics/page.sai2"), each
     * {size, time of change}. A drawing whose file is gone moves to the one
     * file that has no time of its own and exactly the size and time its own
     * file had when last seen: renamed or moved in a file manager. Then every
     * drawing's file is noted as it is now. Call it with SAI closed (its
     * running count would write over the move). True if anything changed.
     */
    public synchronized boolean follow(Map<String, long[]> files) {
        return follow(files, null);
    }

    /** As above; each move made goes into `moved` (old key to new), if given. */
    public synchronized boolean follow(Map<String, long[]> files, Map<String, String> moved) {
        boolean changed = false;
        for (String k : new java.util.ArrayList<>(canvases.keySet())) {
            if (k.startsWith(":") || files.containsKey(k)) continue;
            long[] m = marks.get(k);
            if (m == null) continue;
            String hit = null;
            int hits = 0;
            for (Map.Entry<String, long[]> f : files.entrySet()) {
                long[] v = f.getValue();
                if (canvases.containsKey(f.getKey()) || v == null || v.length < 2) continue;
                if (v[0] == m[0] && v[1] == m[1]) { hit = f.getKey(); hits++; }
            }
            if (hits != 1) continue;
            canvases.put(hit, canvases.remove(k));
            marks.remove(k);
            if (k.equals(lastKey)) lastKey = hit;
            if (moved != null) moved.put(k, hit);
            changed = true;
        }
        for (String k : canvases.keySet()) {
            long[] v = files.get(k);
            if (v == null || v.length < 2) continue;
            long[] m = marks.get(k);
            if (m == null || m[0] != v[0] || m[1] != v[1]) {
                marks.put(k, new long[] {v[0], v[1]});
                changed = true;
            }
        }
        if (marks.keySet().retainAll(canvases.keySet())) changed = true;
        if (changed) dirty = true;
        return changed;
    }

    /** "Find its file" in All drawings: the time on `from`, whose file is
     *  gone, goes to `to` (added to any time `to` has). With SAI closed. */
    public synchronized boolean move(String from, String to) {
        if (from.equals(to)) return false;
        Canvas c = canvases.remove(from);
        if (c == null) return false;
        Canvas there = canvases.get(to);
        if (there == null) canvases.put(to, c);
        else there.add(c);
        marks.remove(from);
        if (from.equals(lastKey)) lastKey = to;
        dirty = true;
        return true;
    }

    /** "Forget it": the time on a drawing whose file is gone. With SAI closed. */
    public synchronized boolean remove(String key) {
        boolean had = canvases.remove(key) != null;
        marks.remove(key);
        if (had) dirty = true;
        return had;
    }

    /** The time on the drawing at `path` (relative to SAI Drawings), or null. */
    public synchronized Canvas get(String path) {
        Canvas c = canvases.get(path);
        if (c == null) return null;
        Canvas copy = new Canvas();
        copy.add(c);
        return copy;
    }

    /** Every drawing's time, copied, by key; for the cards of All drawings. */
    public synchronized Map<String, Canvas> all() {
        Map<String, Canvas> out = new TreeMap<>();
        for (Map.Entry<String, Canvas> e : canvases.entrySet()) {
            Canvas copy = new Canvas();
            copy.add(e.getValue());
            out.put(e.getKey(), copy);
        }
        return out;
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
            if (f[0].equals("@session")) {
                if (f.length >= 6) {
                    try {
                        Session s = new Session();
                        s.start = Long.parseLong(f[2]);
                        s.end = Long.parseLong(f[3]);
                        s.strokes = Integer.parseInt(f[4]);
                        s.seconds = Double.parseDouble(f[5]);
                        canvases.computeIfAbsent(f[1], k -> new Canvas()).sessions.add(s);
                    }
                    catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            if (f[0].equals("@file")) {
                if (f.length >= 4) {
                    try {
                        marks.put(f[1], new long[] {Long.parseLong(f[2]), Long.parseLong(f[3])});
                    }
                    catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            if (f[0].equals("@rename")) {
                if (f.length >= 4) {
                    try {
                        renames.add(new Rename(f[1], f[2], Long.parseLong(f[3])));
                    }
                    catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            if (f.length < 5) continue;
            try {
                Canvas c = canvases.computeIfAbsent(f[0], k -> new Canvas());
                c.strokes = Integer.parseInt(f[1]);
                c.seconds = Double.parseDouble(f[2]);
                c.first = Long.parseLong(f[3]);
                c.last = Long.parseLong(f[4]);
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
        for (Map.Entry<String, Canvas> e : canvases.entrySet()) {
            String k = e.getKey().replace('\t', ' ').replace('\n', ' ');
            for (Session s : e.getValue().sessions) {
                out.append("@session\t").append(k).append('\t').append(s.start).append('\t').append(s.end)
                   .append('\t').append(s.strokes).append('\t').append(String.format(Locale.ROOT, "%.0f", s.seconds))
                   .append('\n');
            }
        }
        /* four fields: an older SAI Pen skips lines shorter than five */
        for (Map.Entry<String, long[]> e : marks.entrySet()) {
            out.append("@file\t").append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t')
               .append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        }
        for (Rename r : renames) {
            out.append("@rename\t").append(r.from.replace('\t', ' ')).append('\t')
               .append(r.to.replace('\t', ' ')).append('\t').append(r.atMs).append('\n');
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
