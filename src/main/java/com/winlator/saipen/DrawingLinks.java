package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the artist decided by hand about drawings and their timelapses, in All
 * drawings (the owner, 2026-11-27: "if a timelapse got the wrong drawing, let
 * people unlink it; if a record lost its file, ask where it is now"). Always
 * wins over the automatic matching (DrawingsDialog.match: by name, then by
 * look):
 *
 *   link     <drawing file key>  <timelapse id>   this file's timelapse is that one
 *   unlink   <drawing file key>  <timelapse id>   never pair these two
 *   forget   <timelapse id>                       a timelapse with no file: do not
 *                                                 list it as lost (its video stays)
 *
 * A file key is the path under SAI Drawings ("comics/page.sai2"), as
 * CanvasTime's; a timelapse id is DrawingLibrary.Drawing.id. Kept in the app's
 * own files (drawing-links.txt), tab-separated, one record a line.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class DrawingLinks {
    public static final String FILE = "drawing-links.txt";
    static final String HEADER = "# SAI Pen: drawings and timelapses linked or unlinked by hand";

    private final Map<String, String> linked = new HashMap<>();
    private final Set<String> unlinked = new LinkedHashSet<>();
    private final Set<String> forgotten = new LinkedHashSet<>();

    /** The timelapse the artist gave this file, or null. */
    public String linkOf(String fileKey) {
        return linked.get(fileKey);
    }

    /** The file the artist gave this timelapse, or null. */
    public String fileOf(String timelapseId) {
        for (Map.Entry<String, String> e : linked.entrySet()) if (e.getValue().equals(timelapseId)) return e.getKey();
        return null;
    }

    public boolean isUnlinked(String fileKey, String timelapseId) {
        return unlinked.contains(fileKey + "\t" + timelapseId);
    }

    public boolean isForgotten(String timelapseId) {
        return forgotten.contains(timelapseId);
    }

    /** May the automatic matching pair them? Not if the artist unlinked
     *  them, or gave either one to something else. */
    public boolean mayPair(String fileKey, String timelapseId) {
        if (isUnlinked(fileKey, timelapseId)) return false;
        String mine = linked.get(fileKey);
        if (mine != null && !mine.equals(timelapseId)) return false;
        String theirs = fileOf(timelapseId);
        return theirs == null || theirs.equals(fileKey);
    }

    /** "This is its timelapse": one timelapse a file, one file a timelapse. */
    public void link(String fileKey, String timelapseId) {
        String before = fileOf(timelapseId);
        if (before != null) linked.remove(before);
        linked.put(fileKey, timelapseId);
        unlinked.remove(fileKey + "\t" + timelapseId);
        forgotten.remove(timelapseId);
    }

    /** "Not this drawing's timelapse." */
    public void unlink(String fileKey, String timelapseId) {
        if (timelapseId.equals(linked.get(fileKey))) linked.remove(fileKey);
        unlinked.add(fileKey + "\t" + timelapseId);
    }

    /** "Forget it": a timelapse that has no file stops being listed as lost. */
    public void forget(String timelapseId) {
        String f = fileOf(timelapseId);
        if (f != null) linked.remove(f);
        forgotten.add(timelapseId);
    }

    /** A drawing file renamed or moved (CanvasTime.follow, Find its file):
     *  what was decided for it goes along. */
    public void moveFile(String from, String to) {
        if (from.equals(to)) return;
        String t = linked.remove(from);
        if (t != null && !linked.containsKey(to)) linked.put(to, t);
        List<String> moved = new ArrayList<>();
        for (String u : unlinked) if (u.startsWith(from + "\t")) moved.add(u);
        for (String u : moved) {
            unlinked.remove(u);
            unlinked.add(to + u.substring(from.length()));
        }
    }

    /* ---- the file ---- */

    public static DrawingLinks load(File file) {
        DrawingLinks l = new DrawingLinks();
        try {
            if (file.isFile()) l.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException ignored) {
            /* unreadable: nothing decided by hand, the automatic matching only */
        }
        return l;
    }

    void parse(String text) {
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f[0].equals("link") && f.length >= 3 && !f[1].isEmpty() && !f[2].isEmpty()) link(f[1], f[2]);
            else if (f[0].equals("unlink") && f.length >= 3 && !f[1].isEmpty() && !f[2].isEmpty()) unlink(f[1], f[2]);
            else if (f[0].equals("forget") && f.length >= 2 && !f[1].isEmpty()) forget(f[1]);
        }
    }

    String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, String> e : linked.entrySet())
            out.append("link\t").append(clean(e.getKey())).append('\t').append(clean(e.getValue())).append('\n');
        for (String u : unlinked) {
            int tab = u.indexOf('\t');
            out.append("unlink\t").append(clean(u.substring(0, tab))).append('\t').append(clean(u.substring(tab + 1))).append('\n');
        }
        for (String f : forgotten) out.append("forget\t").append(clean(f)).append('\n');
        return out.toString();
    }

    public boolean save(File file) {
        return DrawingStats.write(file, serialize());
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }
}
