package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds SAI settings brought over from a PC: a folder with settings2.ssd in
 * it, or a zip with settings2.ssd anywhere inside. That file is always there
 * and always has that name, so it is what is looked for; the zip itself can
 * be named anything ("settings.zip", "my sai.zip").
 *
 * Only on the person's "Look for it", never by itself (the owner wants no
 * search nobody asked for, see SaiLauncherActivity.changeSai). Only names
 * are read: a zip's list of files, never what is in them.
 *
 * THE TRAP: the device's own settings folder (and its settings-before-…
 * backups) has settings2.ssd too. Everything under `skip` is left out, or
 * SAI's own settings would be offered back to it.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class PcSettings {
    private PcSettings() {}

    /** A folder or a zip that holds a settings folder. */
    public static final class Found {
        public final File file;
        public final boolean zip;
        /** When it was made: the zip's, or the folder's settings2.ssd's. */
        public final long modified;

        Found(File file, boolean zip, long modified) {
            this.file = file;
            this.zip = zip;
            this.modified = modified;
        }
    }

    /** Settings folders and zips under root, down to maxDepth folders, newest
     *  first. Nothing under `skip` (may be null). */
    public static List<Found> find(File root, int maxDepth, File skip) {
        String skipPath = canonical(skip);
        List<Found> out = new ArrayList<>();
        SaiLocator.walk(root, maxDepth, dir -> {
            if (isUnder(dir, skipPath)) return;
            File ssd = SaiLocator.child(dir, BrushImport.SETTINGS_FILE);
            if (ssd != null && ssd.isFile()) out.add(new Found(dir, false, ssd.lastModified()));
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                if (hasSettings(f)) out.add(new Found(f, true, f.lastModified()));
            }
        });
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** settings2.ssd anywhere in the zip's list of files. A zip that cannot
     *  be read is not one. */
    static boolean hasSettings(File zip) {
        try {
            return hasSettings(zip, StandardCharsets.UTF_8);
        }
        catch (IOException | IllegalArgumentException malformedName) {
            /* names from an older Windows, in its old code page */
            try {
                return hasSettings(zip, legacyCharset());
            }
            catch (IOException | IllegalArgumentException e) {
                return false;
            }
        }
    }

    private static boolean hasSettings(File zip, Charset names) throws IOException {
        try (ZipFile z = new ZipFile(zip, names)) {
            Enumeration<? extends ZipEntry> all = z.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (e.isDirectory()) continue;
                String[] seg = BrushImport.segments(e.getName());
                if (seg == null || BrushImport.isJunk(seg)) continue;
                if (seg[seg.length - 1].equalsIgnoreCase(BrushImport.SETTINGS_FILE)) return true;
            }
            return false;
        }
    }

    private static Charset legacyCharset() {
        try {
            return Charset.forName("IBM437");
        }
        catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static String canonical(File f) {
        if (f == null) return null;
        try {
            return f.getCanonicalPath();
        }
        catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    private static boolean isUnder(File dir, String skipPath) {
        if (skipPath == null) return false;
        String p = canonical(dir);
        return p.equals(skipPath) || p.startsWith(skipPath + File.separator);
    }
}
