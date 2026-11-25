package com.winlator.saipen;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading what is inside SAI's settings files, for the browser's details and
 * its Settings page. Looked at on a device (2026-11-24); none of these
 * formats is described by SYSTEMAX.
 *
 *  - custool/<layer kind>/N.saitdat: a tool, text lines "key=T:value" (T a
 *    type letter), a "." line between blocks. name, tidstr (the kind of tool:
 *    pencil, airbrush, bucket...), cursize (tenths of a pixel), curdens,
 *    hardness, fomnam (brush shape), texnam (brush texture), texval.
 *  - N.saitlnk: the same tool shown in another layer kind's panel:
 *    tarcat (1 normal, 2 linework, 3 shape layers) and tarid.
 *  - _N.saitgrp: a tool set ("Basic", "Ver.1"...): name, then after "." the
 *    lines "slot=tool number". _0.saitset: the sets' order, "place=set".
 *  - brushtex/…, brushfom/…, scatter/… NAME.ini next to NAME.bmp: SAI's
 *    starting values for that picture ("Density = 50"), ";" comments.
 *  - colhis.saichis: "SAI-COLHIS-TYPE0", 8 bytes, then 10 bytes a colour:
 *    2 bytes 0, 2 bytes 1 (in use), red, green, blue as 16-bit little-endian.
 *    The order red-green-blue is a guess from the colours found (warm paper
 *    whites, an amber); not yet checked against SAI's own panel.
 *  - swatch/*.saiswat: "SAI-SWATCH-TYPE0" and 8 bytes; an empty one ends
 *    there. Colours after that are read as in colhis, which is unchecked.
 *  - skin/skin.ini: [Skin] colsel=0/1, SAI's Window Color Scheme (white or
 *    dark based); which number is which is unchecked.
 *  - settings2.ssd: scrambled (no readable text in 16 KB): only its date.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class SaiSettingsFiles {
    private SaiSettingsFiles() {}

    /* ---- key=value files of SAI's tools ---- */

    /** The typed values of every block, without the type letter:
     *  "name=U:Pencil" -> name=Pencil. A tool's size and texture are in the
     *  second block. Lines without a type ("0=1", a set's slots) are not values. */
    static Map<String, String> values(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines(f)) {
            if (line.startsWith("--EOF")) break;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String v = line.substring(eq + 1);
            if (v.length() < 2 || v.charAt(1) != ':') continue;
            out.putIfAbsent(line.substring(0, eq), v.substring(2));
        }
        return out;
    }

    /** The second block's "a=b" lines, in file order: a set's slots, the sets' order. */
    static List<int[]> pairs(File f) {
        List<int[]> out = new ArrayList<>();
        int block = 0;
        for (String line : lines(f)) {
            if (line.equals(".")) { if (++block > 1) break; continue; }
            if (block != 1) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try { out.add(new int[] {Integer.parseInt(line.substring(0, eq).trim()), Integer.parseInt(line.substring(eq + 1).trim())}); }
            catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** One tool as SAI's panel has it. size: pixels, or -1 for a tool
     *  without one (bucket, selection...). */
    public static final class Tool {
        public final String layerKind, name, kind, shape, texture;
        public final int number;
        public final double size;
        public final int density, hardness, textureStrength;
        /** A link: the layer kind it really belongs to; null for the tool itself. */
        public final String linkedFrom;
        /** What pen pressure does to its size and density. */
        final PressureStroke.Response pressure;

        Tool(String layerKind, int number, String name, String kind, double size, int density, int hardness,
             String shape, String texture, int textureStrength, String linkedFrom, PressureStroke.Response pressure) {
            this.layerKind = layerKind; this.number = number; this.name = name; this.kind = kind;
            this.size = size; this.density = density; this.hardness = hardness;
            this.shape = shape; this.texture = texture; this.textureStrength = textureStrength;
            this.linkedFrom = linkedFrom; this.pressure = pressure;
        }

        public boolean draws() { return size > 0; }
    }

    static final String[] LAYER_KINDS = {"nrm", "ink", "shp2"};

    /** A tool by its number in a layer kind's folder, following a link once. */
    static Tool tool(File custool, String layerKind, int number) {
        File dir = new File(custool, layerKind);
        File dat = new File(dir, number + ".saitdat");
        if (dat.isFile()) return toolFrom(dat, layerKind, number, null);
        File lnk = new File(dir, number + ".saitlnk");
        if (lnk.isFile()) {
            Map<String, String> v = values(lnk);
            int cat = integer(v.get("tarcat"), 0), id = integer(v.get("tarid"), -1);
            if (cat >= 1 && cat <= LAYER_KINDS.length && id >= 0) {
                File target = new File(new File(custool, LAYER_KINDS[cat - 1]), id + ".saitdat");
                if (target.isFile()) return toolFrom(target, layerKind, number, LAYER_KINDS[cat - 1]);
            }
        }
        return null;
    }

    static Tool toolFrom(File dat, String layerKind, int number, String linkedFrom) {
        Map<String, String> v = values(dat);
        String name = v.get("name");
        if (name == null || name.isEmpty()) name = String.valueOf(number);
        int size = integer(v.get("cursize"), -1);
        return new Tool(layerKind, number, name, v.getOrDefault("tidstr", ""),
                        size > 0 ? size / 10.0 : -1, integer(v.get("curdens"), 100), integer(v.get("hardness"), 50),
                        v.getOrDefault("fomnam", ""), v.getOrDefault("texnam", ""), integer(v.get("texval"), 0),
                        linkedFrom, pressureOf(v));
    }

    /** szprsf/dnprsf, minsize/mindens, szsens/dnsens; a tool without them
     *  (a line or curve tool) does not follow pressure. */
    static PressureStroke.Response pressureOf(Map<String, String> v) {
        return new PressureStroke.Response(
            integer(v.get("szprsf"), 0) != 0, integer(v.get("minsize"), 0), integer(v.get("szsens"), 100),
            integer(v.get("dnprsf"), 0) != 0, integer(v.get("mindens"), 0), integer(v.get("dnsens"), 100));
    }

    /** A tool set of one layer kind, its tools in panel order. */
    public static final class ToolSet {
        public final String layerKind, name;
        public final List<Tool> tools;
        ToolSet(String layerKind, String name, List<Tool> tools) { this.layerKind = layerKind; this.name = name; this.tools = tools; }
    }

    /** Every layer kind's sets, in SAI's order (_0.saitset), then any set
     *  that order does not name. */
    public static List<ToolSet> toolSets(File settings) {
        List<ToolSet> out = new ArrayList<>();
        File custool = new File(settings, "custool");
        for (String kind : LAYER_KINDS) {
            File dir = new File(custool, kind);
            if (!dir.isDirectory()) continue;
            List<Integer> order = new ArrayList<>();
            for (int[] p : pairs(new File(dir, "_0.saitset"))) if (!order.contains(p[1])) order.add(p[1]);
            File[] files = dir.listFiles();
            List<Integer> found = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (n.startsWith("_") && n.toLowerCase(Locale.ROOT).endsWith(".saitgrp")) {
                        int id = integer(n.substring(1, n.length() - ".saitgrp".length()), -1);
                        if (id >= 0) found.add(id);
                    }
                }
            }
            found.sort(Integer::compare);
            for (int id : found) if (!order.contains(id)) order.add(id);
            for (int id : order) {
                File grp = new File(dir, "_" + id + ".saitgrp");
                if (!grp.isFile()) continue;
                String name = values(grp).get("name");
                List<int[]> slots = pairs(grp);
                slots.sort((a, b) -> Integer.compare(a[0], b[0]));
                List<Tool> tools = new ArrayList<>();
                for (int[] s : slots) {
                    Tool t = tool(custool, kind, s[1]);
                    if (t != null) tools.add(t);
                }
                out.add(new ToolSet(kind, name == null || name.isEmpty() ? "Set " + id : name, tools));
            }
        }
        return out;
    }

    /* ---- a picture's .ini ---- */

    /** NAME.ini next to NAME.bmp, or null. */
    public static File iniOf(File picture) {
        String n = picture.getName();
        int dot = n.lastIndexOf('.');
        File ini = new File(picture.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".ini");
        return ini.isFile() ? ini : null;
    }

    /** "Density = 50" lines, comments and [sections] left out. */
    public static Map<String, String> ini(File ini) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ini == null) return out;
        for (String line : lines(ini)) {
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("[")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    /* ---- colours ---- */

    /** Colours as 0xAARRGGBB, from colhis.saichis or a .saiswat. An unknown
     *  or empty file gives none. */
    public static List<Integer> colours(File f) {
        List<Integer> out = new ArrayList<>();
        byte[] b = bytes(f, 64 * 1024);
        if (b.length < 24) return out;
        String magic = new String(b, 0, 16, StandardCharsets.US_ASCII);
        if (!magic.startsWith("SAI-COLHIS-") && !magic.startsWith("SAI-SWATCH-")) return out;
        for (int i = 24; i + 10 <= b.length; i += 10) {
            int used = (b[i + 2] & 0xff) | (b[i + 3] & 0xff) << 8;
            if (used != 1) continue;
            int r = b[i + 5] & 0xff, g = b[i + 7] & 0xff, bl = b[i + 9] & 0xff;
            out.add(0xff000000 | r << 16 | g << 8 | bl);
        }
        return out;
    }

    /** skin/skin.ini's colsel, or -1. */
    public static int windowScheme(File settings) {
        File ini = new File(settings, "skin/skin.ini");
        return integer(ini(ini.isFile() ? ini : null).get("colsel"), -1);
    }

    /* ---- backups and every file ---- */

    /** A folder of older copies: SAI's own (bak) or SAI Pen's (settings-before-…). */
    public static final class Backup {
        public final String path;
        public final int files;
        public final long lastModified;
        Backup(String path, int files, long lastModified) { this.path = path; this.files = files; this.lastModified = lastModified; }
    }

    /** SAI's bak folders inside settings, a few folders deep, by path. */
    public static List<Backup> saiBackups(File settings) {
        List<Backup> out = new ArrayList<>();
        findBak(settings, "", 0, out);
        out.sort((a, b) -> a.path.compareToIgnoreCase(b.path));
        return out;
    }

    private static void findBak(File dir, String rel, int depth, List<Backup> out) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.getName().equalsIgnoreCase("bak")) {
                long[] n = count(f);
                out.add(new Backup(r, (int) n[0], n[1]));
            } else findBak(f, r, depth + 1, out);
        }
    }

    /** settings-before-… folders next to settings (SAI Pen's, before an import), newest first. */
    public static List<Backup> importBackups(File settings) {
        List<Backup> out = new ArrayList<>();
        File[] files = settings.getParentFile() == null ? null : settings.getParentFile().listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("settings-before-")) {
                long[] n = count(f);
                out.add(new Backup(f.getName(), (int) n[0], f.lastModified()));
            }
        }
        out.sort((a, b) -> b.path.compareTo(a.path));
        return out;
    }

    /** Files in a folder and below, and the newest one's time. */
    static long[] count(File dir) {
        long[] n = {0, 0};
        File[] files = dir.listFiles();
        if (files == null) return n;
        for (File f : files) {
            if (f.isDirectory()) { long[] m = count(f); n[0] += m[0]; n[1] = Math.max(n[1], m[1]); }
            else { n[0]++; n[1] = Math.max(n[1], f.lastModified()); }
        }
        return n;
    }

    /** One file in settings: its path inside it and its size. */
    public static final class Entry {
        public final String path;
        public final long size;
        Entry(String path, long size) { this.path = path; this.size = size; }
    }

    /** Every file in settings, by path. */
    public static List<Entry> allFiles(File settings) {
        List<Entry> out = new ArrayList<>();
        walk(settings, "", out);
        out.sort((a, b) -> a.path.compareToIgnoreCase(b.path));
        return out;
    }

    private static void walk(File dir, String rel, List<Entry> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) walk(f, r, out);
            else out.add(new Entry(r, f.length()));
        }
    }

    /* ---- small pieces ---- */

    static int integer(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim().replace("+", "")); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static List<String> lines(File f) {
        if (f == null) return new ArrayList<>();
        String text = new String(bytes(f, 256 * 1024), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String l : text.split("\r?\n")) out.add(l.trim());
        return out;
    }

    private static byte[] bytes(File f, int max) {
        byte[] buf = new byte[max];
        int len = 0;
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while (len < buf.length && (r = in.read(buf, len, buf.length - len)) > 0) len += r;
        }
        catch (IOException e) {
            return new byte[0];
        }
        return Arrays.copyOf(buf, len);
    }
}
