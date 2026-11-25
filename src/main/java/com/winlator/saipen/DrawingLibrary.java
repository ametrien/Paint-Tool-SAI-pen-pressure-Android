package com.winlator.saipen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which drawing a timelapse session belongs to, across SAI starts: the Mac
 * app's LibraryCore.swift (timelapse-encoder/), ported rule for rule, with its
 * thresholds and its traps. Its tests (tests/LibraryTests.swift) are ported
 * too, in DrawingLibraryTest.
 *
 * The canvas address only names a canvas while SAI runs; the title is
 * "NewCanvas1" for every unsaved drawing and changes on a rename. What
 * survives is what the drawing LOOKS like: a reopened drawing looks exactly as
 * it was left. So a session's first frame is compared with the last frame of
 * each drawing, on a 16 x 16 grey fingerprint.
 *
 * The ladder is lopsided on purpose: two drawings wrongly welded into one
 * video is far worse than two entries for one drawing. It merges on strong
 * evidence, asks on plausible evidence, and keeps them apart otherwise.
 *
 * No Android types: the JVM tests check it.
 */
public final class DrawingLibrary {

    /* ---- fingerprints ---- */

    public static final class Signature {
        public static final int SIDE = 16;
        /** Below this, a fingerprint is blank paper and proves nothing. */
        public static final double DETAIL_FLOOR = 0.04;

        public final byte[] cells;          /* SIDE * SIDE grey values */
        public final int width, height;     /* for display; not used to match */

        public Signature(byte[] cells, int width, int height) {
            this.cells = cells;
            this.width = width;
            this.height = height;
        }

        public boolean isValid() {
            return cells != null && cells.length == SIDE * SIDE;
        }

        /** Box average of a BGRA frame into the grid. Averaging, not
         *  sampling: a stroke moves an average a little, a sample all or
         *  nothing. Null for a buffer that does not hold the stated size. */
        public static Signature of(byte[] bgra, int width, int height, int stride) {
            if (bgra == null || width <= 0 || height <= 0 || stride < width * 4
                || bgra.length < (long) stride * height) return null;
            byte[] cells = new byte[SIDE * SIDE];
            for (int gy = 0; gy < SIDE; gy++) {
                int y0 = height * gy / SIDE, y1 = Math.max(y0 + 1, height * (gy + 1) / SIDE);
                for (int gx = 0; gx < SIDE; gx++) {
                    int x0 = width * gx / SIDE, x1 = Math.max(x0 + 1, width * (gx + 1) / SIDE);
                    long sum = 0, n = 0;
                    for (int y = y0; y < Math.min(y1, height); y++) {
                        int row = y * stride;
                        for (int x = x0; x < Math.min(x1, width); x++) {
                            int p = row + x * 4;
                            sum += ((bgra[p] & 0xff) + 2 * (bgra[p + 1] & 0xff) + (bgra[p + 2] & 0xff)) / 4;
                            n++;
                        }
                    }
                    cells[gy * SIDE + gx] = (byte) (n > 0 ? Math.min(255, sum / n) : 0);
                }
            }
            return new Signature(cells, width, height);
        }

        /** How much the fingerprint varies, 0..1. Two new canvases are both
         *  white and match each other perfectly: content is evidence only
         *  when there is content. */
        public double detail() {
            if (!isValid()) return 0;
            double mean = 0;
            for (byte c : cells) mean += c & 0xff;
            mean /= cells.length;
            double var = 0;
            for (byte c : cells) var += Math.pow((c & 0xff) - mean, 2);
            var /= cells.length;
            return Math.min(1, Math.sqrt(var) / 64);
        }

        public boolean isFeatureless() {
            return detail() < DETAIL_FLOOR;
        }

        /** Mean absolute difference, 0 (same) .. 1. Needed, not enough: two
         *  canvases of one stroke each, in different places, are close. */
        public static double distance(Signature a, Signature b) {
            if (a == null || b == null || !a.isValid() || !b.isValid()) return 1;
            long total = 0;
            for (int i = 0; i < a.cells.length; i++) total += Math.abs((a.cells[i] & 0xff) - (b.cells[i] & 0xff));
            return total / (double) (a.cells.length * 255);
        }

        /** Correlation, -1 .. 1: is the ink in the SAME PLACES. This decides.
         *  0 for a featureless one, not a false +1. */
        public static double similarity(Signature a, Signature b) {
            if (a == null || b == null || !a.isValid() || !b.isValid()) return 0;
            int n = a.cells.length;
            double mx = 0, my = 0;
            for (int i = 0; i < n; i++) {
                mx += a.cells[i] & 0xff;
                my += b.cells[i] & 0xff;
            }
            mx /= n;
            my /= n;
            double num = 0, dx = 0, dy = 0;
            for (int i = 0; i < n; i++) {
                double p = (a.cells[i] & 0xff) - mx, q = (b.cells[i] & 0xff) - my;
                num += p * q;
                dx += p * p;
                dy += q * q;
            }
            if (dx <= 0 || dy <= 0) return 0;
            return num / Math.sqrt(dx * dy);
        }

        String hex() {
            StringBuilder sb = new StringBuilder(cells.length * 2);
            for (byte c : cells) sb.append(String.format(Locale.ROOT, "%02x", c & 0xff));
            return sb.toString();
        }

        static Signature fromHex(String hex, int width, int height) {
            if (hex == null || hex.length() != SIDE * SIDE * 2) return null;
            byte[] cells = new byte[SIDE * SIDE];
            for (int i = 0; i < cells.length; i++) cells[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            return new Signature(cells, width, height);
        }
    }

    /* ---- the model ---- */

    /** One SAI session of one drawing: its own video. */
    public static final class Piece {
        public String file;          /* name only: pieces may move between drawings */
        public final long startedAt; /* ms since 1970 */
        public final int frames, width, height;
        public final Signature opening, closing;

        public Piece(String file, long startedAt, int frames, int width, int height, Signature opening, Signature closing) {
            this.file = file;
            this.startedAt = startedAt;
            this.frames = frames;
            this.width = width;
            this.height = height;
            this.opening = opening;
            this.closing = closing;
        }
    }

    /** One drawing, across however many evenings. */
    public static final class Drawing {
        public final String id;
        public String title;          /* a label: renaming re-identifies nothing */
        public final String folder;
        public final List<Piece> pieces = new ArrayList<>();

        Drawing(String id, String title, String folder) {
            this.id = id;
            this.title = title;
            this.folder = folder;
        }

        /** In capture order: a session filed late goes in the middle. */
        public List<Piece> ordered() {
            List<Piece> l = new ArrayList<>(pieces);
            Collections.sort(l, (a, b) -> Long.compare(a.startedAt, b.startedAt));
            return l;
        }

        /** The canvas as this drawing was last put down. */
        public Signature closing() {
            List<Piece> l = ordered();
            return l.isEmpty() ? null : l.get(l.size() - 1).closing;
        }

        public int totalFrames() {
            int n = 0;
            for (Piece p : pieces) n += p.frames;
            return n;
        }
    }

    /** A session that may continue a drawing: filed as its own drawing
     *  until somebody says otherwise. Ignoring it forever is fine. */
    public static final class Pending {
        public final String pieceFile, drawingId, candidateId;

        Pending(String pieceFile, String drawingId, String candidateId) {
            this.pieceFile = pieceFile;
            this.drawingId = drawingId;
            this.candidateId = candidateId;
        }
    }

    public final List<Drawing> drawings = new ArrayList<>();
    public final List<Pending> pending = new ArrayList<>();
    /** "piece|drawing" pairs already answered no: never asked twice. */
    public final Set<String> declined = new HashSet<>();

    public Drawing drawing(String id) {
        for (Drawing d : drawings) if (d.id.equals(id)) return d;
        return null;
    }

    public boolean hasDeclined(String piece, String drawingId) {
        return declined.contains(piece + "|" + drawingId);
    }

    public void decline(String piece, String drawingId) {
        declined.add(piece + "|" + drawingId);
    }

    /* ---- the ladder ---- */

    public enum Kind { SAME, ASK, NEW }

    public static final class Match {
        public final Kind kind;
        public final String drawingId;
        public final double distance;

        Match(Kind kind, String drawingId, double distance) {
            this.kind = kind;
            this.drawingId = drawingId;
            this.distance = distance;
        }

        static final Match NEW = new Match(Kind.NEW, null, 1);

        @Override public String toString() {
            return kind + (drawingId == null ? "" : " " + drawingId);
        }
    }

    /** Below this distance, and with SAME_SIMILARITY, the same drawing
     *  continued (reopened untouched 0.000, halved 0.013). */
    public static final double SAME_DISTANCE = 0.03;
    /** The correlation that merges without asking. */
    public static final double SAME_SIMILARITY = 0.90;
    /** The correlation worth a question. */
    public static final double ASK_SIMILARITY = 0.55;
    /** Past this distance nothing resembles what was left. */
    public static final double ASK_DISTANCE = 0.20;

    /** SAI names every new document NewCanvas1, NewCanvas2: no evidence. */
    public static boolean isDefaultTitle(String t) {
        if (t == null || !t.startsWith("NewCanvas")) return false;
        String rest = t.substring("NewCanvas".length());
        for (int i = 0; i < rest.length(); i++) if (!Character.isDigit(rest.charAt(i))) return false;
        return true;
    }

    /** Which drawing, if any, a session that OPENS like this belongs to. */
    public Match classify(Signature opening, String title, String pieceFile) {
        if (opening == null || !opening.isValid() || opening.isFeatureless()) return Match.NEW;

        Drawing best = null;
        double bestSim = 0, bestDist = 1;
        for (Drawing d : drawings) {
            Signature c = d.closing();
            if (c == null || !c.isValid() || c.isFeatureless()) continue;
            double sim = Signature.similarity(opening, c), dist = Signature.distance(opening, c);
            if (best == null || sim > bestSim) {
                best = d;
                bestSim = sim;
                bestDist = dist;
            }
        }
        if (best == null) return Match.NEW;

        if (bestSim >= SAME_SIMILARITY && bestDist <= SAME_DISTANCE) return new Match(Kind.SAME, best.id, bestDist);

        Signature bc = best.closing();
        boolean titleAgrees = title != null && !title.isEmpty() && !isDefaultTitle(title) && title.equals(best.title)
            && opening.width == bc.width && opening.height == bc.height;
        boolean plausible = bestSim >= ASK_SIMILARITY && bestDist <= ASK_DISTANCE;
        if (plausible || titleAgrees) {
            if (hasDeclined(pieceFile, best.id)) return Match.NEW;
            return new Match(Kind.ASK, best.id, bestDist);
        }
        return Match.NEW;
    }

    /* ---- filing ---- */

    /** Files a new piece: into its drawing on SAME, into a drawing of its
     *  own otherwise (with a question waiting on ASK). Returns the drawing. */
    public Drawing file(Piece piece, String title) {
        Match m = classify(piece.opening, title, piece.file);
        if (m.kind == Kind.SAME) {
            Drawing d = drawing(m.drawingId);
            attach(piece, d);
            if (title != null && !title.isEmpty() && !isDefaultTitle(title)) d.title = title;
            return d;
        }
        Drawing d = addDrawing(title, piece);
        if (m.kind == Kind.ASK) pending.add(new Pending(piece.file, d.id, m.drawingId));
        return d;
    }

    public Drawing addDrawing(String title, Piece piece) {
        Set<String> folders = new HashSet<>();
        for (Drawing d : drawings) folders.add(d.folder);
        String id = newId(piece.startedAt);
        Drawing d = new Drawing(id, title == null ? "" : title, folderName(title, piece.startedAt, folders));
        d.pieces.add(piece);
        drawings.add(d);
        return d;
    }

    private String newId(long startedAt) {
        Set<String> ids = new HashSet<>();
        for (Drawing d : drawings) ids.add(d.id);
        for (int salt = 0; ; salt++) {
            String id = String.format(Locale.ROOT, "d%08x%02x", (int) (startedAt / 1000), salt & 0xff);
            if (!ids.contains(id) || salt > 0xffff) return salt > 0xff ? id + salt : id;
        }
    }

    /** Append only: never rewrites a piece. */
    public void attach(Piece piece, Drawing d) {
        if (d == null) return;
        for (Piece p : d.pieces) if (p.file.equals(piece.file)) return;
        d.pieces.add(piece);
    }

    /** "Yes, it continues that drawing": the waiting piece moves there. */
    public Drawing accept(Pending p) {
        pending.remove(p);
        Drawing from = drawing(p.drawingId), to = drawing(p.candidateId);
        if (from == null || to == null) return from;
        Piece piece = null;
        for (Piece x : from.pieces) if (x.file.equals(p.pieceFile)) piece = x;
        if (piece == null) return from;
        from.pieces.remove(piece);
        /* a name the drawing already has would make attach drop the piece:
         * a whole session lost */
        Set<String> taken = new HashSet<>();
        for (Piece x : to.pieces) taken.add(x.file);
        if (taken.contains(piece.file)) piece.file = pieceName(piece.startedAt, taken);
        attach(piece, to);
        if (from.pieces.isEmpty()) drawings.remove(from);
        return to;
    }

    /** "No, something else": remembered, never asked again. */
    public void refuse(Pending p) {
        pending.remove(p);
        decline(p.pieceFile, p.candidateId);
    }

    /** The piece `accept` would move, or null. */
    public Piece pendingPiece(Pending p) {
        Drawing from = drawing(p.drawingId);
        if (from == null) return null;
        for (Piece x : from.pieces) if (x.file.equals(p.pieceFile)) return x;
        return null;
    }

    /** The question about a piece just filed, not an old one: asking an
     *  hours-old question after a new video confused the Mac's owner. */
    public Pending questionAbout(List<String> justFiled) {
        for (Pending p : pending) if (justFiled.contains(p.pieceFile)) return p;
        return null;
    }

    /* ---- naming ---- */

    public static String stamp(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(new Date(time));
    }

    /** "NewCanvas1 2026-07-30 1357": the date says which drawing it is when
     *  the title cannot. */
    public static String folderName(String title, long startedAt, Set<String> taken) {
        String stem = folderStem(title) + " " + stamp(startedAt);
        if (!taken.contains(stem)) return stem;
        int n = 2;
        while (taken.contains(stem + " " + n)) n++;
        return stem + " " + n;
    }

    public static String folderStem(String title) {
        String s = title == null ? "" : title.replaceAll("[/\\\\:?%*|\"<>\\p{Cntrl}]", "").trim();
        if (s.replace(".", "").isEmpty()) s = "";
        if (s.isEmpty()) s = "Drawing";
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
    }

    /** One session's file, unique in its folder: a second session in the
     *  same minute must not overwrite the first. */
    public static String pieceName(long startedAt, Set<String> taken) {
        String base = stamp(startedAt);
        if (!taken.contains(base + ".mp4")) return base + ".mp4";
        int n = 2;
        while (taken.contains(base + " (" + n + ").mp4")) n++;
        return base + " (" + n + ").mp4";
    }

    /* ---- one video per drawing ---- */

    /**
     * The drawing's pieces, in capture order, cut where the size changes: a
     * video has one size, so pieces join without re-encoding only within a
     * run of one size. Nearly always one run.
     */
    public static List<List<Piece>> sizeRuns(Drawing d) {
        List<List<Piece>> runs = new ArrayList<>();
        List<Piece> cur = null;
        for (Piece p : d.ordered()) {
            if (cur == null || cur.get(0).width != p.width || cur.get(0).height != p.height) {
                cur = new ArrayList<>();
                runs.add(cur);
            }
            cur.add(p);
        }
        return runs;
    }

    /** The joined video's name: the drawing's folder, "(2)" for its second
     *  size. */
    public static String joinedName(Drawing d, int run) {
        return d.folder + (run > 0 ? " (" + (run + 1) + ")" : "") + ".mp4";
    }

    /** Newest drawing first, for the timelapse window. */
    public List<Drawing> newestFirst() {
        List<Drawing> l = new ArrayList<>(drawings);
        Collections.sort(l, (a, b) -> Long.compare(last(b), last(a)));
        return l;
    }

    private static long last(Drawing d) {
        long t = 0;
        for (Piece p : d.pieces) t = Math.max(t, p.startedAt);
        return t;
    }

    /** "3 sessions" style counts for the window, as numbers. */
    public static long lastDrawn(Drawing d) {
        return last(d);
    }

    public Pending pendingFor(Drawing d) {
        for (Pending p : pending) if (p.drawingId.equals(d.id)) return p;
        return null;
    }

    public void remove(Drawing d) {
        drawings.remove(d);
        List<Pending> gone = new ArrayList<>();
        for (Pending p : pending) if (p.drawingId.equals(d.id) || p.candidateId.equals(d.id)) gone.add(p);
        pending.removeAll(gone);
    }

    /* ---- on disk: plain lines, one record each ---- */

    public void save(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("saipen-drawings 1\n");
            for (Drawing d : drawings) {
                w.write("drawing\t" + d.id + "\t" + clean(d.folder) + "\t" + clean(d.title) + "\n");
                for (Piece p : d.pieces) {
                    w.write("piece\t" + clean(p.file) + "\t" + p.startedAt + "\t" + p.frames + "\t" + p.width + "\t" + p.height
                            + "\t" + hex(p.opening) + "\t" + hex(p.closing) + "\n");
                }
            }
            for (Pending p : pending) w.write("pending\t" + clean(p.pieceFile) + "\t" + p.drawingId + "\t" + p.candidateId + "\n");
            for (String k : declined) w.write("declined\t" + clean(k) + "\n");
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("cannot save " + f);
        }
    }

    public static DrawingLibrary load(File f) throws IOException {
        DrawingLibrary lib = new DrawingLibrary();
        if (!f.isFile()) return lib;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            Drawing cur = null;
            for (String line; (line = r.readLine()) != null; ) {
                String[] c = line.split("\t", -1);
                switch (c[0]) {
                    case "drawing":
                        if (c.length < 4) break;
                        cur = new Drawing(c[1], c[3], c[2]);
                        lib.drawings.add(cur);
                        break;
                    case "piece":
                        if (cur == null || c.length < 8) break;
                        int w = Integer.parseInt(c[4]), h = Integer.parseInt(c[5]);
                        cur.pieces.add(new Piece(c[1], Long.parseLong(c[2]), Integer.parseInt(c[3]), w, h,
                                                 Signature.fromHex(c[6], w, h), Signature.fromHex(c[7], w, h)));
                        break;
                    case "pending":
                        if (c.length >= 4) lib.pending.add(new Pending(c[1], c[2], c[3]));
                        break;
                    case "declined":
                        if (c.length >= 2) lib.declined.add(c[1]);
                        break;
                    default:
                        break;
                }
            }
        }
        catch (NumberFormatException e) {
            throw new IOException("damaged " + f, e);
        }
        return lib;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String hex(Signature s) {
        return s == null || !s.isValid() ? "" : s.hex();
    }
}
