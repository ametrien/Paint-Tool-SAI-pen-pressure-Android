package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyboardKeysTest {
    /* Android's numbers: KeyEvent.KEYCODE_* and InputDevice.KEYBOARD_TYPE_* */
    private static final int ALPHABETIC = 2, NON_ALPHABETIC = 1, NONE = 0;
    private static final int KEYCODE_N = 42, KEYCODE_ESCAPE = 111, KEYCODE_DPAD_LEFT = 21,
        KEYCODE_SPACE = 62, KEYCODE_CTRL_LEFT = 113, KEYCODE_BUTTON_A = 96,
        KEYCODE_BUTTON_MODE = 110, KEYCODE_BUTTON_1 = 188, KEYCODE_BUTTON_16 = 203;

    @Test
    public void aTypingKeyboardsKeysGoToSai() {
        /* THE BUG (issue #5): Winlator dropped these when Android also called
         * the keyboard a gamepad; whether it does must not matter here */
        assertTrue(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_N));
        assertTrue(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_ESCAPE));
        assertTrue(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_SPACE));
        assertTrue(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_CTRL_LEFT));
        assertTrue("arrows, which Winlator took as a d-pad",
            KeyboardKeys.toSai(ALPHABETIC, KEYCODE_DPAD_LEFT));
    }

    @Test
    public void gamepadButtonsStayWithTheGamepadCode() {
        assertFalse(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_BUTTON_A));
        assertFalse(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_BUTTON_MODE));
        assertFalse(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_BUTTON_1));
        assertFalse(KeyboardKeys.toSai(ALPHABETIC, KEYCODE_BUTTON_16));
        assertTrue("next to the button range", KeyboardKeys.toSai(ALPHABETIC, 95));
        assertTrue("next to the button range", KeyboardKeys.toSai(ALPHABETIC, 204));
    }

    @Test
    public void aRealGamepadKeepsWinlatorsWay() {
        /* a gamepad has no letters: its d-pad stays a d-pad */
        assertFalse(KeyboardKeys.toSai(NON_ALPHABETIC, KEYCODE_DPAD_LEFT));
        assertFalse(KeyboardKeys.toSai(NONE, KEYCODE_N));
    }

    @Test
    public void eachDeviceIsNamedOnce() {
        assertTrue(KeyboardKeys.firstKeyFrom(1001));
        assertFalse(KeyboardKeys.firstKeyFrom(1001));
        assertTrue(KeyboardKeys.firstKeyFrom(1002));
    }

    @Test
    public void theLogLineSaysHowAndroidSeesTheKeyboard() {
        assertEquals("keyboard \"Combo\": typing keyboard, sources 0x4503, Android calls it a gamepad too",
            KeyboardKeys.describe("Combo", 0x4503, ALPHABETIC, true));
        assertEquals("keyboard \"Plain\": typing keyboard, sources 0x4103",
            KeyboardKeys.describe("Plain", 0x4103, ALPHABETIC, false));
        assertEquals("keyboard \"Pad\": keys, no letters, sources 0x1000511, Android calls it a gamepad too",
            KeyboardKeys.describe("Pad", 0x1000511, NON_ALPHABETIC, true));
    }
}
