package com.winlator.saipen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is in SAI's settings folder, kind by kind, for the preview behind the
 * tiles in the brushes card: the pictures of one kind, or the tools.
 *
 * The kinds are the tiles' order. Brush shapes are three of SAI's folders
 * (brushfom/blotmap, bristle, brshape), shown as one kind like the count.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class SettingsContents {
    private SettingsContents() {}

    public static final int BRUSH_TEXTURES = 0, PAPER_TEXTURES = 1, BRUSH_SHAPES = 2, SCATTER = 3, TOOLS = 4;
    public static final int KINDS = 5;

    /** SAI's folders (inside settings) for each picture kind. */
    static String[] folders(int kind) {
        switch (kind) {
            case BRUSH_TEXTURES: return new String[] {"brushtex"};
            case PAPER_TEXTURES: return new String[] {"papertex"};
            case BRUSH_SHAPES:   return new String[] {"brushfom/blotmap", "brushfom/bristle", "brushfom/brshape"};
            case SCATTER:        return new String[] {"scatter"};
            default:             return new String[0];
        }
    }

    /** The .bmp pictures of one kind, sorted by name (any letter case). */
    public static List<File> pictures(File settings, int kind) {
        List<File> out = new ArrayList<>();
        for (String f : folders(kind)) {
            File[] files = new File(settings, f).listFiles();
            if (files == null) continue;
            for (File p : files) {
                if (p.isFile() && p.getName().toLowerCase(Locale.ROOT).endsWith(".bmp")) out.add(p);
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** SAI's own files of one sort in one folder (swatch/*.saiswat,
     *  scratchpad/*.saispad), sorted by name; what is in them is SAI's own
     *  format, described nowhere, so only their names are shown. */
    public static List<File> files(File settings, String folder, String ext) {
        List<File> out = new ArrayList<>();
        File[] files = new File(settings, folder).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(ext)) out.add(f);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    public static List<File> swatches(File settings) { return files(settings, "swatch", ".saiswat"); }
    public static List<File> scratchPads(File settings) { return files(settings, "scratchpad", ".saispad"); }

    /** A file's name without its extension: "Swatches1.saiswat" -> "Swatches1". */
    public static String bareName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** The rest of the settings folder, for the "Also in settings" lines:
     *  each a thing SAI keeps there and where (as found on a device,
     *  2026-11-24). Only what is there is listed. */
    public static final int EXTRA_COLOUR_HISTORY = 0, EXTRA_SKIN = 1, EXTRA_SETTINGS = 2, EXTRA_BACKUPS = 3;

    public static final class Extra {
        public final int what;
        /** Where it is, as SAI names it: "colhis.saichis", "skin", ...; for
         *  backups the number of bak folders. */
        public final String where;
        public final int count;
        Extra(int what, String where, int count) { this.what = what; this.where = where; this.count = count; }
    }

    public static List<Extra> extras(File settings) {
        List<Extra> out = new ArrayList<>();
        if (new File(settings, "colhis.saichis").isFile()) out.add(new Extra(EXTRA_COLOUR_HISTORY, "colhis.saichis", 1));
        if (new File(settings, "skin").isDirectory()) out.add(new Extra(EXTRA_SKIN, "skin", 1));
        if (new File(settings, "settings2.ssd").isFile()) out.add(new Extra(EXTRA_SETTINGS, "settings2.ssd", 1));
        int bak = bakFolders(settings, 0);
        if (bak > 0) out.add(new Extra(EXTRA_BACKUPS, "bak", bak));
        return out;
    }

    /** SAI's own backups: folders named bak, anywhere a few folders deep. */
    static int bakFolders(File dir, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return 0;
        int n = 0;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            if (f.getName().equalsIgnoreCase("bak")) n++;
            else n += bakFolders(f, depth + 1);
        }
        return n;
    }

    /** A picture's name as SAI shows it: without ".bmp". */
    public static String name(File picture) {
        String n = picture.getName();
        return n.toLowerCase(Locale.ROOT).endsWith(".bmp") ? n.substring(0, n.length() - 4) : n;
    }

    /** One tool: its group (custool's subfolder: nrm for normal layers, ink
     *  for linework layers, shp2 for shape layers) and the name SAI shows. */
    public static final class Tool {
        public final String group, name;
        Tool(String group, String name) { this.group = group; this.name = name; }
    }

    /**
     * SAI's tools, group by group, as the tool panel has them. Each tool is a
     * small text file (custool/nrm/1.saitdat ...) with lines like
     * "name=U:Pencil"; the file's number is its place in the panel, so the
     * files sort by that number, not by name (10 after 9, not after 1).
     * .saitlnk files are links to another tool, not tools: left out, as in
     * the count.
     */
    public static List<Tool> tools(File settings) {
        List<Tool> out = new ArrayList<>();
        File[] groups = new File(settings, "custool").listFiles();
        if (groups == null) return out;
        java.util.Arrays.sort(groups, (a, b) -> groupOrder(a.getName()) != groupOrder(b.getName())
            ? Integer.compare(groupOrder(a.getName()), groupOrder(b.getName()))
            : a.getName().compareToIgnoreCase(b.getName()));
        for (File g : groups) {
            File[] tools = g.listFiles();
            if (tools == null) continue;
            java.util.Arrays.sort(tools, (a, b) -> Long.compare(number(a.getName()), number(b.getName())) != 0
                ? Long.compare(number(a.getName()), number(b.getName()))
                : a.getName().compareToIgnoreCase(b.getName()));
            for (File t : tools) {
                String n = t.getName();
                if (!t.isFile() || !n.toLowerCase(Locale.ROOT).endsWith(".saitdat")) continue;
                String name = nameIn(t);
                out.add(new Tool(g.getName(), name != null ? name : n.substring(0, n.length() - ".saitdat".length())));
            }
        }
        return out;
    }

    /** Normal layers first, then linework, then shapes, as in SAI. */
    private static int groupOrder(String group) {
        switch (group.toLowerCase(Locale.ROOT)) {
            case "nrm": return 0;
            case "ink": return 1;
            case "shp2": return 2;
            default: return 3;
        }
    }

    /** "12.saitdat" -> 12; no number in front -> after all numbered ones. */
    static long number(String fileName) {
        int i = 0;
        while (i < fileName.length() && i < 9 && Character.isDigit(fileName.charAt(i))) i++;
        return i == 0 ? Long.MAX_VALUE : Long.parseLong(fileName.substring(0, i));
    }

    /** The "name=" line's text, or null. The files are small (about 1 KB);
     *  only the first 16 KB are read. */
    static String nameIn(File tool) {
        byte[] buf = new byte[16 * 1024];
        int len = 0;
        try (java.io.InputStream in = new java.io.FileInputStream(tool)) {
            int r;
            while (len < buf.length && (r = in.read(buf, len, buf.length - len)) > 0) len += r;
        }
        catch (java.io.IOException e) {
            return null;
        }
        String text = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.startsWith("name=")) continue;
            String v = line.substring("name=".length());
            /* "U:Pencil": a type letter and a colon before the value */
            if (v.length() >= 2 && v.charAt(1) == ':') v = v.substring(2);
            return v.isEmpty() ? null : v;
        }
        return null;
    }
}
