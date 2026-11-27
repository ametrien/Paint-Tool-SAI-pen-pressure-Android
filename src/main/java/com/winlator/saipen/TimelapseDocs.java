package com.winlator.saipen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which file each recorded document was opened from and saved to, as the DLL
 * saw SAI open and save drawing files (C:\sai-timelapse\documents.txt, new at
 * every SAI start; see PLAN-TIMELAPSE-WHICH-DRAWING.md):
 *
 *   start      <tick ms>
 *   <tick ms>  <document id, hex>  open|save  <path>  <document name>
 *
 * The document id is the frames' canvas_id: it names one document for as
 * long as it was open, whatever its name (a Save As renames it). The ticks
 * count on the frames' tick_ms clock.
 *
 * The app copies the file next to the SAI start's frames while SAI runs, so
 * it stays with them. A session is linked only to the lines of its own SAI
 * start: a file whose start is later than the session's first frame belongs
 * to another start, and says nothing about it.
 *
 * No Android types: the JVM tests check it with real files.
 */
public final class TimelapseDocs {
    private TimelapseDocs() {}

    public static final String FILE = "documents.txt";

    /** What is known about one document's files. */
    public static final class Doc {
        /** The file it was opened from, "" for a new canvas or when unknown. */
        public String openedFrom = "";
        /** Every file it was saved to, oldest first, no repeats. */
        public final List<String> savedTo = new ArrayList<>();
        /** Whether documents.txt was there for its SAI start at all. */
        public boolean known;

        public boolean isEmpty() {
            return openedFrom.isEmpty() && savedTo.isEmpty();
        }

        /** The name the drawing goes by: the last file saved to, else the
         *  one opened, without folder or extension; "" if neither. */
        public String title() {
            String p = !savedTo.isEmpty() ? savedTo.get(savedTo.size() - 1) : openedFrom;
            return p.isEmpty() ? "" : stem(p);
        }
    }

    /** C:\sai-timelapse\documents.txt in the container. */
    public static File source(File containerRoot) {
        return new File(containerRoot, ".wine/drive_c/sai-timelapse/" + FILE);
    }

    /** Copies the DLL's file next to this SAI start's frames when it has
     *  changed. Quiet when there is none (an older DLL, or recording off). */
    public static void copyInto(File source, File sessionDir) throws IOException {
        if (!source.isFile() || source.length() == 0) return;
        File dest = new File(sessionDir, FILE);
        if (dest.isFile() && dest.length() == source.length()) return;
        if (!sessionDir.isDirectory() && !sessionDir.mkdirs()) return;
        File tmp = new File(sessionDir, FILE + ".tmp");
        Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("cannot write " + dest);
        }
    }

    /** What the session folder's documents.txt says about one document
     *  whose first frame was taken at `firstTick`. */
    public static Doc of(File sessionDir, long canvasId, long firstTick) {
        Doc d = new Doc();
        File f = new File(sessionDir, FILE);
        if (!f.isFile()) return d;
        long start = -1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] c = line.split("\t", -1);
                if (c.length >= 2 && c[0].equals("start")) {
                    Long t = num(c[1], 10);
                    if (t != null) start = t;
                    continue;
                }
                if (c.length < 5 || start < 0) continue;      /* half a line, or no start yet */
                Long id = num(c[1], 16);
                if (id == null || id != canvasId || c[3].isEmpty()) continue;
                if (c[2].equals("open")) {
                    if (d.openedFrom.isEmpty()) d.openedFrom = c[3];
                }
                else if (c[2].equals("save")) {
                    boolean seen = false;
                    for (String s : d.savedTo) seen |= key(s).equals(key(c[3]));
                    if (!seen) d.savedTo.add(c[3]);
                }
            }
        }
        catch (IOException e) {
            return new Doc();
        }
        if (start < 0 || firstTick < start) return new Doc();    /* another SAI start's file */
        d.known = true;
        return d;
    }

    /** Two paths name the same file: case and / against \ do not matter. */
    public static String key(String path) {
        return path == null ? "" : path.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    /** "D:\SAI Drawings\art.sai2" -> "art". */
    public static String stem(String path) {
        String p = path.replace('/', '\\');
        int b = Math.max(p.lastIndexOf('\\'), p.lastIndexOf(':')) + 1;
        String name = p.substring(b);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Long num(String s, int radix) {
        try {
            return Long.parseUnsignedLong(s.trim(), radix);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}
