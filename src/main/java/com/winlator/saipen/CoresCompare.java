package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the fast-cores choices (SaiCores) from the speed logs already on
 * the device, so the person drawing can pick after a few sessions of each:
 * SAI Pen's screen shows each choice's touch-to-screen time next to it.
 *
 * A log says its choice in its "cores: ... SAI's threads: threads" line.
 * Logs from before 0.4.7 have no such line: nothing was pinned then, so
 * they count as "off".
 *
 * THE TRAP: the first strokes of every session are slower, whatever the
 * choice (2026-11-19: about 40 ms for strokes 1 to 10 in both the "threads"
 * and the "all" session, 24 to 28 ms after). A choice tried in short
 * sessions would look slow for that alone. So only strokes after the first
 * SKIP_STROKES of a session count.
 *
 * No Android types: the JVM tests use log texts.
 */
public final class CoresCompare {
    static final int SKIP_STROKES = 10;
    /** Fewer strokes than this for a choice: too few to show a number. */
    public static final int MIN_STROKES = 20;

    private static final Pattern MODE = Pattern.compile("SAI's threads: (\\w+)");
    private static final Pattern STROKE = Pattern.compile("stroke (\\d+) .*to screen (\\d+) ms");

    public static final class Result {
        public int sessions;
        public final List<Integer> toScreen = new ArrayList<>();

        public int strokes() {
            return toScreen.size();
        }

        /** Median touch to screen in ms, -1 if too few strokes. */
        public int median() {
            if (toScreen.size() < MIN_STROKES) return -1;
            List<Integer> s = new ArrayList<>(toScreen);
            Collections.sort(s);
            int n = s.size();
            return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
        }
    }

    private CoresCompare() {}

    /** One log's choice, and its strokes' touch to screen after SKIP_STROKES. */
    static SaiCores.Mode modeOf(String log) {
        Matcher m = MODE.matcher(log);
        if (!m.find()) return SaiCores.Mode.OFF;
        switch (m.group(1)) {
            case "threads": return SaiCores.Mode.THREADS;
            case "all": return SaiCores.Mode.ALL;
            default: return SaiCores.Mode.OFF;
        }
    }

    static List<Integer> strokes(String log) {
        List<Integer> out = new ArrayList<>();
        for (String line : log.split("\n")) {
            Matcher m = STROKE.matcher(line);
            if (m.find() && Integer.parseInt(m.group(1)) > SKIP_STROKES) out.add(Integer.parseInt(m.group(2)));
        }
        return out;
    }

    public static Map<SaiCores.Mode, Result> fromTexts(List<String> logs) {
        Map<SaiCores.Mode, Result> out = new EnumMap<>(SaiCores.Mode.class);
        for (SaiCores.Mode m : SaiCores.Mode.values()) out.put(m, new Result());
        for (String log : logs) {
            List<Integer> s = strokes(log);
            /* a log with no strokes past the first ones says nothing */
            if (s.isEmpty()) continue;
            Result r = out.get(modeOf(log));
            r.sessions++;
            r.toScreen.addAll(s);
        }
        return out;
    }

    /** The speed logs in `dir` (SpeedSession.FOLDER). */
    public static Map<SaiCores.Mode, Result> fromFolder(File dir) {
        List<String> texts = new ArrayList<>();
        File[] logs = dir.listFiles((d, n) -> n.startsWith("speed ") && n.endsWith(".txt"));
        if (logs != null) {
            for (File f : logs) {
                try {
                    texts.add(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
                catch (IOException ignored) {
                    /* unreadable: left out */
                }
            }
        }
        return fromTexts(texts);
    }
}
