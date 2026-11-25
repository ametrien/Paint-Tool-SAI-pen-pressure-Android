package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chinese, Japanese and Korean text in SAI with a font that is already on
 * the device, as pure text: the SystemLink part of the Wine prefix's
 * system.reg. Ported from the Mac app (FontLinkCore.swift, 0.3.12).
 *
 * What is known: SAI draws its menus, panels and dialogs with GDI and asks
 * for Tahoma, MS UI Gothic, Meiryo UI and MS PGothic (in sai2.exe also
 * spelled ＭＳ Ｐゴシック). Winlator's Wine has only Tahoma (Latin), so every
 * Japanese character was garbled or a box. When a character is missing,
 * Wine tries the list for that font name under
 * HKLM\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink,
 * and Wine's lists only name Windows files (MSGOTHIC.TTC, SIMSUN.TTC...).
 * With a font of the device at the front of those five lists, it draws.
 *
 * SAI's own Text tool is NOT covered: it reads font files with its own
 * engine, which does not support CFF fonts (Noto Sans CJK is CFF).
 *
 * THE TRAPS:
 *
 *   1. Wine matches a list entry by FILE name and FAMILY name, both exactly
 *      (case aside). Android's Noto collection is NotoSansCJK-Regular.ttc,
 *      and its Japanese face is named "Noto Sans CJK JP" (read from the
 *      file on a Galaxy Tab, 2026-11-24). Wine already loads Android's
 *      /system/fonts (the prefix's Fonts key lists that folder's
 *      NotoSansCJK-Regular.ttc, through Wine's unix path), so the file is
 *      only named here: nothing is linked or copied.
 *
 *   2. Off must give Wine's own list back exactly, in order. Our entries
 *      are told apart from Wine's (Windows files: MSGOTHIC.TTC...), so
 *      Wine's list is the list without ours. Nothing has to be remembered,
 *      except the person's own fonts (`remembered`), which could be named
 *      anything.
 *
 *   3. A value that is there but cannot be read is LEFT ALONE.
 *
 *   4. For a font name that does not exist (MS UI Gothic and the other
 *      Japanese ones), Wine draws EVERY character with the first font in
 *      the list, Latin and Cyrillic too: on the Mac "Слой" came out as
 *      full-width "С л о й". So those lists start with Wine's own Tahoma
 *      (the Mac uses Arial, which Winlator's Wine does not have). Tahoma
 *      exists, so its own list needs no lead.
 *
 *   5. Never copied, bundled or downloaded: the device's fonts are only
 *      named. A font the person adds is their own file, for their use.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class FontLink {
    private FontLink() {}

    public static final String SECTION = "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink";

    /** The font names SAI asks for (strings in sai2.exe). */
    public static final String[] SAI_FONT_NAMES = {"Tahoma", "MS UI Gothic", "Meiryo UI", "MS PGothic", "ＭＳ Ｐゴシック"};

    /** Android's Noto Sans CJK: every phone and tablet since Android 7. */
    public static final String NOTO_FILE = "NotoSansCJK-Regular.ttc";
    public static final String NOTO_JP = entry(NOTO_FILE, "Noto Sans CJK JP");
    /** Wine's own Tahoma, first for the names that have no font (THE TRAP 4). */
    public static final String WINE_TAHOMA = entry("tahoma.ttf", "Tahoma");

    /** A Japanese font that comes with Android, in /system/fonts. */
    public static final class BuiltIn {
        public final String id, label, file, family;

        BuiltIn(String id, String label, String file, String family) {
            this.id = id;
            this.label = label;
            this.file = file;
            this.family = family;
        }

        public String entry() {
            return FontLink.entry(file, family);
        }
    }

    public static final String SYSTEM_FONTS = "/system/fonts";

    /** Offered when their file is on the device. Families read from the
     *  files on a Galaxy Tab (2026-11-24): Samsung's SECCJK-Regular.ttc calls
     *  its Japanese face "SEC CJK JP" (its full name says Noto, the family
     *  does not, and Wine matches the family). */
    public static final BuiltIn[] BUILT_IN = {
        new BuiltIn("noto", "Noto Sans CJK JP", NOTO_FILE, "Noto Sans CJK JP"),
        new BuiltIn("noto_serif", "Noto Serif CJK JP", "NotoSerifCJK-Regular.ttc", "Noto Serif CJK JP"),
        new BuiltIn("samsung", "Samsung (SEC CJK JP)", "SECCJK-Regular.ttc", "SEC CJK JP"),
    };

    /** The font choice SAI Pen remembers: a BuiltIn id, or OFF. */
    public static final String PREF_FONT = "saipen_cjk_font";
    public static final String OFF = "off";
    public static final String DEFAULT_FONT = "noto";

    public static BuiltIn builtIn(String id) {
        for (BuiltIn b : BUILT_IN) if (b.id.equals(id)) return b;
        return null;
    }

    /**
     * The entries to put first for a stored choice: the chosen font, then
     * Noto Sans CJK (it has every character), or none for off. Nothing
     * stored means Noto. `onDevice` says whether a font file is there.
     */
    public static List<String> fontsFor(String stored, java.util.function.Predicate<String> onDevice) {
        List<String> out = new ArrayList<>();
        if (OFF.equals(stored)) return out;
        BuiltIn chosen = builtIn(stored != null ? stored : DEFAULT_FONT);
        if (chosen == null) chosen = builtIn(DEFAULT_FONT);
        if (onDevice.test(chosen.file)) out.add(chosen.entry());
        BuiltIn noto = builtIn(DEFAULT_FONT);
        if (!out.contains(noto.entry()) && onDevice.test(noto.file)) out.add(noto.entry());
        return out;
    }

    /** Every entry this app writes on its own. Anything else is Wine's
     *  (or a remembered font of the person's). */
    static final String[] ALL_OURS = {
        WINE_TAHOMA, BUILT_IN[0].entry(), BUILT_IN[1].entry(), BUILT_IN[2].entry(),
    };

    public static String entry(String file, String family) {
        return file + "," + family;
    }

    /** A SystemLink value: a list, or one that could not be read. */
    public static final class Value {
        public final List<String> list;   /* null: unreadable */

        Value(List<String> list) {
            this.list = list;
        }

        boolean readable() {
            return list != null;
        }
    }

    /** Our entries for one of SAI's font names, in order. */
    public static List<String> entries(String name, List<String> fonts) {
        List<String> out = new ArrayList<>();
        if (!name.equalsIgnoreCase("Tahoma")) out.add(WINE_TAHOMA);
        out.addAll(fonts);
        return out;
    }

    static boolean isOurs(String entry, Collection<String> remembered) {
        for (String e : ALL_OURS) if (e.equalsIgnoreCase(entry)) return true;
        for (String e : remembered) if (e.equalsIgnoreCase(entry)) return true;
        return false;
    }

    /** Wine's own list: the list without our entries (THE TRAP 2). */
    static List<String> withoutOurs(List<String> list, Collection<String> remembered) {
        List<String> out = new ArrayList<>();
        for (String e : list) if (!isOurs(e, remembered)) out.add(e);
        return out;
    }

    private static String header() {
        return "[" + SECTION.replace("\\", "\\\\").toLowerCase(Locale.ROOT) + "]";
    }

    /** The SystemLink values, keyed by name as written. null when the
     *  section is not in the file (no prefix yet): nothing is touched. */
    public static Map<String, Value> systemLinks(String systemReg) {
        Map<String, Value> out = new LinkedHashMap<>();
        boolean inSection = false, found = false;
        for (String raw : systemReg.split("\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                /* "[Key] 1789057661": the number after the bracket is a timestamp */
                inSection = line.toLowerCase(Locale.ROOT).startsWith(header());
                found |= inSection;
                continue;
            }
            if (!inSection || !line.startsWith("\"")) continue;
            String[] nd = splitNameAndData(line);
            if (nd == null) continue;
            out.put(nd[0], parseList(nd[1]));
        }
        return found ? out : null;
    }

    private static Value parseList(String data) {
        if (!data.startsWith("str(7):\"") || !data.endsWith("\"") || data.length() < 9) return new Value(null);
        String body = unescape(data.substring(8, data.length() - 1));
        List<String> list = new ArrayList<>();
        for (String s : body.split("\0", -1)) if (!s.isEmpty()) list.add(s);
        return new Value(list);
    }

    /** `"Name"=data` -> {Name, data}, with the name's escapes undone. */
    private static String[] splitNameAndData(String line) {
        StringBuilder name = new StringBuilder();
        int i = 1;
        boolean escaped = false;
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                name.append('\\').append(c);
                escaped = false;
            }
            else if (c == '\\') escaped = true;
            else if (c == '"') break;
            else name.append(c);
        }
        if (i >= line.length() || i + 1 >= line.length() || line.charAt(i + 1) != '=') return null;
        return new String[] {unescape(name.toString()), line.substring(i + 2)};
    }

    /** Wine's escapes in .reg strings: \\ \" \0 \n \r \t, and \xNNNN for
     *  anything outside ASCII (1 to 4 hex digits). */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '0': out.append('\0'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'x': {
                    int j = i + 1, v = 0, k = 0;
                    while (k < 4 && j < s.length() && Character.digit(s.charAt(j), 16) >= 0) {
                        v = v * 16 + Character.digit(s.charAt(j), 16);
                        j++;
                        k++;
                    }
                    if (k == 0) out.append("\\x");
                    else out.appendCodePoint(v);
                    i = j - 1;
                    break;
                }
                default: out.append(n);   /* \\ and \" */
            }
        }
        return out.toString();
    }

    /** As Wine writes it: \\ \" \0, and \x with 4 digits outside ASCII. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '"') out.append("\\\"");
            else if (c == '\0') out.append("\\0");
            else if (c < 0x20 || c > 0x7e) out.append(String.format(Locale.ROOT, "\\x%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    static String valueLine(String name, List<String> list) {
        StringBuilder body = new StringBuilder();
        for (String e : list) body.append(e).append('\0');
        return "\"" + escape(name) + "\"=str(7):\"" + escape(body.toString()) + "\"";
    }

    /**
     * system.reg with SAI's five lists set: `fonts` in front of Wine's own
     * when `fonts` is not empty, Wine's own alone when it is (off). Returns
     * the same string when nothing changes, or when there is no SystemLink
     * section to change.
     */
    public static String apply(String systemReg, List<String> fonts, Collection<String> remembered) {
        Map<String, Value> current = systemLinks(systemReg);
        if (current == null) return systemReg;
        boolean on = !fonts.isEmpty();

        /* the line for each name: the new one, or null to drop the value */
        Map<String, String> lineFor = new LinkedHashMap<>();
        for (String name : SAI_FONT_NAMES) {
            String key = null;
            for (String k : current.keySet()) if (k.equalsIgnoreCase(name)) key = k;
            Value v = key != null ? current.get(key) : null;
            if (v != null && !v.readable()) continue;                     /* THE TRAP 3 */
            List<String> now = v != null ? v.list : null;
            List<String> wine = withoutOurs(now != null ? now : new ArrayList<>(), remembered);
            List<String> target;
            if (on) {
                target = entries(name, fonts);
                target.addAll(wine);
            }
            else target = wine.isEmpty() ? null : wine;
            List<String> nowOrNull = now == null || now.isEmpty() ? null : now;
            if (target == null ? nowOrNull == null : target.equals(nowOrNull)) continue;
            lineFor.put((key != null ? key : name).toLowerCase(Locale.ROOT),
                        target == null ? null : valueLine(key != null ? key : name, target));
        }
        if (lineFor.isEmpty()) return systemReg;

        String[] lines = systemReg.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        int sectionEnd = -1;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                if (inSection) sectionEnd = out.size();
                inSection = line.toLowerCase(Locale.ROOT).startsWith(header());
                out.add(raw);
                continue;
            }
            if (inSection && line.startsWith("\"")) {
                String[] nd = splitNameAndData(line);
                String k = nd != null ? nd[0].toLowerCase(Locale.ROOT) : null;
                if (k != null && lineFor.containsKey(k)) {
                    String nl = lineFor.remove(k);
                    if (nl != null) out.add(nl);
                    continue;
                }
            }
            out.add(raw);
        }
        if (inSection) sectionEnd = out.size();
        /* the names that had no value yet go at the end of the section,
         * before the blank line that ends it */
        int at = sectionEnd;
        while (at > 0 && out.get(at - 1).trim().isEmpty()) at--;
        for (String nl : lineFor.values()) if (nl != null) out.add(at++, nl);
        return String.join("\n", out);
    }
}
