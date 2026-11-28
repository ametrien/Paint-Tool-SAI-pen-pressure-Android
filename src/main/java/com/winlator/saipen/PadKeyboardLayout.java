package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The keys of SAI Pen's own keyboard (PadKeyboard): a PC keyboard's, US
 * layout, in five rows of the same width (ROW_UNITS key widths each).
 *
 * Why a keyboard of our own, when Android has one: the tablet's keyboard
 * (the owner, 2026-11-28: "weird sometimes") pushes the picture up
 * (KeyboardShift), covers the bottom of the screen and has no Ctrl, Alt,
 * Esc or arrows for SAI. Ours floats over SAI like the on-screen keys, in
 * their colour and opacity, is dragged anywhere, and sends X keys, the same
 * ones a Bluetooth keyboard does: Shift+1 is "!" because Wine reads the X
 * keys with the US layout, as it does a real keyboard's.
 *
 * No Android types: the JVM tests check the rows and the labels.
 */
public final class PadKeyboardLayout {
    /** Every row is this many key widths wide. */
    public static final float ROW_UNITS = 15f;

    public enum Kind {
        /** a key that types or does something once, pressed while touched */
        KEY,
        /** pressed again and again while held: Backspace, Delete, arrows */
        REPEAT,
        SHIFT, CTRL, ALT
    }

    public static final class Key {
        /** what it shows; for letters the lower case */
        public final String label;
        /** what it shows while Shift is on; the label if Shift does not change it */
        public final String shifted;
        /** the com.winlator.xserver.XKeycode name: "KEY_A" */
        public final String xKey;
        /** its width, in key widths */
        public final float units;
        public final Kind kind;

        Key(String label, String shifted, String xKey, float units, Kind kind) {
            this.label = label;
            this.shifted = shifted;
            this.xKey = xKey;
            this.units = units;
            this.kind = kind;
        }

        public boolean isModifier() {
            return kind == Kind.SHIFT || kind == Kind.CTRL || kind == Kind.ALT;
        }

        /** What it shows now. */
        public String label(boolean shift) {
            return shift ? shifted : label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final List<List<Key>> ROWS;

    static {
        List<List<Key>> rows = new ArrayList<>();

        List<Key> r = new ArrayList<>();
        r.add(sym("`", "~", "KEY_GRAVE"));
        String digits = "1234567890", shiftedDigits = "!@#$%^&*()";
        for (int i = 0; i < digits.length(); i++) {
            r.add(sym(String.valueOf(digits.charAt(i)), String.valueOf(shiftedDigits.charAt(i)),
                "KEY_" + digits.charAt(i)));
        }
        r.add(sym("-", "_", "KEY_MINUS"));
        r.add(sym("=", "+", "KEY_EQUAL"));
        r.add(new Key("⌫", "⌫", "KEY_BKSP", 2f, Kind.REPEAT));
        rows.add(Collections.unmodifiableList(r));

        r = new ArrayList<>();
        r.add(new Key("Tab", "Tab", "KEY_TAB", 1.5f, Kind.KEY));
        letters(r, "qwertyuiop");
        r.add(sym("[", "{", "KEY_BRACKET_LEFT"));
        r.add(sym("]", "}", "KEY_BRACKET_RIGHT"));
        r.add(new Key("\\", "|", "KEY_BACKSLASH", 1.5f, Kind.KEY));
        rows.add(Collections.unmodifiableList(r));

        r = new ArrayList<>();
        r.add(new Key("Esc", "Esc", "KEY_ESC", 1.75f, Kind.KEY));
        letters(r, "asdfghjkl");
        r.add(sym(";", ":", "KEY_SEMICOLON"));
        r.add(sym("'", "\"", "KEY_APOSTROPHE"));
        r.add(new Key("Enter", "Enter", "KEY_ENTER", 2.25f, Kind.KEY));
        rows.add(Collections.unmodifiableList(r));

        r = new ArrayList<>();
        r.add(new Key("Shift", "Shift", "KEY_SHIFT_L", 2.25f, Kind.SHIFT));
        letters(r, "zxcvbnm");
        r.add(sym(",", "<", "KEY_COMMA"));
        r.add(sym(".", ">", "KEY_PERIOD"));
        r.add(sym("/", "?", "KEY_SLASH"));
        r.add(new Key("Shift", "Shift", "KEY_SHIFT_L", 2.75f, Kind.SHIFT));
        rows.add(Collections.unmodifiableList(r));

        r = new ArrayList<>();
        r.add(new Key("Ctrl", "Ctrl", "KEY_CTRL_L", 1.5f, Kind.CTRL));
        r.add(new Key("Alt", "Alt", "KEY_ALT_L", 1.5f, Kind.ALT));
        r.add(new Key("", "", "KEY_SPACE", 7f, Kind.KEY));
        r.add(new Key("←", "←", "KEY_LEFT", 1f, Kind.REPEAT));
        r.add(new Key("↑", "↑", "KEY_UP", 1f, Kind.REPEAT));
        r.add(new Key("↓", "↓", "KEY_DOWN", 1f, Kind.REPEAT));
        r.add(new Key("→", "→", "KEY_RIGHT", 1f, Kind.REPEAT));
        r.add(new Key("Del", "Del", "KEY_DEL", 1f, Kind.REPEAT));
        rows.add(Collections.unmodifiableList(r));

        ROWS = Collections.unmodifiableList(rows);
    }

    private PadKeyboardLayout() {}

    private static Key sym(String label, String shifted, String xKey) {
        return new Key(label, shifted, xKey, 1f, Kind.KEY);
    }

    private static void letters(List<Key> row, String letters) {
        for (char c : letters.toCharArray()) {
            String low = String.valueOf(c);
            row.add(new Key(low, low.toUpperCase(java.util.Locale.ROOT),
                "KEY_" + Character.toUpperCase(c), 1f, Kind.KEY));
        }
    }

    /** A row's width in key widths. */
    public static float units(List<Key> row) {
        float u = 0;
        for (Key k : row) u += k.units;
        return u;
    }

    /**
     * Ctrl, Alt and Shift on SAI Pen's keyboard: a tap turns one on for the
     * next key only (ONCE), a second tap keeps it on (LOCKED), a third turns
     * it off. A key typed lets go of the ONCE ones. Pressed in X while on,
     * so a pen stroke with Ctrl on is a Ctrl stroke, as with the on-screen
     * keys' held Ctrl.
     */
    public enum ModState { OFF, ONCE, LOCKED }

    public static final class Modifiers {
        private final ModState[] state = {ModState.OFF, ModState.OFF, ModState.OFF};

        private static int index(Kind k) {
            switch (k) {
                case SHIFT: return 0;
                case CTRL: return 1;
                case ALT: return 2;
                default: throw new IllegalArgumentException(k + " is no modifier");
            }
        }

        public ModState state(Kind k) {
            return state[index(k)];
        }

        public boolean on(Kind k) {
            return state(k) != ModState.OFF;
        }

        /** A tap on the modifier: the next state. */
        public ModState tap(Kind k) {
            int i = index(k);
            state[i] = state[i] == ModState.OFF ? ModState.ONCE
                : state[i] == ModState.ONCE ? ModState.LOCKED : ModState.OFF;
            return state[i];
        }

        /** A key was typed: the ONCE ones turn off. Returns those that did. */
        public List<Kind> typed() {
            List<Kind> off = new ArrayList<>();
            Kind[] kinds = {Kind.SHIFT, Kind.CTRL, Kind.ALT};
            for (int i = 0; i < state.length; i++) {
                if (state[i] == ModState.ONCE) {
                    state[i] = ModState.OFF;
                    off.add(kinds[i]);
                }
            }
            return off;
        }

        /** All off (the keyboard hidden). Returns those that were on. */
        public List<Kind> clear() {
            List<Kind> off = new ArrayList<>();
            Kind[] kinds = {Kind.SHIFT, Kind.CTRL, Kind.ALT};
            for (int i = 0; i < state.length; i++) {
                if (state[i] != ModState.OFF) off.add(kinds[i]);
                state[i] = ModState.OFF;
            }
            return off;
        }
    }

    /** The X key a modifier presses. */
    public static String xKey(Kind modifier) {
        switch (modifier) {
            case SHIFT: return "KEY_SHIFT_L";
            case CTRL: return "KEY_CTRL_L";
            case ALT: return "KEY_ALT_L";
            default: throw new IllegalArgumentException(modifier + " is no modifier");
        }
    }
}
