package com.winlator.saipen;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Brushes, textures and presets into SAI's settings folder (since 0.4.21 on
 * the tablet: Documents/SYSTEMAX Software Development/SAIv2/settings), from
 * a zip or a folder the person picks, so nobody has to know SAI's folder
 * names or use a file manager.
 *
 * Two kinds of thing come in:
 * - a pack: pictures (.bmp, some with an .ini) that people share, sorted
 *   into folders named as SAI names them (brushtex, papertex, scatter,
 *   blotmap, bristle, brshape). Each file goes into SAI's folder of that
 *   name; a file already there is kept. Pictures in folders with other
 *   names are "unsorted": the screen asks what they are.
 * - a whole settings folder, from a PC or another device: the one with
 *   settings2.ssd in it. It replaces the folder on the tablet as a whole
 *   (SAI numbers its tools, two sets do not mix), the old one kept beside it.
 *
 * Only .bmp and .ini are taken from a pack; a pack's programs (one came
 * with SaiBristleCorrector.exe) and notes stay out. SAI's own backups (bak)
 * and junk from Macs are left out everywhere.
 *
 * No Android types: the JVM tests run it on the Mac with real zips.
 */
public final class BrushImport {
    private BrushImport() {}

    /** SAI's folders for pictures inside settings, in the order the screen
     *  counts them. The three brush shape folders sit in brushfom. */
    public static final String[] FOLDERS = {
        "brushtex", "papertex", "scatter", "brushfom/blotmap", "brushfom/bristle", "brushfom/brshape"};
    public static final int BRUSH_TEXTURES = 0, PAPER_TEXTURES = 1, SCATTER = 2, BLOTMAP = 3;

    /** The file that makes a folder SAI's whole settings folder. */
    public static final String SETTINGS_FILE = "settings2.ssd";

    public interface Visitor { void file(String path, InputStream in) throws IOException; }

    /** A zip or a folder, read file by file. Can be read more than once. */
    public interface Source { void visit(Visitor v) throws IOException; }

    public interface Opener { InputStream open() throws IOException; }

    public interface Progress { void at(int done, int total); }

    /* ---- what is in it ---- */

    public static final class Plan {
        /** Pictures (.bmp) per FOLDERS entry. */
        public final int[] pictures = new int[FOLDERS.length];
        /** Pictures in folders whose names do not say what they are. */
        public int unsorted;
        /** Those pictures and the .ini files beside them: the progress total
         *  once the person has said what they are. */
        public int unsortedFiles;
        /** Files that are taken in, .ini included: the progress total. */
        public int files;
        /** Where settings2.ssd sits, "" for the top; null when this is no
         *  whole settings folder. */
        public String settingsRoot;

        public boolean isWholeSettings() { return settingsRoot != null; }
        public int sorted() {
            int n = 0;
            for (int c : pictures) n += c;
            return n;
        }
        /** Brush shapes, the three brushfom folders together. */
        public int brushShapes() { return pictures[3] + pictures[4] + pictures[5]; }
        public boolean isEmpty() { return !isWholeSettings() && sorted() == 0 && unsorted == 0; }
    }

    public static Plan scan(Source source) throws IOException {
        final Plan plan = new Plan();
        final List<String> paths = new ArrayList<>();
        source.visit((path, in) -> {
            String[] seg = segments(path);
            if (seg == null || isJunk(seg)) return;
            paths.add(path);
            if (seg[seg.length - 1].equalsIgnoreCase(SETTINGS_FILE) && plan.settingsRoot == null) {
                plan.settingsRoot = join(seg, seg.length - 1);
            }
        });
        for (String path : paths) {
            String[] seg = segments(path);
            if (plan.isWholeSettings()) {
                if (under(seg, plan.settingsRoot) != null) plan.files++;
                continue;
            }
            int folder = folderOf(seg);
            boolean bmp = isPicture(seg[seg.length - 1]);
            if (folder >= 0 && isTaken(seg[seg.length - 1])) {
                plan.files++;
                if (bmp) plan.pictures[folder]++;
            }
            else if (isTaken(seg[seg.length - 1])) {
                plan.unsortedFiles++;
                if (bmp) plan.unsorted++;
            }
        }
        return plan;
    }

    /* ---- what SAI has now ---- */

    /** SAI's pictures per FOLDERS entry, and last its tools (custool). */
    public static int[] count(File settings) {
        int[] n = new int[FOLDERS.length + 1];
        for (int i = 0; i < FOLDERS.length; i++) {
            File[] files = new File(settings, FOLDERS[i]).listFiles();
            if (files == null) continue;
            for (File f : files) if (f.isFile() && isPicture(f.getName())) n[i]++;
        }
        File[] groups = new File(settings, "custool").listFiles();
        if (groups != null) {
            for (File g : groups) {
                File[] tools = g.listFiles();
                if (tools == null) continue;
                for (File t : tools) if (t.isFile() && t.getName().toLowerCase(Locale.ROOT).endsWith(".saitdat")) n[FOLDERS.length]++;
            }
        }
        return n;
    }

    /** SAI has made its settings folder (it does on its first start). */
    public static boolean hasSaiSettings(File settings) {
        return new File(settings, SETTINGS_FILE).isFile();
    }

    /* ---- adding a pack ---- */

    public static final class Result {
        public final int[] added = new int[FOLDERS.length];
        /** Pictures not added because one of that name was there already. */
        public int kept;
        /** Whole settings: files put in, and where the old folder went. */
        public int files;
        public File backup;

        public int addedPictures() {
            int n = 0;
            for (int c : added) n += c;
            return n;
        }
        public int addedBrushShapes() { return added[3] + added[4] + added[5]; }
    }

    /**
     * Adds a pack's pictures to `settings`. `forced` is a FOLDERS index for a
     * pack of unsorted pictures (the person said what they are), or -1 to go
     * by the pack's folder names. Files already in `settings` stay.
     */
    public static Result add(Source source, File settings, int forced, int total, Progress progress) throws IOException {
        final Result result = new Result();
        final int[] done = {0};
        source.visit((path, in) -> {
            String dest = destination(path, forced);
            if (dest == null) return;
            File out = new File(settings, dest);
            boolean bmp = isPicture(out.getName());
            if (out.exists()) {
                if (bmp) result.kept++;
            }
            else {
                write(in, out);
                if (bmp) result.added[folderIndex(dest)]++;
            }
            if (progress != null) progress.at(++done[0], total);
        });
        return result;
    }

    /** Where a file of a pack goes inside settings, or null when it stays out. */
    static String destination(String path, int forced) {
        String[] seg = segments(path);
        if (seg == null || isJunk(seg)) return null;
        String name = seg[seg.length - 1];
        if (!isTaken(name)) return null;
        int folder = forced >= 0 ? forced : folderOf(seg);
        return folder < 0 ? null : FOLDERS[folder] + "/" + name;
    }

    /* ---- a whole settings folder ---- */

    /**
     * Replaces `settings` with the settings folder at `root` inside the
     * source. It is put together beside it first (settings.importing) and
     * checked; only then does the old folder become `backupName` (or
     * backupName-2...) and the new one take its place.
     *
     * THE TRAP: `settings` is where the link inside Wine points, so the new
     * folder must end up under exactly that name, and SAI's old settings are
     * never deleted, only renamed.
     */
    public static Result replace(Source source, String root, File settings, String backupName,
                                 int total, Progress progress) throws IOException {
        File parent = settings.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File importing = new File(parent, settings.getName() + ".importing");
        PrefixLinks.deleteTree(importing.toPath());
        final Result result = new Result();
        try {
            source.visit((path, in) -> {
                String[] seg = segments(path);
                if (seg == null || isJunk(seg)) return;
                String rel = under(seg, root);
                if (rel == null) return;
                write(in, new File(importing, rel));
                result.files++;
                if (progress != null) progress.at(result.files, total);
            });
            if (!new File(importing, SETTINGS_FILE).isFile()) throw new IOException("no " + SETTINGS_FILE + " in it");
        }
        catch (IOException | RuntimeException e) {
            PrefixLinks.deleteTree(importing.toPath());
            throw e;
        }
        if (settings.exists()) {
            result.backup = PrefixLinks.freeName(new File(parent, backupName));
            Files.move(settings.toPath(), result.backup.toPath());
        }
        try {
            Files.move(importing.toPath(), settings.toPath());
        }
        catch (IOException e) {
            if (result.backup != null) Files.move(result.backup.toPath(), settings.toPath());
            throw e;
        }
        return result;
    }

    /* ---- sources ---- */

    /**
     * A zip, opened anew for each read. Names are read as UTF-8; a zip made
     * on an older Windows has names in its old code page, which UTF-8
     * refuses, so such a zip is read again as IBM437 (or Latin-1).
     */
    public static Source zip(Opener opener) {
        return visitor -> {
            try {
                readZip(opener, StandardCharsets.UTF_8, visitor);
            }
            catch (IllegalArgumentException malformedName) {
                readZip(opener, legacyZipCharset(), visitor);
            }
        };
    }

    private static Charset legacyZipCharset() {
        try {
            return Charset.forName("IBM437");
        }
        catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static void readZip(Opener opener, Charset names, Visitor visitor) throws IOException {
        try (InputStream raw = opener.open(); ZipInputStream z = new ZipInputStream(raw, names)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory()) visitor.file(e.getName(), z);
            }
        }
    }

    /** A folder on the device, walked all the way down. */
    public static Source folder(File dir) {
        return visitor -> {
            Path base = dir.toPath();
            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> all = Files.walk(base)) {
                all.filter(Files::isRegularFile).forEach(files::add);
            }
            java.util.Collections.sort(files);
            for (Path p : files) {
                try (InputStream in = new FileInputStream(p.toFile())) {
                    visitor.file(base.relativize(p).toString().replace(File.separatorChar, '/'), in);
                }
            }
        };
    }

    /* ---- names ---- */

    /** The path's parts, or null for anything that could climb out. */
    static String[] segments(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return null;
        String[] seg = p.split("/+");
        for (String s : seg) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) return null;
        }
        return seg;
    }

    /** SAI's backups, Macs' extra files, and a SAI tool's backups. */
    static boolean isJunk(String[] seg) {
        for (int i = 0; i < seg.length; i++) {
            String s = seg[i];
            if (s.equals("__MACOSX") || s.startsWith("._") || s.equals(".DS_Store")) return true;
            if (i < seg.length - 1 && (s.equalsIgnoreCase("bak") || s.startsWith("SaiBristleCollectorBackup"))) return true;
        }
        return false;
    }

    private static boolean isPicture(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".bmp");
    }

    private static boolean isTaken(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".bmp") || n.endsWith(".ini");
    }

    /** The FOLDERS entry the file's own folder is named after, or -1. */
    private static int folderOf(String[] seg) {
        if (seg.length < 2) return -1;
        String parent = seg[seg.length - 2].toLowerCase(Locale.ROOT);
        for (int i = 0; i < FOLDERS.length; i++) {
            String f = FOLDERS[i];
            if (parent.equals(f.substring(f.lastIndexOf('/') + 1))) return i;
        }
        return -1;
    }

    private static int folderIndex(String dest) {
        for (int i = 0; i < FOLDERS.length; i++) {
            if (dest.startsWith(FOLDERS[i] + "/")) return i;
        }
        return 0;
    }

    private static String join(String[] seg, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i > 0 ? "/" : "").append(seg[i]);
        return b.toString();
    }

    /** The path below `root`, or null when it is not under it. */
    private static String under(String[] seg, String root) {
        String[] r = root.isEmpty() ? new String[0] : root.split("/");
        if (seg.length <= r.length) return null;
        for (int i = 0; i < r.length; i++) {
            if (!seg[i].equals(r[i])) return null;
        }
        String[] rest = new String[seg.length - r.length];
        System.arraycopy(seg, r.length, rest, 0, rest.length);
        return join(rest, rest.length);
    }

    /** Written beside the file and renamed: a cut-short copy never looks whole. */
    private static void write(InputStream in, File out) throws IOException {
        File dir = out.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File part = new File(dir, out.getName() + ".part");
        try {
            Files.copy(in, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        finally {
            Files.deleteIfExists(part.toPath());
        }
    }
}
