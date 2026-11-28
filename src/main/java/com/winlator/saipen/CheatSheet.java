package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the cheat sheet (CheatSheetDialog) lists: every gesture as the
 * person has it set (Gestures), then a few things to know. It opens by
 * itself the first time SAI Pen's screen opens, and again from the link at
 * the bottom of that screen.
 *
 * A gesture set to "nothing" (or a pen input set to just draw) is left out,
 * the three-finger hold too (a gesture like the others since 0.5.2). The four-finger tap (the menu) and hold (the
 * gestures' settings) cannot be changed, so they are always there.
 *
 * No Android types: the JVM tests check the rows and the memory line.
 */
public final class CheatSheet {
    private CheatSheet() {}

    /** Seen once: it does not open by itself again. */
    public static final String PREF_CHEAT_SHEET_SEEN = "saipen_cheat_sheet_seen";

    /** The rows that are always the same, whatever the settings. */
    public enum Fixed { PEN, ONE_FINGER, TWO_FINGERS, FOUR_TAP, FOUR_HOLD }

    /** One line of the gestures: a fixed one, or a settable input. */
    public static final class Row {
        public final Fixed fixed;
        public final Gestures.Input input;

        private Row(Fixed fixed, Gestures.Input input) {
            this.fixed = fixed;
            this.input = input;
        }

        @Override
        public String toString() {
            return fixed != null ? fixed.name() : input.name();
        }
    }

    /* the order on the sheet: the pen, then fingers from one to four, the
     * edges last; null entries are placeholders for the fixed rows */
    private static final Object[] ORDER = {
        Fixed.PEN, Gestures.Input.PEN_BUTTON, Gestures.Input.PEN_HOVER_BUTTON, Gestures.Input.PEN_ERASER,
        Fixed.ONE_FINGER, Gestures.Input.LONG_PRESS,
        Fixed.TWO_FINGERS, Gestures.Input.TWO_TAP, Gestures.Input.TWO_DOUBLE_TAP,
        Gestures.Input.THREE_TAP, Gestures.Input.THREE_DOUBLE_TAP, Gestures.Input.THREE_HOLD,
        Fixed.FOUR_TAP, Fixed.FOUR_HOLD,
        Gestures.Input.EDGE_LEFT, Gestures.Input.EDGE_RIGHT,
    };

    /** The gesture rows for these settings (actions: Gestures.actions). */
    public static List<Row> rows(Gestures.Action[] actions) {
        List<Row> l = new ArrayList<>();
        for (Object o : ORDER) {
            if (o instanceof Fixed) {
                l.add(new Row((Fixed) o, null));
            }
            else {
                Gestures.Input in = (Gestures.Input) o;
                Gestures.Type t = actions[in.ordinal()].type;
                if (t == Gestures.Type.NONE || t == Gestures.Type.DRAW) continue;
                l.add(new Row(null, in));
            }
        }
        return Collections.unmodifiableList(l);
    }

    /** Under this much memory the speed part says how much the device has. */
    public static final int LOW_RAM_GB = 8;

    /* the sizes devices are sold with: Android's total is always a little
     * under it (an 8 GB tablet reports about 7.3 GiB, the kernel's share) */
    private static final int[] SOLD_GB = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32};

    /** The memory as the device is sold with it: the total Android reports
     *  (ActivityManager.MemoryInfo.totalMem), up to the next usual size. */
    public static int soldGb(long totalBytes) {
        double gib = totalBytes / (1024.0 * 1024 * 1024);
        for (int g : SOLD_GB) if (gib <= g) return g;
        return (int) Math.ceil(gib);
    }

    /** The GB to name in the speed part, or 0 when there is enough (or the
     *  total is unknown) and the text stays general. */
    public static int lowRamGb(long totalBytes) {
        if (totalBytes <= 0) return 0;
        int gb = soldGb(totalBytes);
        return gb < LOW_RAM_GB ? gb : 0;
    }
}
