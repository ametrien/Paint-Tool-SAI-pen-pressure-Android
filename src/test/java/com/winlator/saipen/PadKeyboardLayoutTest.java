package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PadKeyboardLayoutTest {
    private static final Set<String> XKEYCODES = new HashSet<>(Arrays.asList(
        "KEY_ESC", "KEY_1", "KEY_2", "KEY_3", "KEY_4", "KEY_5", "KEY_6", "KEY_7", "KEY_8", "KEY_9", "KEY_0",
        "KEY_MINUS", "KEY_EQUAL", "KEY_BKSP", "KEY_TAB", "KEY_Q", "KEY_W", "KEY_E", "KEY_R", "KEY_T", "KEY_Y",
        "KEY_U", "KEY_I", "KEY_O", "KEY_P", "KEY_BRACKET_LEFT", "KEY_BRACKET_RIGHT", "KEY_ENTER", "KEY_CTRL_L",
        "KEY_A", "KEY_S", "KEY_D", "KEY_F", "KEY_G", "KEY_H", "KEY_J", "KEY_K", "KEY_L", "KEY_SEMICOLON",
        "KEY_APOSTROPHE", "KEY_GRAVE", "KEY_SHIFT_L", "KEY_BACKSLASH", "KEY_Z", "KEY_X", "KEY_C", "KEY_V",
        "KEY_B", "KEY_N", "KEY_M", "KEY_COMMA", "KEY_PERIOD", "KEY_SLASH", "KEY_ALT_L", "KEY_SPACE",
        "KEY_LEFT", "KEY_UP", "KEY_DOWN", "KEY_RIGHT", "KEY_DEL"));

    @Test
    public void everyRowIsAsWide() {
        assertEquals(5, PadKeyboardLayout.ROWS.size());
        for (List<PadKeyboardLayout.Key> row : PadKeyboardLayout.ROWS) {
            assertEquals(PadKeyboardLayout.ROW_UNITS, PadKeyboardLayout.units(row), 1e-4);
        }
    }

    @Test
    public void everyKeyIsAnXKey() {
        for (List<PadKeyboardLayout.Key> row : PadKeyboardLayout.ROWS) {
            for (PadKeyboardLayout.Key k : row) assertTrue(k.xKey, XKEYCODES.contains(k.xKey));
        }
    }

    @Test
    public void lettersShowUpperCaseWithShift() {
        PadKeyboardLayout.Key q = PadKeyboardLayout.ROWS.get(1).get(1);
        assertEquals("q", q.label(false));
        assertEquals("Q", q.label(true));
        assertEquals("KEY_Q", q.xKey);
        PadKeyboardLayout.Key one = PadKeyboardLayout.ROWS.get(0).get(1);
        assertEquals("1", one.label(false));
        assertEquals("!", one.label(true));
    }

    @Test
    public void modifiersCycleOnceLockedOff() {
        PadKeyboardLayout.Modifiers m = new PadKeyboardLayout.Modifiers();
        assertFalse(m.on(PadKeyboardLayout.Kind.CTRL));
        assertEquals(PadKeyboardLayout.ModState.ONCE, m.tap(PadKeyboardLayout.Kind.CTRL));
        assertEquals(PadKeyboardLayout.ModState.LOCKED, m.tap(PadKeyboardLayout.Kind.CTRL));
        assertEquals(PadKeyboardLayout.ModState.OFF, m.tap(PadKeyboardLayout.Kind.CTRL));
    }

    @Test
    public void aTypedKeyLetsGoOfTheOnceOnes() {
        PadKeyboardLayout.Modifiers m = new PadKeyboardLayout.Modifiers();
        m.tap(PadKeyboardLayout.Kind.SHIFT);
        m.tap(PadKeyboardLayout.Kind.CTRL);
        m.tap(PadKeyboardLayout.Kind.CTRL);   /* locked */
        assertEquals(Collections.singletonList(PadKeyboardLayout.Kind.SHIFT), m.typed());
        assertFalse(m.on(PadKeyboardLayout.Kind.SHIFT));
        assertTrue(m.on(PadKeyboardLayout.Kind.CTRL));
        assertTrue(m.typed().isEmpty());
        assertEquals(Collections.singletonList(PadKeyboardLayout.Kind.CTRL), m.clear());
        assertFalse(m.on(PadKeyboardLayout.Kind.CTRL));
    }
}
