package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Drawings saved inside the app, moved to the device.
 *
 * SAI2's own file browser opens in the Windows user folder (users/xuser),
 * inside the app, and keeps the last folder in SAI's encrypted settings, so
 * SAI Pen cannot make it open in SAI Drawings (GitHub issue #2). A drawing
 * saved there, or in Desktop, Documents or Pictures, is out of reach of
 * Android's file managers and goes with an uninstall. So before SAI starts
 * and after it closes, each one is moved to Documents/SAI Drawings, which SAI
 * sees as users/xuser/SAI Drawings (a link to the folder, PrefixLinks): it is
 * opened and saved again from there.
 *
 * Nothing is left in its place. A link to the single file was tried in 0.4.23:
 * SAI opened the drawing through it but could not save it again ("Cannot
 * save the canvas. Invalid parameter."), while saving into the linked folder
 * works. Such links left by 0.4.23 are taken away (only links into the
 * device folder: the file they point to stays).
 *
 * THE TRAP: never lose a drawing. Nothing on the device is overwritten: a
 * name already taken by another drawing gives "name (2).sai2"; the same
 * drawing already there only lets the in-app copy go. A file inside the app
 * is deleted only once its copy on the device is complete; any failure leaves
 * it where it was.
 *
 * No Android types: the JVM tests make real files and links on the Mac.
 */
public final class DeviceDrawings {
    private DeviceDrawings() {}

    /** What SAI Ver.2 saves and exports: the file types in its dialogs. */
    static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "sai2", "sai", "psd", "psb", "png", "jpg", "jpeg", "bmp", "tga"));

    /**
     * The folders of users/xuser looked at ("" is users/xuser itself): only
     * the files right in them, never deeper (AppData, SAI's settings under
     * Documents). SAI Drawings and Downloads are links to the device and so
     * are passed over; they are here for when making that link failed.
     */
    public static final String[] FOLDERS = {"", "Desktop", "Documents", "Pictures", "SAI Drawings", "Downloads"};

    public static boolean isDrawing(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Moves each drawing lying right in one of `folders` to `target`. Returns
     * the device files drawings were moved to this time (empty when there was
     * nothing to move or `target` cannot be made).
     */
    public static List<File> moveOut(List<File> folders, File target) {
        List<File> moved = new ArrayList<>();
        if (!target.isDirectory() && !target.mkdirs()) return moved;   /* no access to the device's storage */
        Path targetDir = target.toPath().toAbsolutePath().normalize();

        for (File folder : folders) {
            Path dir = folder.toPath();
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir)) continue;
            String[] names = folder.list();
            if (names == null) continue;
            Arrays.sort(names);
            for (String name : names) {
                if (!isDrawing(name)) continue;
                Path at = new File(folder, name).toPath();
                try {
                    if (Files.isSymbolicLink(at)) {
                        /* 0.4.23's link to a device file: SAI cannot save through it */
                        Path to = Files.readSymbolicLink(at);
                        if (to.isAbsolute() && to.normalize().startsWith(targetDir)) Files.delete(at);
                        continue;
                    }
                    if (!Files.isRegularFile(at, LinkOption.NOFOLLOW_LINKS)) continue;
                    moved.add(moveOne(at, target));
                }
                catch (IOException | RuntimeException e) {
                    /* the drawing stays where it is; the next pass tries again */
                }
            }
        }
        return moved;
    }

    /** Copies the drawing at `at` to the device, then deletes it inside the
     *  app. Returns the device file. */
    private static File moveOne(Path at, File target) throws IOException {
        String name = at.getFileName().toString();
        File dest = new File(target, name);
        if (dest.exists() && sameContent(at, dest.toPath())) {
            Files.delete(at);                           /* already on the device */
            return dest;
        }
        if (dest.exists() || Files.isSymbolicLink(dest.toPath())) dest = freeName(target, name);

        Path to = dest.toPath();
        Path part = new File(target, "." + dest.getName() + ".saipen-part").toPath();
        Files.deleteIfExists(part);                     /* a copy cut short last time */
        try {
            Files.copy(at, part);                       /* no attributes: shared storage refuses some */
            if (Files.size(part) != Files.size(at)) throw new IOException("copy incomplete: " + part);
            try {
                Files.setLastModifiedTime(part, Files.getLastModifiedTime(at));
            }
            catch (IOException ignored) {
                /* the time of the copy then: the drawing itself is whole */
            }
            Files.move(part, to);                       /* fails if the name was taken meanwhile */
        }
        catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.delete(at);
        return dest;
    }

    /** "name (2).ext", "name (3).ext"...: the first not taken in `dir`. */
    static File freeName(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            File f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return f;
        }
    }

    static boolean sameContent(Path a, Path b) throws IOException {
        if (Files.size(a) != Files.size(b)) return false;
        byte[] x = new byte[64 * 1024], y = new byte[64 * 1024];
        try (InputStream ia = Files.newInputStream(a); InputStream ib = Files.newInputStream(b)) {
            while (true) {
                int n = fill(ia, x), m = fill(ib, y);
                if (n != m) return false;
                if (n == 0) return true;
                for (int i = 0; i < n; i++) if (x[i] != y[i]) return false;
            }
        }
    }

    /** Reads until `buf` is full or the stream ends (readNBytes is API 33). */
    private static int fill(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }
}
