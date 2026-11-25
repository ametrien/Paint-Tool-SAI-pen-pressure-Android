package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class GesturesTest {
    private static Gestures.Action a(String s) {
        return Gestures.Action.parse(s);
    }

    @Test
    public void defaultsAreWhatSaiPenDidBefore() {
        Gestures.Action[] all = Gestures.actions(new HashMap<>());
        assertEquals(Gestures.Type.RIGHT_CLICK, all[Gestures.Input.TWO_TAP.ordinal()].type);
        assertEquals(Gestures.Type.KEYBOARD, all[Gestures.Input.THREE_TAP.ordinal()].type);
        assertEquals(Gestures.Type.RIGHT_CLICK, all[Gestures.Input.PEN_BUTTON.ordinal()].type);
        assertEquals(Gestures.Type.DRAW, all[Gestures.Input.PEN_ERASER.ordinal()].type);
        /* the side button in the air is a right click too, like touching with it */
        assertEquals(Gestures.Type.RIGHT_CLICK, all[Gestures.Input.PEN_HOVER_BUTTON.ordinal()].type);
        for (Gestures.Input in : new Gestures.Input[]{Gestures.Input.TWO_DOUBLE_TAP, Gestures.Input.THREE_DOUBLE_TAP,
                Gestures.Input.LONG_PRESS, Gestures.Input.EDGE_LEFT, Gestures.Input.EDGE_RIGHT}) {
            assertEquals(in.id, Gestures.Type.NONE, all[in.ordinal()].type);
        }
        assertEquals(0, Gestures.changed(all));
        assertFalse(Gestures.usesOnScreenKeys(all));
    }

    @Test
    public void parsesAndWritesBack() {
        for (String s : new String[]{"none", "draw", "left", "right", "keyboard", "keys", "menu",
                "key:Ctrl+Z", "key:hold E", "key:Ctrl+Shift+Z"}) {
            assertEquals(s, a(s).toString());
        }
        assertEquals(Shortcut.parse("Ctrl+Z"), a("key:ctrl+z").keys);
        assertNull(a("key:"));
        assertNull(a("key:Ctrl+Nope"));
        assertNull(a("jump"));
        assertNull(a(null));
    }

    @Test
    public void twoFingerTapForUndo() {
        Map<String, Object> saved = new HashMap<>();
        saved.put(Gestures.Input.TWO_TAP.pref(), "key:Ctrl+Z");
        saved.put(Gestures.Input.THREE_TAP.pref(), "key:Ctrl+Y");
        Gestures.Action[] all = Gestures.actions(saved);
        assertEquals(a("key:Ctrl+Z"), all[Gestures.Input.TWO_TAP.ordinal()]);
        assertEquals(a("key:Ctrl+Y"), all[Gestures.Input.THREE_TAP.ordinal()]);
        assertEquals(2, Gestures.changed(all));
    }

    @Test
    public void unusableChoicesFallBackToTheDefault() {
        /* draw is for the pen, not a finger */
        assertEquals(Gestures.Input.TWO_TAP.byDefault, Gestures.action(Gestures.Input.TWO_TAP, "draw"));
        /* "nothing" would leave a pen touch neither drawing nor clicking: draw it is */
        assertEquals(Gestures.Input.PEN_ERASER.byDefault, Gestures.action(Gestures.Input.PEN_ERASER, "none"));
        assertEquals(Gestures.Input.LONG_PRESS.byDefault, Gestures.action(Gestures.Input.LONG_PRESS, "garbage"));
        /* not a String in the preferences */
        Map<String, Object> saved = new HashMap<>();
        saved.put(Gestures.Input.TWO_TAP.pref(), 3);
        assertEquals(Gestures.Input.TWO_TAP.byDefault, Gestures.actions(saved)[Gestures.Input.TWO_TAP.ordinal()]);
    }

    @Test
    public void onlyAPenTouchHoldsAKey() {
        /* a finger gesture is over when it fires: its key is pressed once */
        assertEquals(a("key:E"), Gestures.action(Gestures.Input.LONG_PRESS, "key:hold E"));
        assertEquals(a("key:hold E"), Gestures.action(Gestures.Input.PEN_BUTTON, "key:hold E"));
        assertTrue(Gestures.canHold(Gestures.Input.PEN_ERASER));
        assertFalse(Gestures.canHold(Gestures.Input.TWO_TAP));
        assertFalse(Gestures.canHold(Gestures.Input.PEN_HOVER_BUTTON));
    }

    @Test
    public void whatAPenTouchDoes() {
        assertEquals(Gestures.PEN_LEFT, Gestures.penTouch(a("draw")));
        assertEquals(Gestures.PEN_RIGHT, Gestures.penTouch(a("right")));
        /* a held key: it draws with the key down */
        assertEquals(Gestures.PEN_LEFT, Gestures.penTouch(a("key:hold E")));
        /* a key once, the keyboard, the keys, the menu: run at touch-down, no ink */
        assertEquals(Gestures.PEN_NOTHING, Gestures.penTouch(a("key:Ctrl+Z")));
        assertEquals(Gestures.PEN_NOTHING, Gestures.penTouch(a("keyboard")));
        assertEquals(Gestures.PEN_NOTHING, Gestures.penTouch(a("keys")));
        assertEquals(Gestures.PEN_NOTHING, Gestures.penTouch(a("menu")));
    }

    @Test
    public void onScreenKeysAreNeededWhenAGestureTogglesThem() {
        Map<String, Object> saved = new HashMap<>();
        saved.put(Gestures.Input.EDGE_RIGHT.pref(), "keys");
        assertTrue(Gestures.usesOnScreenKeys(Gestures.actions(saved)));
    }

    @Test
    public void everyInputIsInOneSectionAndTheFourFingerTapInNone() {
        assertEquals(Gestures.Input.values().length,
            Gestures.fingerInputs().size() + Gestures.penInputs().size());
        assertTrue(Gestures.penInputs().contains(Gestures.Input.PEN_HOVER_BUTTON));
        for (Gestures.Input in : Gestures.Input.values()) assertFalse(in.id.contains("four"));
    }
}
