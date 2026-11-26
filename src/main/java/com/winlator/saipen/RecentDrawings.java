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

    /** The newest .sai2 files under `folder`, newest first, at most `max`. */
    public static List<File> newest(File folder, int max) {
        List<File> all = new ArrayList<>();
        collect(folder, 0, all);
        all.sort((a, b) -> {
            int byTime = Long.compare(b.lastModified(), a.lastModified());
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
            else if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".sai2") && f.length() > 0) {
                out.add(f);
            }
        }
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

    /** The name to show under a thumbnail: without ".sai2". */
    public static String title(File drawing) {
        String n = drawing.getName();
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".sai2") ? n.substring(0, n.length() - 5) : n;
    }
}
