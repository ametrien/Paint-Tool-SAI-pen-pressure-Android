package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One button of the floating keys (FloatingKeys): a key combination, and
 * what a tap on it does.
 * - ONCE: the combination is pressed once, like Ctrl+Z (undo) or Ctrl+T
 *   (SAI's Transform);
 * - HELD: the combination goes down and stays down, the button lit, until
 *   the next tap (or while a finger stays on it). Ctrl held, for example,
 *   for a perspective ruler's handles, which move with Ctrl and the pen.
 *
 * - EXIT_SAI: no keys; closes SAI the way its red cross does (FloatingKeys,
 *   ExitTaps), kept in the list as the line "Exit SAI". Only the floating
 *   keys' list knows it (parseList): parse() never gives it, so a gesture's
 *   key stays a key.
 *
 * Each person picks their own buttons, up to MAX. Written the way people
 * write keys ("Ctrl+Shift+T", modifiers in the order Ctrl, Alt, Shift; a
 * modifier alone is fine for a held button), and kept in the preferences
 * one per line, a held one starting with "hold ".
 *
 * No Android types: the JVM tests check parsing and the list.
 */
public final class Shortcut {
    public static final int CTRL = 1, ALT = 2, SHIFT = 4;

    /** Buttons at most: one row on a tablet, two on a phone. */
    public static final int MAX = 8;

    /** The first time: Ctrl to hold, undo and redo next to it, transform,
     *  select all and deselect, Hue and Saturation, and Enter to finish a
     *  transform. */
    public static final String DEFAULTS = "hold Ctrl\nCtrl+Z\nCtrl+Y\nCtrl+T\nCtrl+A\nCtrl+D\nCtrl+U\nEnter";

    private static final String HOLD = "hold ";
    /** The Exit SAI button, as kept in the list and as its label. */
    public static final String EXIT_SAI = "Exit SAI";

    /** The keys a combination can end in, in the picker's order, and the X
     *  key (com.winlator.xserver.XKeycode) each one is. */
    public static final List<String> KEYS;
    private static final List<String> XKEYS;
    static {
        List<String> k = new ArrayList<>(), x = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) { k.add(String.valueOf(c)); x.add("KEY_" + c); }
        for (char c = '0'; c <= '9'; c++) { k.add(String.valueOf(c)); x.add("KEY_" + c); }
        String[][] more = {
            {"Enter", "KEY_ENTER"}, {"Esc", "KEY_ESC"}, {"Tab", "KEY_TAB"}, {"Space", "KEY_SPACE"},
            {"Delete", "KEY_DEL"}, {"Backspace", "KEY_BKSP"},
            {"Up", "KEY_UP"}, {"Down", "KEY_DOWN"}, {"Left", "KEY_LEFT"}, {"Right", "KEY_RIGHT"},
            {"Home", "KEY_HOME"}, {"End", "KEY_END"}, {"PageUp", "KEY_PRIOR"}, {"PageDown", "KEY_NEXT"},
            {"[", "KEY_BRACKET_LEFT"}, {"]", "KEY_BRACKET_RIGHT"}, {"-", "KEY_MINUS"}, {"=", "KEY_EQUAL"},
            {",", "KEY_COMMA"}, {".", "KEY_PERIOD"}, {"/", "KEY_SLASH"}, {";", "KEY_SEMICOLON"},
            {"'", "KEY_APOSTROPHE"}, {"\\", "KEY_BACKSLASH"}, {"`", "KEY_GRAVE"},
        };
        for (String[] m : more) { k.add(m[0]); x.add(m[1]); }
        for (int i = 1; i <= 12; i++) { k.add("F" + i); x.add("KEY_F" + i); }
        KEYS = Collections.unmodifiableList(k);
        XKEYS = Collections.unmodifiableList(x);
    }
    /** The X keys of Ctrl, Alt and Shift (the left ones), in that order. */
    public static final String[] MOD_XKEYS = {"KEY_CTRL_L", "KEY_ALT_L", "KEY_SHIFT_L"};

    public final int modifiers;
    /** One of KEYS, or null for modifiers alone (held buttons only). */
    public final String key;
    public final boolean held;
    /** The Exit SAI button: no keys, never held. */
    public final boolean exitSai;

    public Shortcut(int modifiers, String key, boolean held) {
        this.modifiers = modifiers & (CTRL | ALT | SHIFT);
        this.key = key;
        /* a modifier alone pressed once does nothing in SAI: it is always held */
        this.held = held || key == null;
        this.exitSai = false;
    }

    private Shortcut() {
        modifiers = 0;
        key = null;
        held = false;
        exitSai = true;
    }

    /** The Exit SAI button. */
    public static Shortcut exitSai() {
        return new Shortcut();
    }

    /** "Ctrl+Shift+T", "hold Ctrl" -> the button; null for anything that is
     *  not one (nothing, an unknown key). Case and spaces don't matter. */
    public static Shortcut parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        boolean held = false;
        if (t.toLowerCase(Locale.ROOT).startsWith(HOLD)) {
            held = true;
            t = t.substring(HOLD.length()).trim();
        }
        if (t.isEmpty()) return null;
        int mods = 0;
        String key = null;
        List<String> parts = new ArrayList<>(Arrays.asList(t.split("\\+")));
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).trim();
            String low = p.toLowerCase(Locale.ROOT);
            boolean last = i == parts.size() - 1;
            if (low.equals("ctrl") || low.equals("control")) mods |= CTRL;
            else if (low.equals("alt")) mods |= ALT;
            else if (low.equals("shift")) mods |= SHIFT;
            else if (last && (key = keyName(p)) != null) { /* the key */ }
            else return null;
        }
        if (key == null && mods == 0) return null;
        return new Shortcut(mods, key, held);
    }

    /** The key's name as in KEYS, whatever its case; null if there is none. */
    static String keyName(String p) {
        for (String k : KEYS) if (k.equalsIgnoreCase(p)) return k;
        return null;
    }

    /** The XKeycode names this button presses, modifiers first: "KEY_CTRL_L", "KEY_T". */
    public List<String> xKeys() {
        List<String> out = new ArrayList<>();
        if (exitSai) return out;
        if ((modifiers & CTRL) != 0) out.add(MOD_XKEYS[0]);
        if ((modifiers & ALT) != 0) out.add(MOD_XKEYS[1]);
        if ((modifiers & SHIFT) != 0) out.add(MOD_XKEYS[2]);
        if (key != null) out.add(XKEYS.get(KEYS.indexOf(key)));
        return out;
    }

    /** The button's label: "Ctrl+T", "Ctrl". */
    public String label() {
        if (exitSai) return EXIT_SAI;
        StringBuilder sb = new StringBuilder();
        if ((modifiers & CTRL) != 0) sb.append("Ctrl+");
        if ((modifiers & ALT) != 0) sb.append("Alt+");
        if ((modifiers & SHIFT) != 0) sb.append("Shift+");
        if (key != null) sb.append(key);
        else sb.setLength(sb.length() - 1);   /* no "+" at the end */
        return sb.toString();
    }

    /** As kept in the preferences: "hold Ctrl", "Ctrl+Z". */
    @Override
    public String toString() {
        if (exitSai) return EXIT_SAI;
        return (held ? HOLD : "") + label();
    }

    /** The same keys, held or not: a list has each combination once. */
    public boolean sameKeys(Shortcut o) {
        if (o.exitSai || exitSai) return o.exitSai == exitSai;
        return o.modifiers == modifiers && (key == null ? o.key == null : key.equals(o.key));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shortcut && sameKeys((Shortcut) o) && ((Shortcut) o).held == held
            && ((Shortcut) o).exitSai == exitSai;
    }

    @Override
    public int hashCode() {
        return modifiers * 31 + (key == null ? 0 : key.hashCode()) + (held ? 7 : 0) + (exitSai ? 101 : 0);
    }

    /** Buttons per row for n buttons when at most `fit` fit side by side
     *  (FloatingKeys): one row if they all fit, else as few rows as will do,
     *  filled evenly. */
    public static int columns(int n, int fit) {
        fit = Math.max(3, fit);
        if (n <= fit) return n;
        int rows = (n + fit - 1) / fit;
        return (n + rows - 1) / rows;
    }

    /** The saved list (one per line) -> the buttons: unreadable lines and
     *  repeated keys left out, at most MAX; "Exit SAI" is the Exit SAI button. */
    public static List<Shortcut> parseList(String saved) {
        List<Shortcut> out = new ArrayList<>();
        if (saved == null) return out;
        for (String line : saved.split("\n")) {
            Shortcut s = EXIT_SAI.equalsIgnoreCase(line.trim()) ? exitSai() : parse(line);
            if (s == null || out.size() >= MAX) continue;
            boolean repeat = false;
            for (Shortcut o : out) repeat |= o.sameKeys(s);
            if (!repeat) out.add(s);
        }
        return out;
    }

    /** The buttons -> the saved list, one per line. */
    public static String joinList(List<Shortcut> list) {
        StringBuilder sb = new StringBuilder();
        for (Shortcut s : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
}
