package com.winlator.saipen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Report a problem" on SAI Pen's screen: everything that helps with a bug
 * report (SAI Pen's version, the device, its cores, how SAI is set up, how
 * the device is right now, how fast SAI drew here) as one text to paste into
 * Reddit or a GitHub issue. People wrote "it lags" without a device or a
 * version, and every answer started with questions.
 *
 * The text is shown before it is copied, and it leaves the device only if
 * the person pastes it somewhere.
 *
 * NEVER in it: the SAI licence (its file name holds the licence number, which
 * with the email's password downloads certificates from SYSTEMAX), the System
 * ID, file paths (they can hold names). So the launcher passes only
 * values it chose, and every value is still cleaned here (safe()).
 *
 * In English whatever the device's language: it is read by the author.
 *
 * No Android types: the launcher reads the values, the JVM tests check the text.
 */
public final class BugReport {
    /** "sai-" and a long number, as in a licence file's name. */
    private static final Pattern LICENCE = Pattern.compile("(?i)sai[-_ ]?\\d{6,}");
    /** A long run of digits anywhere (a licence or an ID without its "sai-"). */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{9,}");
    /** A SAI build in a folder or zip name: "sai2-Alpha-20261019-64bit-en". */
    private static final Pattern BUILD = Pattern.compile("(?i)(alpha|preview|technical[ ._-]?preview)[ ._-]?(\\d{4})[ ._-]?(\\d{2})[ ._-]?(\\d{2})([a-z])?");

    private final List<String> lines = new ArrayList<>();

    /** The first line, "SAI Pen 0.5.1 (58)". */
    public BugReport(String versionName, long versionCode) {
        lines.add("SAI Pen " + safe(versionName) + " (" + versionCode + ")");
    }

    /** "Key: value"; a null or empty value is left out. */
    public BugReport add(String key, String value) {
        if (value == null || value.trim().isEmpty()) return this;
        lines.add(key + ": " + safe(value));
        return this;
    }

    /** An indented line under a heading. */
    public BugReport item(String key, String value) {
        if (value == null || value.trim().isEmpty()) return this;
        lines.add("  " + key + ": " + safe(value));
        return this;
    }

    public BugReport heading(String name) {
        lines.add("");
        lines.add(name + ":");
        return this;
    }

    public String text() {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }

    /** Takes out anything like a licence number, a long ID or a path. */
    static String safe(String value) {
        String v = value.replace('\n', ' ').trim();
        v = LICENCE.matcher(v).replaceAll("(hidden)");
        v = LONG_NUMBER.matcher(v).replaceAll("(hidden)");
        if (v.contains("/") && v.matches(".*(/storage|/sdcard|/data|/emulated|/mnt).*")) return "(a path, hidden)";
        if (v.matches(".*[A-Za-z]:\\\\.*")) return "(a path, hidden)";
        return v;
    }

    /** "Alpha.2026.10.19" from a SAI folder's name, or null if the name doesn't say. */
    public static String saiBuild(String folderName) {
        if (folderName == null) return null;
        Matcher m = BUILD.matcher(folderName);
        if (!m.find()) return null;
        String kind = m.group(1).toLowerCase(Locale.ROOT).startsWith("alpha") ? "Alpha" : "Preview";
        return kind + "." + m.group(2) + "." + m.group(3) + "." + m.group(4) + (m.group(5) != null ? m.group(5).toLowerCase(Locale.ROOT) : "");
    }

    /** PowerManager's thermal status in words. */
    public static String thermal(int status) {
        switch (status) {
            case 0: return "none (cool)";
            case 1: return "light";
            case 2: return "moderate (slowing down)";
            case 3: return "severe (slowed down)";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "not known";
        }
    }

    /** "8: 2 fast (100%, 98%), 6 slower (41%)", from each core's strength
     *  in % (unit "%") or its top clock (unit " MHz"). */
    public static String cores(int[] values, int fastCount, String unit) {
        if (values == null || values.length == 0) return null;
        int n = values.length;
        if (fastCount <= 0 || fastCount >= n) return n + ", all alike";
        int[] s = values.clone();
        java.util.Arrays.sort(s);
        StringBuilder fast = new StringBuilder();
        for (int i = n - 1; i >= n - fastCount; i--) {
            if (fast.length() > 0) fast.append(", ");
            fast.append(s[i]).append(unit);
        }
        int slowMin = s[0], slowMax = s[n - fastCount - 1];
        String slow = slowMin == slowMax ? slowMin + unit : slowMin + unit + " to " + slowMax + unit;
        return n + ": " + fastCount + " fast (" + fast + "), " + (n - fastCount) + " slower (" + slow + ")";
    }

    public static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    /** "1.1 of 3.6 GB" */
    public static String gb(long mb) {
        return String.format(Locale.ROOT, "%.1f", mb / 1024f);
    }
}
