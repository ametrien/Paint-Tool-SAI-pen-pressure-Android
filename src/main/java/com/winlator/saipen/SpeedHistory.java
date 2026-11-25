package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How fast SAI drew on this device, from its own speed logs
 * (SpeedSession.FOLDER), for DeviceReadiness: pen to screen per screen
 * sharpness, and in the last sessions. Strokes are counted as in
 * CoresCompare: only after the first CoresCompare.SKIP_STROKES of a
 * session, which are slower whatever the settings.
 *
 * No Android types: the JVM tests use log texts.
 */
public final class SpeedHistory {
    /** A sharpness is compared only with at least this many sessions of it. */
    public static final int MIN_SESSIONS = 2;
    /** "The last sessions": this many, newest first. */
    public static final int RECENT = 5;

    /** "SAI's screen 2000x1200, display 60 Hz" */
    private static final Pattern SCREEN = Pattern.compile("SAI's screen \\d+x(\\d+)");

    public final Map<Integer, CoresCompare.Result> bySharpness = new TreeMap<>();
    /** median pen to screen in the last RECENT sessions with strokes, -1 if too few */
    public final int recentMs;

    private SpeedHistory(int recentMs) {
        this.recentMs = recentMs;
    }

    static int sharpnessOf(String log) {
        Matcher m = SCREEN.matcher(log);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** logs: oldest first. */
    public static SpeedHistory fromTexts(List<String> logs) {
        List<List<Integer>> sessions = new ArrayList<>();
        Map<Integer, CoresCompare.Result> by = new TreeMap<>();
        for (String log : logs) {
            List<Integer> s = CoresCompare.strokes(log);
            if (s.isEmpty()) continue;
            sessions.add(s);
            int h = sharpnessOf(log);
            if (h <= 0) continue;
            CoresCompare.Result r = by.computeIfAbsent(h, k -> new CoresCompare.Result());
            r.sessions++;
            r.toScreen.addAll(s);
        }
        CoresCompare.Result recent = new CoresCompare.Result();
        for (int i = Math.max(0, sessions.size() - RECENT); i < sessions.size(); i++) recent.toScreen.addAll(sessions.get(i));
        SpeedHistory h = new SpeedHistory(recent.median());
        h.bySharpness.putAll(by);
        return h;
    }

    /** The speed logs in `dir`, oldest first by their names ("speed yyyy-MM-dd HH-mm-ss ..."). */
    public static SpeedHistory fromFolder(File dir) {
        List<String> texts = new ArrayList<>();
        File[] logs = dir.listFiles((d, n) -> n.startsWith("speed ") && n.endsWith(".txt"));
        if (logs != null) {
            Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : logs) {
                try {
                    texts.add(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
                catch (IOException ignored) {
                    /* unreadable: left out */
                }
            }
        }
        return fromTexts(Collections.unmodifiableList(texts));
    }
}
