package com.winlator.saipen;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which files count as drawings: every kind SAI Ver.2's Open window lists as
 * "Image Files" (.sai2, .sai, .psd, .psb, .bmp, .jpg, .jpeg, .png, .tga). All
 * are shown unless the artist hides a kind in All drawings; what is hidden is
 * one preference, a comma list, so the start screen's row follows it too.
 *
 * .jpeg counts as jpg. No Android types: the JVM tests run it as it is.
 */
public final class DrawingFormats {
    private DrawingFormats() {}

    /** Every kind, in the order the chips show them. */
    public static final List<String> ALL = Collections.unmodifiableList(
        Arrays.asList("sai2", "sai", "psd", "psb", "png", "jpg", "bmp", "tga"));

    /** The hidden kinds, e.g. "png,jpg"; missing or empty: all shown. */
    public static final String PREF_HIDDEN = "drawings_hidden_formats";

    /** The file's kind ("sai2", "png", ...), or "" if it is not a drawing. */
    public static String of(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return "";
        String ext = n.substring(dot + 1);
        if (ext.equals("jpeg")) ext = "jpg";
        return ALL.contains(ext) ? ext : "";
    }

    public static boolean isDrawing(File f) {
        return !of(f).isEmpty();
    }

    /** The kinds the files have, in ALL's order. */
    public static List<String> present(Collection<File> files) {
        Set<String> have = new java.util.HashSet<>();
        for (File f : files) have.add(of(f));
        List<String> out = new ArrayList<>();
        for (String k : ALL) if (have.contains(k)) out.add(k);
        return out;
    }

    /** The preference's text as a set of kinds; unknown words are dropped. */
    public static Set<String> parse(String pref) {
        Set<String> out = new LinkedHashSet<>();
        if (pref == null) return out;
        for (String s : pref.split(",")) {
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (k.equals("jpeg")) k = "jpg";
            if (ALL.contains(k)) out.add(k);
        }
        return out;
    }

    /** The set back as the preference's text, in a fixed order. */
    public static String join(Set<String> hidden) {
        return String.join(",", new TreeSet<>(hidden));
    }

    /** The files whose kind is not hidden, in the same order. */
    public static List<File> shown(List<File> files, Set<String> hidden) {
        if (hidden.isEmpty()) return files;
        List<File> out = new ArrayList<>();
        for (File f : files) if (!hidden.contains(of(f))) out.add(f);
        return out;
    }
}
