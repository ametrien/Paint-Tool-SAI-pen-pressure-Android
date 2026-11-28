package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The tips on the loading screen (StartupCover) and the menu toast, as the
 * person has the gestures set (Gestures): a tip never names a gesture that
 * does something else now.
 *
 * The keyboard tip names the first input set to the keyboard (fingers come
 * first in Input's order); none set, no tip. The on-screen keys tip names the
 * three-finger hold while it is set to the keys (Gestures.holdShowsKeys), else the
 * first input set to show or hide them. With neither tip left, the
 * four-finger hold (the gestures' settings), which cannot be changed.
 * Always one more: gestures, on-screen keys and pen pressure can be changed
 * under Settings on SAI Pen's screen, before SAI starts. And which drive in
 * SAI is which folder (F: drawings, G: gallery, ...).
 *
 * No Android types: the JVM tests check which tips there are.
 */
public final class LoadingTips {
    private LoadingTips() {}

    public enum Kind {
        /** "input" opens and closes the tablet's keyboard */
        KEYBOARD,
        /** the three-finger hold shows or hides the on-screen keys */
        KEYS_HOLD,
        /** "input" shows or hides the on-screen keys */
        KEYS,
        /** four fingers held still: change the gestures */
        CHANGE_GESTURES,
        /** gestures, keys and pen pressure: Settings on SAI Pen's screen */
        SETTINGS,
        /** which drive is which folder: F: for drawings */
        DRIVES
    }

    public static final class Tip {
        public final Kind kind;
        /** the gesture the tip names, for KEYBOARD and KEYS; else null */
        public final Gestures.Input input;

        private Tip(Kind kind, Gestures.Input input) {
            this.kind = kind;
            this.input = input;
        }

        @Override
        public String toString() {
            return input == null ? kind.name() : kind.name() + ":" + input.name();
        }
    }

    /** The first input set to this type, or null. */
    public static Gestures.Input first(Gestures.Action[] actions, Gestures.Type type) {
        for (Gestures.Input in : Gestures.Input.values()) {
            if (actions[in.ordinal()].type == type) return in;
        }
        return null;
    }

    /** The tips to pick from for these settings: never empty. */
    public static List<Tip> tips(Gestures.Action[] actions) {
        boolean keysGesture = Gestures.holdShowsKeys(actions);
        List<Tip> l = new ArrayList<>();
        Gestures.Input keyboard = first(actions, Gestures.Type.KEYBOARD);
        if (keyboard != null) l.add(new Tip(Kind.KEYBOARD, keyboard));
        if (keysGesture) l.add(new Tip(Kind.KEYS_HOLD, null));
        else {
            Gestures.Input keys = first(actions, Gestures.Type.ON_SCREEN_KEYS);
            if (keys != null) l.add(new Tip(Kind.KEYS, keys));
        }
        if (l.isEmpty()) l.add(new Tip(Kind.CHANGE_GESTURES, null));
        l.add(new Tip(Kind.SETTINGS, null));
        l.add(new Tip(Kind.DRIVES, null));
        return Collections.unmodifiableList(l);
    }
}
