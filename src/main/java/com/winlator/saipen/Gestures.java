package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What each finger or pen gesture does over SAI, as each person sets it on
 * SAI Pen's screen (GesturesSettings, issue #10). A two-finger tap was
 * always a right click, which in SAI picks a colour; people coming from
 * other drawing apps tap two fingers to undo.
 *
 * Every input has a default that is what SAI Pen did before, so nothing
 * changes for anyone who never opens the settings; the one new default is
 * the side button pressed in the air, a right click like touching with it.
 * The three-finger hold was the on-screen keys' own switch until 0.5.2
 * (FloatingKeys.PREF_GESTURE); it is a gesture like the others now, the
 * keys by default, and off for anyone who had switched it off (actions).
 * The four-finger tap
 * (Winlator's menu) is not here: changed, it could lock a person out.
 * Two-finger drags (scroll, zoom, rotate) are not either: they are
 * continuous, not one action.
 *
 * Kept in the preferences one string per input: "right", "keyboard",
 * "key:Ctrl+Z", "key:hold E".
 *
 * No Android types: the JVM tests check parsing, defaults and choices.
 */
public final class Gestures {
    private Gestures() {}

    public static final String PREF_PREFIX = "saipen_gesture_";

    /** What an input can do. */
    public enum Type {
        /** nothing at all */
        NONE("none"),
        /** the pen draws as if the button were not there (pen inputs only) */
        DRAW("draw"),
        LEFT_CLICK("left"),
        RIGHT_CLICK("right"),
        /** the tablet's on-screen keyboard */
        KEYBOARD("keyboard"),
        /** SAI Pen's own keyboard, shown or hidden (PadKeyboard) */
        PAD_KEYBOARD("sai_keyboard"),
        /** show or hide the on-screen keys (FloatingKeys) */
        ON_SCREEN_KEYS("keys"),
        /** Winlator's side menu */
        MENU("menu"),
        /** SAI's window back to the whole screen: minimized, made smaller or
         *  moved with its title bar (SaiPenRequests.bringBackSai) */
        BRING_BACK("sai_back"),
        /** a key combination (Shortcut) */
        KEY("key:");

        public final String saved;

        Type(String saved) {
            this.saved = saved;
        }
    }

    /** Whether an input is a finger gesture (or the pen's button in the
     *  air: an event, not a stroke) or a pen touch, which still draws. */
    public enum Kind { EVENT, PEN_TOUCH }

    public enum Input {
        TWO_TAP("two_tap", Kind.EVENT, "right"),
        TWO_DOUBLE_TAP("two_double_tap", Kind.EVENT, "none"),
        THREE_TAP("three_tap", Kind.EVENT, "keyboard"),
        THREE_DOUBLE_TAP("three_double_tap", Kind.EVENT, "none"),
        /** three fingers held still for a moment (FingersHold) */
        THREE_HOLD("three_hold", Kind.EVENT, "keys"),
        LONG_PRESS("long_press", Kind.EVENT, "none"),
        EDGE_LEFT("edge_left", Kind.EVENT, "none"),
        EDGE_RIGHT("edge_right", Kind.EVENT, "none"),
        /** the pen touching with its side button held */
        PEN_BUTTON("pen_button", Kind.PEN_TOUCH, "right"),
        /** the pen's other end, on pens that have an eraser end */
        PEN_ERASER("pen_eraser", Kind.PEN_TOUCH, "draw"),
        /** the pen's side button pressed while the pen hovers */
        PEN_HOVER_BUTTON("pen_hover_button", Kind.EVENT, "right");

        public final String id;
        public final Kind kind;
        public final Action byDefault;

        Input(String id, Kind kind, String byDefault) {
            this.id = id;
            this.kind = kind;
            this.byDefault = Action.parse(byDefault);
        }

        public String pref() {
            return PREF_PREFIX + id;
        }
    }

    /** The types each kind of input offers, in the picker's order (KEY last). */
    public static List<Type> choices(Input in) {
        if (in.kind == Kind.PEN_TOUCH) {
            return Collections.unmodifiableList(Arrays.asList(
                Type.DRAW, Type.RIGHT_CLICK, Type.KEYBOARD, Type.PAD_KEYBOARD, Type.ON_SCREEN_KEYS, Type.MENU,
                Type.BRING_BACK, Type.KEY));
        }
        /* a hold is no click: the fingers are not on one point */
        if (in == Input.THREE_HOLD) {
            return Collections.unmodifiableList(Arrays.asList(
                Type.NONE, Type.KEYBOARD, Type.PAD_KEYBOARD, Type.ON_SCREEN_KEYS, Type.MENU, Type.BRING_BACK, Type.KEY));
        }
        return Collections.unmodifiableList(Arrays.asList(
            Type.NONE, Type.LEFT_CLICK, Type.RIGHT_CLICK, Type.KEYBOARD, Type.PAD_KEYBOARD, Type.ON_SCREEN_KEYS,
            Type.MENU, Type.BRING_BACK, Type.KEY));
    }

    /** Whether a key may be held: only while a pen touch lasts. A finger
     *  gesture is over when it fires, so its key is pressed once. */
    public static boolean canHold(Input in) {
        return in.kind == Kind.PEN_TOUCH;
    }

    /** One choice: a type, and the keys if it is KEY. */
    public static final class Action {
        public final Type type;
        /** non-null exactly when type is KEY */
        public final Shortcut keys;

        private Action(Type type, Shortcut keys) {
            this.type = type;
            this.keys = keys;
        }

        public static Action of(Type type) {
            if (type == Type.KEY) throw new IllegalArgumentException("KEY needs keys");
            return new Action(type, null);
        }

        public static Action key(Shortcut keys) {
            return new Action(Type.KEY, keys);
        }

        /** "right", "key:Ctrl+Z" -> the action; null if it is not one. */
        public static Action parse(String saved) {
            if (saved == null) return null;
            String t = saved.trim();
            if (t.startsWith(Type.KEY.saved)) {
                Shortcut s = Shortcut.parse(t.substring(Type.KEY.saved.length()));
                return s == null ? null : key(s);
            }
            for (Type ty : Type.values()) {
                if (ty != Type.KEY && ty.saved.equals(t)) return of(ty);
            }
            return null;
        }

        @Override
        public String toString() {
            return type == Type.KEY ? Type.KEY.saved + keys : type.saved;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Action)) return false;
            Action a = (Action) o;
            return a.type == type && (keys == null ? a.keys == null : keys.equals(a.keys));
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + (keys == null ? 0 : keys.hashCode());
        }
    }

    /**
     * The action saved for `in` (from the preferences' strings), its
     * default when nothing or something unusable is saved: a type the input
     * does not offer, or a held key on a finger gesture.
     */
    public static Action action(Input in, String saved) {
        Action a = Action.parse(saved);
        if (a == null || !choices(in).contains(a.type)) return in.byDefault;
        if (a.type == Type.KEY && a.keys.held && !canHold(in)) {
            return Action.key(new Shortcut(a.keys.modifiers, a.keys.key, false));
        }
        return a;
    }

    /** The on-screen keys' old switch for the three-finger hold
     *  (FloatingKeys.PREF_GESTURE, 0.5.2 and earlier). */
    public static final String OLD_KEYS_HOLD_PREF = "saipen_keys_gesture";

    /** All inputs' actions from the saved strings (pref name -> value). */
    public static Action[] actions(Map<String, ?> saved) {
        Input[] all = Input.values();
        Action[] out = new Action[all.length];
        for (int i = 0; i < all.length; i++) {
            Object v = saved == null ? null : saved.get(all[i].pref());
            out[i] = action(all[i], v instanceof String ? (String) v : null);
        }
        /* the hold switched off under On-screen keys before it was a gesture:
         * off it stays, until it is set under Gestures */
        if (saved != null && !(saved.get(Input.THREE_HOLD.pref()) instanceof String)
                && Boolean.FALSE.equals(saved.get(OLD_KEYS_HOLD_PREF))) {
            out[Input.THREE_HOLD.ordinal()] = Action.of(Type.NONE);
        }
        return out;
    }

    /** The inputs set to this type, in the settings' order. */
    public static List<Input> inputsDoing(Action[] actions, Type type) {
        List<Input> l = new ArrayList<>();
        for (Input in : fingerInputs()) if (actions[in.ordinal()].type == type) l.add(in);
        for (Input in : penInputs()) if (actions[in.ordinal()].type == type) l.add(in);
        return l;
    }

    /** Whether three fingers held still show or hide the on-screen keys. */
    public static boolean holdShowsKeys(Action[] actions) {
        return actions[Input.THREE_HOLD.ordinal()].type == Type.ON_SCREEN_KEYS;
    }

    /** How many inputs differ from their default (the settings row's summary). */
    public static int changed(Action[] actions) {
        int n = 0;
        for (Input in : Input.values()) if (!actions[in.ordinal()].equals(in.byDefault)) n++;
        return n;
    }

    /** Whether any input shows or hides the on-screen keys: they must then
     *  be set up even if they are off at the start (FloatingKeys). */
    public static boolean usesOnScreenKeys(Action[] actions) {
        for (Action a : actions) if (a.type == Type.ON_SCREEN_KEYS) return true;
        return false;
    }

    /** The inputs in the settings' order, fingers first. */
    public static List<Input> fingerInputs() {
        List<Input> l = new ArrayList<>();
        for (Input in : Input.values()) if (!isPen(in)) l.add(in);
        return l;
    }

    public static List<Input> penInputs() {
        List<Input> l = new ArrayList<>();
        for (Input in : Input.values()) if (isPen(in)) l.add(in);
        return l;
    }

    private static boolean isPen(Input in) {
        return in == Input.PEN_BUTTON || in == Input.PEN_ERASER || in == Input.PEN_HOVER_BUTTON;
    }

    /**
     * What a pen touch does at touch-down, from its action:
     * PEN_LEFT draws (a held key, if any, goes down first), PEN_RIGHT is a
     * right click, PEN_NOTHING draws nothing (the action ran at touch-down).
     */
    public static final int PEN_LEFT = 1, PEN_RIGHT = 3, PEN_NOTHING = -1;

    public static int penTouch(Action a) {
        switch (a.type) {
            case DRAW:
            case LEFT_CLICK:
                return PEN_LEFT;
            case RIGHT_CLICK:
                return PEN_RIGHT;
            case KEY:
                return a.keys.held ? PEN_LEFT : PEN_NOTHING;
            default:
                return PEN_NOTHING;
        }
    }
}
