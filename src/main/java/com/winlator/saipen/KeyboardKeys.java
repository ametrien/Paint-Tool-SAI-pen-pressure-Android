package com.winlator.saipen;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keys from a hardware keyboard reach SAI, also from a keyboard Android
 * calls a gamepad as well (issue #5).
 *
 * Winlator hands every key of a device Android lists as a gamepad or
 * joystick to its game controller code: the arrows become a d-pad and every
 * other key is dropped (XServerDisplayActivity.dispatchKeyEvent,
 * Keyboard.onKeyEvent). Some keyboards say they are gamepads too. Seen
 * 2026-11-20 on the Galaxy Tab S6 Lite with 0.4.23 and two emulated
 * Bluetooth keyboards: the plain one (Sources KEYBOARD) typed into SAI and
 * Ctrl+N opened its New Canvas; the one that also has gamepad buttons
 * (Sources KEYBOARD | GAMEPAD) did nothing at all in SAI, although Android
 * delivered its keys to the app.
 *
 * SAI has no use for a gamepad. So a key from a device with a typing
 * (alphabetic) keyboard goes to SAI, unless the key itself is a gamepad
 * button. A real gamepad has no alphabetic keyboard and keeps Winlator's way.
 *
 * No Android types: the JVM tests call it with plain numbers.
 */
public final class KeyboardKeys {
    /** InputDevice.KEYBOARD_TYPE_ALPHABETIC */
    static final int KEYBOARD_TYPE_ALPHABETIC = 2;
    /** InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC */
    static final int KEYBOARD_TYPE_NON_ALPHABETIC = 1;

    /** Devices already named in the log, by Android's device id. */
    private static final Set<Integer> named = new HashSet<>();

    private KeyboardKeys() {}

    /**
     * True: this key goes to SAI's keyboard, whatever else Android says the
     * device is. keyboardType: InputDevice.getKeyboardType().
     */
    public static boolean toSai(int keyboardType, int keyCode) {
        return keyboardType == KEYBOARD_TYPE_ALPHABETIC && !isGamepadButton(keyCode);
    }

    /**
     * KeyEvent.isGamepadButton without Android: KEYCODE_BUTTON_A (96) to
     * KEYCODE_BUTTON_MODE (110), KEYCODE_BUTTON_1 (188) to KEYCODE_BUTTON_16 (203).
     */
    static boolean isGamepadButton(int keyCode) {
        return (keyCode >= 96 && keyCode <= 110) || (keyCode >= 188 && keyCode <= 203);
    }

    /**
     * The character a key press tells X it types, or 0 for none.
     *
     * THE TRAP: Winlator's X server makes the character the key's keysym
     * when they differ (InputDeviceManager.onKeyPress). For a letter that is
     * the same code, but Tab types '\t' (9) while X's Tab is 0xFF09: after
     * the first press Tab was character 9, Wine no longer knew it as Tab, and
     * SAI's Tab shortcut (show/hide all panels) did nothing. Enter had the
     * same problem and its own exception; every control character below
     * space, and Delete (127), now keeps the key's own keysym.
     */
    public static int typedChar(int unicodeChar) {
        return unicodeChar < 0x20 || unicodeChar == 0x7f ? 0 : unicodeChar;
    }

    /** True the first time a key comes from this device: name it in the log then. */
    public static synchronized boolean firstKeyFrom(int deviceId) {
        return named.add(deviceId);
    }

    /**
     * The log's line for a keyboard, so a report of keys not reaching SAI
     * says how Android sees that keyboard. sources: InputDevice.getSources().
     */
    public static String describe(String name, int sources, int keyboardType, boolean gamepadToAndroid) {
        String type = keyboardType == KEYBOARD_TYPE_ALPHABETIC ? "typing keyboard"
                    : keyboardType == KEYBOARD_TYPE_NON_ALPHABETIC ? "keys, no letters" : "no keys";
        return String.format(Locale.ROOT, "keyboard \"%s\": %s, sources 0x%x%s", name, type, sources,
            gamepadToAndroid ? ", Android calls it a gamepad too" : "");
    }
}
