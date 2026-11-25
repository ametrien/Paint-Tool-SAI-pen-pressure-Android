package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Folders of the Wine prefix that should be folders of the tablet.
 *
 * SAI saves where its file dialog opens: the Windows user folder (users/xuser), inside the app,
 * where no Android file manager can see it (a drawing saved on 2026-11-18
 * was found there only over adb). So users/xuser/Downloads becomes a
 * link to the tablet's own Downloads: it is right there in SAI's save
 * dialog, and Windows remembers the folder after the first save into it.
 *
 * No Android types: the JVM tests make real links on the Mac.
 */
public final class PrefixLinks {
    private PrefixLinks() {}

    /**
     * Makes `folder` a symbolic link to `target`. Returns true when the link
     * is in place (made now or there already).
     *
     * THE TRAP: never throw files away. A real folder is replaced only when
     * it is empty; with files in it nothing changes and false comes back.
     */
    public static boolean linkFolder(File folder, File target) throws IOException {
        Path link = folder.toPath();
        Path to = target.toPath();
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(to)) return true;
            Files.delete(link);                     /* a link elsewhere: only a link goes */
        }
        else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            String[] inside = folder.list();
            if (!folder.isDirectory() || inside == null || inside.length > 0) return false;
            Files.delete(link);
        }
        File parent = folder.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.createSymbolicLink(link, to);
        return true;
    }

    /**
     * linkFolder() for a folder SAI has already filled: SAI's own settings
     * (brushes, textures, presets), which live inside the app until 0.4.21.
     * Returns true when the link is in place.
     *
     * - `target` already has files (the person copied their PC's settings
     *   there): they are used, nothing is mixed in.
     * - `target` is empty or missing: the folder's files are copied there
     *   first, into a side folder that is renamed only once complete, so a
     *   half copy never looks like the person's own settings.
     *
     * Either way the folder itself is kept, renamed to `backup` (or
     * backup-2, -3...), and only then replaced by the link.
     *
     * THE TRAP: never throw settings away, and never leave SAI without
     * them. On any failure the folder is where it was and SAI starts with
     * it as before.
     */
    public static boolean moveIntoAndLink(File folder, File target, File backup) throws IOException {
        Path link = folder.toPath();
        boolean nothingToMove = Files.isSymbolicLink(link) || !Files.exists(link, LinkOption.NOFOLLOW_LINKS);
        String[] inside = nothingToMove ? new String[0] : folder.list();
        if (!nothingToMove && (!folder.isDirectory() || inside == null)) return false;
        if (inside.length == 0) {
            /* a link to nothing would leave SAI unable to make its folder */
            if (!target.isDirectory() && !target.mkdirs()) return false;
            return linkFolder(folder, target);
        }

        if (isEmptyOrMissing(target)) {
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            File side = new File(parent, target.getName() + ".copying");
            deleteTree(side.toPath());              /* a copy cut short last time */
            try {
                copyTree(link, side.toPath());
                if (!hasEveryFile(link, side.toPath())) throw new IOException("copy incomplete: " + side);
                if (target.exists()) Files.delete(target.toPath());   /* empty, checked above */
                Files.move(side.toPath(), target.toPath());
            }
            catch (IOException | RuntimeException e) {
                deleteTree(side.toPath());
                return false;
            }
        }
        if (!target.isDirectory()) return false;

        File kept = freeName(backup);
        Files.move(link, kept.toPath());
        try {
            if (linkFolder(folder, target)) return true;
        }
        catch (IOException e) {
            Files.move(kept.toPath(), link);        /* SAI keeps its own folder */
            throw e;
        }
        Files.move(kept.toPath(), link);
        return false;
    }

    private static boolean isEmptyOrMissing(File dir) {
        if (!dir.exists()) return true;
        String[] list = dir.list();
        return dir.isDirectory() && list != null && list.length == 0;
    }

    static File freeName(File wanted) {
        File f = wanted;
        for (int i = 2; f.exists() || Files.isSymbolicLink(f.toPath()); i++) {
            f = new File(wanted.getParentFile(), wanted.getName() + "-" + i);
        }
        return f;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> all = Files.walk(from)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                Path dst = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dst);
                else Files.copy(p, dst);            /* no attributes: shared storage refuses some */
            }
        }
    }

    /** Every file under `from` is under `to` too, with the same size. */
    private static boolean hasEveryFile(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> all = Files.walk(from)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                if (Files.isDirectory(p)) continue;
                Path dst = to.resolve(from.relativize(p).toString());
                if (!Files.isRegularFile(dst) || Files.size(dst) != Files.size(p)) return false;
            }
        }
        return true;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> all = Files.walk(root)) {
            java.util.List<Path> paths = new java.util.ArrayList<>();
            all.forEach(paths::add);
            java.util.Collections.reverse(paths);   /* files before their folders */
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }
}
