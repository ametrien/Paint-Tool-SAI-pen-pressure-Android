package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SAI's menu language: English or Japanese, the only two sai2.exe has (its
 * strings hold no other language; Russian and Chinese did nothing in the Mac
 * app's testing). SAI reads it once at start from [Locale] lang in sai2.ini,
 * next to sai2.exe. Ported from the Mac app (DisplayCore.swift, 0.3.12).
 *
 * The choice SAI Pen remembers: nothing or "device" (the default: SAI in
 * Japanese on a device set to Japanese, in English on any other), or "en"
 * or "ja", picked in the launcher. It is put into sai2.ini before every
 * start, so SAI follows the device when the person changes its language.
 *
 * sai2.ini as SAI ships it is UTF-16 little-endian with a byte-order mark
 * and CRLF lines; it is written back the same way, so the only bytes that
 * change are the language code.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
public final class SaiLanguage {
    private SaiLanguage() {}

    public static final String EN = "en";
    public static final String JA = "ja";
    /** Follow the device's language (also what nothing stored means). */
    public static final String DEVICE = "device";

    public static final String PREF_CHOICE = "saipen_sai_lang";

    public static boolean isLanguage(String code) {
        return EN.equals(code) || JA.equals(code);
    }

    /** SAI's language for a device language ("ja", "ja_JP", "ja-JP"...). */
    public static String forDevice(String deviceLanguage) {
        return deviceLanguage != null && deviceLanguage.toLowerCase(Locale.ROOT).startsWith("ja") ? JA : EN;
    }

    /** The lang to put into sai2.ini for the stored choice. */
    public static String target(String stored, String deviceLanguage) {
        return isLanguage(stored) ? stored : forDevice(deviceLanguage);
    }

    /** sai2.ini's text and how it was stored. */
    public static final class Ini {
        public final String text;
        public final boolean utf16;

        public Ini(String text, boolean utf16) {
            this.text = text;
            this.utf16 = utf16;
        }

        public static Ini decode(byte[] d) {
            if (d.length >= 2 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xfe) {
                return new Ini(new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE), true);
            }
            return new Ini(new String(d, StandardCharsets.UTF_8), false);
        }

        public byte[] encode() {
            if (!utf16) return text.getBytes(StandardCharsets.UTF_8);
            byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xff;
            out[1] = (byte) 0xfe;
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }

        private String newline() {
            return text.contains("\r\n") ? "\r\n" : "\n";
        }

        private List<String> lines() {
            return new ArrayList<>(Arrays.asList(text.split(java.util.regex.Pattern.quote(newline()), -1)));
        }

        private static boolean isSection(String l) {
            return l.trim().startsWith("[");
        }

        private static boolean isLocale(String l) {
            return l.trim().toLowerCase(Locale.ROOT).equals("[locale]");
        }

        /** `lang = en` (any spacing), but not a comment and not `language = …`. */
        private static String langValue(String l) {
            String t = l.trim();
            if (t.startsWith(";")) return null;
            int eq = t.indexOf('=');
            if (eq < 0) return null;
            if (!t.substring(0, eq).trim().toLowerCase(Locale.ROOT).equals("lang")) return null;
            return t.substring(eq + 1).trim();
        }

        public String language() {
            boolean inLocale = false;
            for (String l : lines()) {
                if (isSection(l)) {
                    inLocale = isLocale(l);
                    continue;
                }
                String v = inLocale ? langValue(l) : null;
                if (v != null) return v;
            }
            return null;
        }

        /** The same file with lang set to `code`. Only that line changes; the
         *  indentation before it is kept. A missing line or section is added. */
        public Ini withLanguage(String code) {
            List<String> ls = lines();
            boolean inLocale = false;
            int localeAt = -1;
            for (int i = 0; i < ls.size(); i++) {
                String l = ls.get(i);
                if (isSection(l)) {
                    inLocale = isLocale(l);
                    if (inLocale) localeAt = i;
                    continue;
                }
                if (inLocale && langValue(l) != null) {
                    int k = 0;
                    while (k < l.length() && (l.charAt(k) == ' ' || l.charAt(k) == '\t')) k++;
                    ls.set(i, l.substring(0, k) + "lang = " + code);
                    return new Ini(String.join(newline(), ls), utf16);
                }
            }
            if (localeAt >= 0) ls.add(localeAt + 1, "lang = " + code);
            else ls.addAll(0, Arrays.asList("[Locale]", "lang = " + code, ""));
            return new Ini(String.join(newline(), ls), utf16);
        }
    }

    /** The lang in sai2.ini in `saiDir`, or null if it can't be read. */
    public static String read(File saiDir) {
        try {
            return Ini.decode(Files.readAllBytes(new File(saiDir, "sai2.ini").toPath())).language();
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Before SAI starts: puts the language into sai2.ini. Returns the lang
     * written, or null when the file was left alone (already right, or no
     * sai2.ini).
     */
    public static String apply(File saiDir, String stored, String deviceLanguage) throws IOException {
        if (saiDir == null) return null;
        File f = new File(saiDir, "sai2.ini");
        if (!f.isFile()) return null;
        String target = target(stored, deviceLanguage);
        Ini ini = Ini.decode(Files.readAllBytes(f.toPath()));
        if (target.equals(ini.language())) return null;
        Files.write(f.toPath(), ini.withLanguage(target).encode());
        return target;
    }
}
