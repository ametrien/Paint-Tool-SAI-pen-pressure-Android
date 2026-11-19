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
}
