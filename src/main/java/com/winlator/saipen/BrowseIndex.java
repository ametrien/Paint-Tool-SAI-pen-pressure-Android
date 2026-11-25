package com.winlator.saipen;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything the browser shows, read in one go off the screen's thread, so a
 * page never fills in bit by bit. Read again when the settings change (an
 * import, a pack added).
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class BrowseIndex {
    public final File settings;
    /** SettingsContents' four picture kinds, each by name. */
    public final List<List<File>> pictures = new ArrayList<>();
    /** The same kinds put away out of SAI's sight (BrushVisibility). */
    public final List<List<File>> hidden = new ArrayList<>();
    public final List<SaiSettingsFiles.ToolSet> toolSets;
    /** Tools as SettingsContents counts them (the files, links left out). */
    public final int toolCount;
    public final List<Integer> recentColours;
    public final List<Swatch> swatches = new ArrayList<>();
    public final List<File> scratchPads;
    public final int windowScheme;
    /** settings2.ssd's time, 0 when there is none. */
    public final long preferencesTime;
    public final List<SaiSettingsFiles.Backup> saiBackups, importBackups;
    /** Pictures that come with SAI's starting values (NAME.ini). */
    public final List<File> withSettings = new ArrayList<>();

    public static final class Swatch {
        public final String name;
        public final List<Integer> colours;
        Swatch(String name, List<Integer> colours) { this.name = name; this.colours = colours; }
    }

    private BrowseIndex(File settings) {
        this.settings = settings;
        for (int k = 0; k < SettingsContents.TOOLS; k++) {
            pictures.add(SettingsContents.pictures(settings, k));
            hidden.add(BrushVisibility.hidden(settings, k));
        }
        toolSets = SaiSettingsFiles.toolSets(settings);
        toolCount = SettingsContents.tools(settings).size();
        recentColours = SaiSettingsFiles.colours(new File(settings, "colhis.saichis"));
        for (File f : SettingsContents.swatches(settings)) {
            swatches.add(new Swatch(SettingsContents.bareName(f), SaiSettingsFiles.colours(f)));
        }
        scratchPads = SettingsContents.scratchPads(settings);
        windowScheme = SaiSettingsFiles.windowScheme(settings);
        File prefs = new File(settings, "settings2.ssd");
        preferencesTime = prefs.isFile() ? prefs.lastModified() : 0;
        saiBackups = SaiSettingsFiles.saiBackups(settings);
        importBackups = SaiSettingsFiles.importBackups(settings);
        for (List<File> kind : pictures) {
            for (File f : kind) if (SaiSettingsFiles.iniOf(f) != null) withSettings.add(f);
        }
        withSettings.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    }

    public static BrowseIndex read(File settings) {
        return new BrowseIndex(settings);
    }

    public int pictureCount() {
        int n = 0;
        for (List<File> k : pictures) n += k.size();
        return n;
    }

    /** Every tool once, in set order; a tool in two sets (or linked) only the first time. */
    public List<SaiSettingsFiles.Tool> tools() {
        List<SaiSettingsFiles.Tool> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (SaiSettingsFiles.ToolSet s : toolSets) {
            for (SaiSettingsFiles.Tool t : s.tools) {
                String key = (t.linkedFrom != null ? t.linkedFrom : t.layerKind) + "/" + t.name;
                if (seen.contains(key)) continue;
                seen.add(key);
                out.add(t);
            }
        }
        return out;
    }

    /** Names containing the words of q (any letter case). */
    public static boolean matches(String name, String q) {
        if (q == null || q.trim().isEmpty()) return true;
        String n = name.toLowerCase(Locale.ROOT);
        for (String w : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!n.contains(w)) return false;
        return true;
    }

    public static <T> List<T> first(List<T> list, int n) {
        return list.size() <= n ? list : Collections.unmodifiableList(list.subList(0, n));
    }
}
