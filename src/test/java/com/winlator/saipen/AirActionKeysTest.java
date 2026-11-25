package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AirActionKeysTest {
    private static final int KEYCODE_Z = 54;
    /* META_CTRL_ON | META_CTRL_LEFT_ON, as a real left Ctrl sets it */
    private static final int CTRL_LEFT_META = 0x1000 | 0x2000;

    @Test
    public void shortcutWithOnlyMetaCtrlGetsCtrl() {
        assertTrue(AirActionKeys.addCtrl(KEYCODE_Z, CTRL_LEFT_META, false));
        assertTrue(AirActionKeys.addCtrl(KEYCODE_Z, 0x1000, false));
    }

    @Test
    public void ctrlAlreadyHeldInXIsNotPressedTwice() {
        /* a real keyboard: its Ctrl key came first and X holds it */
        assertFalse(AirActionKeys.addCtrl(KEYCODE_Z, CTRL_LEFT_META, true));
    }

    @Test
    public void plainKeyGetsNoCtrl() {
        assertFalse(AirActionKeys.addCtrl(KEYCODE_Z, 0, false));
        /* Shift alone (META_SHIFT_ON) is Winlator's own business */
        assertFalse(AirActionKeys.addCtrl(KEYCODE_Z, 0x1, false));
    }

    @Test
    public void ctrlKeyItselfGetsNoExtraCtrl() {
        assertFalse(AirActionKeys.addCtrl(113, CTRL_LEFT_META, false));
        assertFalse(AirActionKeys.addCtrl(114, 0x1000 | 0x4000, false));
    }

    @Test
    public void describeNamesEverythingTheLogNeeds() {
        assertEquals("air key down code 54 meta 0x3000 device -1 \"Virtual\" virtual keyboard type 2",
            AirActionKeys.describe(0, 54, 0x3000, -1, "Virtual", true, 2));
        assertEquals("air key up code 113 meta 0x0 device 7 \"S Pen\" keyboard type 1",
            AirActionKeys.describe(1, 113, 0, 7, "S Pen", false, 1));
    }
}
