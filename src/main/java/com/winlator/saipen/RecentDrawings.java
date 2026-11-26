package com.winlator.saipen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The newest drawings in Documents/SAI Drawings, for the row of thumbnails on
 * the launcher screen (Sai2Thumb makes the pictures). Every drawing ends up
 * in that folder: SAI saves there as drive F:, and what it saved inside the
 * app is copied there when it closes (DeviceDrawings).
 */
public final class RecentDrawings {
    private RecentDrawings() {}

    /** How many the launcher shows. */
    public static final int MAX = 12;
    /** Not more than this many folders deep. */
    static final int DEPTH = 4;
    /** Timelapse videos live under SAI Drawings; nothing to open in SAI there. */
    static final String SKIP = "Timelapses";

    /** Reference pictures the artist keeps under SAI Drawings: shown in All
     *  drawings (under their folder), not in the start screen's row. */
    public static final String REFERENCES = "References";

    /** The newest drawings (every kind SAI opens, DrawingFormats) under
     *  `folder`, newest first, at most `max`. */
    public static List<File> newest(File folder, int max) {
        List<File> all = new ArrayList<>();
        collect(folder, 0, all);
        /* each file's time read once: a file SAI saves while this sorts
           must not change under the sort ("Comparison method violates its
           general contract") */
        java.util.Map<File, Long> time = new java.util.HashMap<>();
        for (File f : all) time.put(f, f.lastModified());
        all.sort((a, b) -> {
            int byTime = Long.compare(time.get(b), time.get(a));
            return byTime != 0 ? byTime : a.getPath().compareTo(b.getPath());
        });
        return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
    }

    private static void collect(File dir, int depth, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < DEPTH && !(depth == 0 && f.getName().equals(SKIP))) collect(f, depth + 1, out);
            }
            else if (DrawingFormats.isDrawing(f) && f.length() > 0) {
                out.add(f);
            }
        }
    }

    /** Whether `f` is in SAI Drawings/References (any depth below it). */
    public static boolean isReference(File f, File drawingsFolder) {
        String refs = new File(drawingsFolder, REFERENCES).getAbsolutePath() + "/";
        return f.getAbsolutePath().regionMatches(true, 0, refs, 0, refs.length());
    }

    /**
     * Where SAI sees `drawing`: drive F: is Documents/SAI Drawings
     * (SaiLocator.drives). Null for a file outside that folder.
     */
    public static String windowsPath(File drawing, File drawingsFolder) {
        String root = drawingsFolder.getAbsolutePath();
        String path = drawing.getAbsolutePath();
        if (!path.startsWith(root + "/")) return null;
        return "F:\\" + path.substring(root.length() + 1).replace('/', '\\');
    }

    /**
     * `windowsPath` as one argument on the command line Winlator starts Wine
     * with (ProcessHelper.splitCommand): a backslash before each space, no
     * quotes. Quotes stay part of the argument there and reached SAI as part
     * of the name: "Cannot open the canvas ... This file extension is not
     * supported" for "F:\x.sai2" (2026-11-26). The /dir folder is passed the
     * same way (StringUtils.escapeDOSPath).
     */
    public static String commandLineArg(String windowsPath) {
        return windowsPath.replace(" ", "\\ ");
    }

    /** The name to show under a thumbnail: without ".sai2". */
    public static String title(File drawing) {
        String n = drawing.getName();
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".sai2") ? n.substring(0, n.length() - 5) : n;
    }
}
