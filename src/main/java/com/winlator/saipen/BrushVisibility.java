package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Which brush textures, paper textures, brush shapes and scatter shapes SAI
 * sees. A big collection makes SAI's lists thousands long; the person picks
 * the ones they use and the rest are put away.
 *
 * SAI lists whatever pictures are in its folders when it starts, so hiding
 * one is moving it (and its NAME.ini, SAI's starting values) out of settings
 * into a folder beside it, at the same place inside: SAIv2/hidden-from-sai/
 * brushtex/Canvas.bmp for settings/brushtex/Canvas.bmp. Showing it moves it
 * back. Nothing is deleted, and nothing is overwritten: when a picture of the
 * same name is already where it would go, it stays where it is.
 *
 * Beside settings, not inside: a settings folder brought from a PC replaces
 * settings as a whole, and the hidden pictures must not go with the old one.
 *
 * SAI sees the change the next time it starts.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class BrushVisibility {
    private BrushVisibility() {}

    /** The folder beside settings, in SAIv2, where hidden pictures wait. */
    public static final String HIDDEN = "hidden-from-sai";

    public static File hiddenFolder(File settings) {
        return new File(settings.getAbsoluteFile().getParentFile(), HIDDEN);
    }

    /** The hidden pictures of one of SettingsContents' kinds, by name. */
    public static List<File> hidden(File settings, int kind) {
        return SettingsContents.pictures(hiddenFolder(settings), kind);
    }

    /** A pack being added: a picture of that name is hidden already (path
     *  inside settings, as BrushImport.destination gives it). */
    public static boolean isHidden(File settings, String path) {
        return new File(hiddenFolder(settings), path).exists();
    }

    public static final class Result {
        public int moved;
        /** Names that stayed: one of that name is already where they would go. */
        public final List<String> taken = new ArrayList<>();
        /** Names that could not be moved (the file system said no). */
        public final List<String> failed = new ArrayList<>();
    }

    /** Moves pictures in settings out of SAI's sight. */
    public static Result hide(File settings, List<File> pictures) {
        return move(settings.getAbsoluteFile(), hiddenFolder(settings), pictures);
    }

    /** Moves hidden pictures back into settings. */
    public static Result show(File settings, List<File> pictures) {
        return move(hiddenFolder(settings), settings.getAbsoluteFile(), pictures);
    }

    private static Result move(File fromRoot, File toRoot, List<File> pictures) {
        Result r = new Result();
        for (File p : pictures) {
            String rel = relative(fromRoot, p.getAbsoluteFile());
            if (rel == null || !p.isFile()) { r.failed.add(p.getName()); continue; }
            File to = new File(toRoot, rel);
            File ini = SaiSettingsFiles.iniOf(p);
            File iniTo = ini == null ? null : new File(to.getParentFile(), ini.getName());
            if (to.exists() || (iniTo != null && iniTo.exists())) { r.taken.add(p.getName()); continue; }
            try {
                Files.createDirectories(to.getParentFile().toPath());
                Files.move(p.toPath(), to.toPath());
                if (ini != null) {
                    try { Files.move(ini.toPath(), iniTo.toPath()); }
                    catch (IOException e) {
                        /* never a picture without its starting values: put it back */
                        Files.move(to.toPath(), p.toPath());
                        throw e;
                    }
                }
                r.moved++;
            }
            catch (IOException | RuntimeException e) {
                r.failed.add(p.getName());
            }
        }
        return r;
    }

    /** "brushtex/Canvas.bmp" for root/brushtex/Canvas.bmp; null when not under root. */
    static String relative(File root, File f) {
        String r = root.getPath(), p = f.getPath();
        if (!p.startsWith(r + File.separator)) return null;
        String rel = p.substring(r.length() + 1);
        for (String seg : rel.split("[/\\\\]")) if (seg.equals("..") || seg.isEmpty()) return null;
        return rel.replace(File.separatorChar, '/');
    }
}
