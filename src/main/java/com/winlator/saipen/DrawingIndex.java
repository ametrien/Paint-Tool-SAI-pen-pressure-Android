package com.winlator.saipen;

import com.winlator.saipen.DrawingLibrary.Drawing;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Every drawing in Documents/SAI Drawings (each kind SAI opens, DrawingFormats), for the Drawings window
 * (DrawingsDialog): search by name, a folder, three orders, and the timelapse
 * a drawing has, if any. RecentDrawings finds the files.
 *
 * Only what the file system says (name, folder, date, size) and the file's
 * small picture (Sai2Thumb, PsdThumb): nothing else of a drawing is read.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class DrawingIndex {
    private DrawingIndex() {}

    public enum Sort { NEWEST, NAME, SIZE }

    public static final class Entry {
        public final File file;
        /** The folder under SAI Drawings, "/" between levels; "" for SAI Drawings itself. */
        public final String folder;
        public final String title;
        public final long modified, size;

        Entry(File file, String folder, String title, long modified, long size) {
            this.file = file;
            this.folder = folder;
            this.title = title;
            this.modified = modified;
            this.size = size;
        }
    }

    /** Every drawing under `root`, newest first. */
    /** Every drawing under `root`, newest first. Reference pictures (the
     *  References folder) are the artist's, not drawings: they stay out,
     *  like the Timelapses folder (owner, 2026-11-26). */
    public static List<Entry> all(File root) {
        List<Entry> out = new ArrayList<>();
        for (File f : RecentDrawings.newest(root, Integer.MAX_VALUE)) {
            if (!RecentDrawings.isReference(f, root)) out.add(entry(f, root));
        }
        return out;
    }

    static Entry entry(File f, File root) {
        String rootPath = root.getAbsolutePath();
        String parent = f.getAbsoluteFile().getParent();
        String folder = parent == null || parent.equals(rootPath) || !parent.startsWith(rootPath + "/")
            ? "" : parent.substring(rootPath.length() + 1);
        return new Entry(f, folder, RecentDrawings.title(f), f.lastModified(), f.length());
    }

    /** The folders that hold drawings, sorted, "" (SAI Drawings itself)
     *  first. "Cats" and "cats" stay two folders. */
    public static List<String> folders(List<Entry> all) {
        TreeSet<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (Entry e : all) s.add(e.folder);
        return new ArrayList<>(s);
    }

    /**
     * What the window shows: the drawings in `folder` (null for every folder;
     * a folder's subfolders count as in it) whose name holds `query` (any
     * case; blank for all), in `sort` order.
     */
    public static List<Entry> view(List<Entry> all, String query, String folder, Sort sort) {
        return view(all, query, folder, sort, false);
    }

    /** As above; `reversed` flips the order: oldest first, Z to A, smallest first. */
    public static List<Entry> view(List<Entry> all, String query, String folder, Sort sort, boolean reversed) {
        return view(all, query, folder, sort, reversed, java.util.Collections.emptySet());
    }

    /** As above, leaving out the kinds in `hidden` (DrawingFormats). */
    public static List<Entry> view(List<Entry> all, String query, String folder, Sort sort, boolean reversed,
                                   java.util.Set<String> hidden) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (hidden.contains(DrawingFormats.of(e.file))) continue;
            if (folder != null && !inFolder(e.folder, folder)) continue;
            if (!q.isEmpty() && !e.title.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(e);
        }
        Comparator<Entry> byPath = (a, b) -> a.file.getPath().compareTo(b.file.getPath());
        Comparator<Entry> c;
        switch (sort) {
            case NAME:
                c = Comparator.comparing((Entry e) -> e.title, String.CASE_INSENSITIVE_ORDER);
                break;
            case SIZE:
                c = (a, b) -> Long.compare(b.size, a.size);
                break;
            default:
                c = (a, b) -> Long.compare(b.modified, a.modified);
        }
        if (reversed) c = c.reversed();
        out.sort(c.thenComparing(byPath));
        return out;
    }

    static boolean inFolder(String entryFolder, String folder) {
        if (folder.isEmpty()) return entryFolder.isEmpty();
        return entryFolder.equals(folder) || entryFolder.startsWith(folder + "/");
    }

    public static long totalSize(List<Entry> entries) {
        long n = 0;
        for (Entry e : entries) n += e.size;
        return n;
    }

    /**
     * The timelapse of this drawing, if there is one: a timelapse drawing
     * with the same name (SAI's canvas title, with or without ".sai2"; any
     * case). A timelapse is filed by how the canvas looks, not by its file,
     * so the name is the only link; two with the same name give the one drawn
     * last. Null for none.
     */
    public static Drawing timelapseFor(Entry e, List<Drawing> drawings) {
        String want = bare(e.title);
        if (want.isEmpty()) return null;
        Drawing best = null;
        for (Drawing d : drawings) {
            if (d.pieces.isEmpty() || !bare(d.title).equals(want)) continue;
            if (best == null || DrawingLibrary.lastDrawn(d) > DrawingLibrary.lastDrawn(best)) best = d;
        }
        return best;
    }

    /**
     * The second link between a drawing and its timelapse, by look, for when
     * the names no longer agree: the drawing was renamed (in SAI or a file
     * manager), or a new canvas was saved under a name nothing saw. A
     * timelapse that no drawing claims by name ("orphan") goes to the
     * unclaimed drawing whose picture looks like the timelapse's last frame
     * (SavedAsMatch, the same test as "The same drawing?"), saved no earlier
     * than a minute before the timelapse began. Each drawing at most once.
     * `look` reads a drawing's picture; only called for possible pairs.
     */
    public static java.util.Map<Drawing, Entry> linkByLook(List<Entry> unclaimed, List<Drawing> orphans,
                                                           java.util.function.Function<Entry, DrawingLibrary.Signature> look) {
        java.util.Map<Drawing, Entry> out = new java.util.LinkedHashMap<>();
        java.util.Map<Entry, DrawingLibrary.Signature> seen = new java.util.HashMap<>();
        java.util.Set<Entry> taken = new java.util.HashSet<>();
        for (Drawing d : orphans) {
            if (d.pieces.isEmpty()) continue;
            long began = Long.MAX_VALUE;
            for (DrawingLibrary.Piece p : d.pieces) began = Math.min(began, p.startedAt);
            List<SavedAsMatch.Candidate> cands = new ArrayList<>();
            java.util.Map<String, Entry> byKey = new java.util.HashMap<>();
            for (Entry e : unclaimed) {
                if (taken.contains(e) || e.modified < began - 60_000) continue;
                DrawingLibrary.Signature sig = seen.containsKey(e) ? seen.get(e) : look.apply(e);
                seen.put(e, sig);
                if (sig == null) continue;
                String key = e.file.getPath();
                byKey.put(key, e);
                cands.add(new SavedAsMatch.Candidate(key, sig));
            }
            String hit = SavedAsMatch.pick(d.closing(), cands);
            if (hit != null) {
                Entry e = byKey.get(hit);
                taken.add(e);
                out.put(d, e);
            }
        }
        return out;
    }

    static String bare(String title) {
        String t = title == null ? "" : title.trim();
        if (t.toLowerCase(Locale.ROOT).endsWith(".sai2")) t = t.substring(0, t.length() - 5).trim();
        return t.toLowerCase(Locale.ROOT);
    }
}
