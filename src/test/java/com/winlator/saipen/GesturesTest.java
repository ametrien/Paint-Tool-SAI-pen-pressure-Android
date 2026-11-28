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
        /* three fingers held still: the keys, as the keys' own switch did */
        assertEquals(Gestures.Type.ON_SCREEN_KEYS, all[Gestures.Input.THREE_HOLD.ordinal()].type);
        assertTrue(Gestures.usesOnScreenKeys(all));
        assertTrue(Gestures.holdShowsKeys(all));
    }

    @Test
    public void theHoldSwitchedOffUnderTheKeysStaysOff() {
        Map<String, Object> saved = new HashMap<>();
        saved.put(Gestures.OLD_KEYS_HOLD_PREF, false);
        Gestures.Action[] all = Gestures.actions(saved);
        assertEquals(Gestures.Type.NONE, all[Gestures.Input.THREE_HOLD.ordinal()].type);
        assertFalse(Gestures.usesOnScreenKeys(all));
        saved.put(Gestures.OLD_KEYS_HOLD_PREF, true);
        assertTrue(Gestures.holdShowsKeys(Gestures.actions(saved)));
        /* set under Gestures since: that wins */
        saved.put(Gestures.OLD_KEYS_HOLD_PREF, false);
        saved.put(Gestures.Input.THREE_HOLD.pref(), "sai_keyboard");
        assertEquals(Gestures.Type.PAD_KEYBOARD, Gestures.actions(saved)[Gestures.Input.THREE_HOLD.ordinal()].type);
    }

    @Test
    public void theHoldClicksNothing() {
        assertFalse(Gestures.choices(Gestures.Input.THREE_HOLD).contains(Gestures.Type.LEFT_CLICK));
        assertFalse(Gestures.choices(Gestures.Input.THREE_HOLD).contains(Gestures.Type.RIGHT_CLICK));
        assertTrue(Gestures.choices(Gestures.Input.THREE_HOLD).contains(Gestures.Type.PAD_KEYBOARD));
        Map<String, Object> saved = new HashMap<>();
        saved.put(Gestures.Input.THREE_HOLD.pref(), "right");
        assertEquals("not offered: the default", Gestures.Type.ON_SCREEN_KEYS,
            Gestures.actions(saved)[Gestures.Input.THREE_HOLD.ordinal()].type);
    }

    @Test
    public void everyGestureCanBringSaiBack() {
        for (Gestures.Input in : Gestures.Input.values()) {
            assertTrue(in.id, Gestures.choices(in).contains(Gestures.Type.BRING_BACK));
        }
        java.util.Map<String, Object> saved = new java.util.HashMap<>();
        saved.put(Gestures.PREF_PREFIX + Gestures.Input.THREE_HOLD.id, "sai_back");
        assertEquals(Gestures.Type.BRING_BACK, Gestures.actions(saved)[Gestures.Input.THREE_HOLD.ordinal()].type);
        assertEquals("draws nothing with the pen", Gestures.PEN_NOTHING,
            Gestures.penTouch(Gestures.actions(saved)[Gestures.Input.THREE_HOLD.ordinal()]));
    }

    @Test
    public void everyGestureCanOpenSaiPensKeyboard() {
        for (Gestures.Input in : Gestures.Input.values()) {
            assertTrue(in.id, Gestures.choices(in).contains(Gestures.Type.PAD_KEYBOARD));
        }
    }

    @Test
    public void theGesturesSetToTheKeysInTheSettingsOrder() {
        Map<String, Object> saved = new HashMap<>();
        saved.put(Gestures.Input.TWO_DOUBLE_TAP.pref(), "keys");
        saved.put(Gestures.Input.PEN_HOVER_BUTTON.pref(), "keys");
        assertEquals(java.util.Arrays.asList(Gestures.Input.TWO_DOUBLE_TAP, Gestures.Input.THREE_HOLD,
                Gestures.Input.PEN_HOVER_BUTTON),
            Gestures.inputsDoing(Gestures.actions(saved), Gestures.Type.ON_SCREEN_KEYS));
    }

    @Test
    public void parsesAndWritesBack() {
        for (String s : new String[]{"none", "draw", "left", "right", "keyboard", "sai_keyboard", "keys", "menu",
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
