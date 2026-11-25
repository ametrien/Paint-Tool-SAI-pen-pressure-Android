package com.winlator.saipen;

/**
 * The Bluetooth S Pen's button pressed away from the screen ("Air actions",
 * Galaxy S24 Ultra, Tab S7 to S10; not the S25 Ultra, Tab S6 Lite, S9 FE).
 *
 * res/xml/saipen_air_actions.xml lists our actions, each with a key
 * shortcut (trigger_key); Samsung's S Pen service sends that shortcut to the
 * app on the screen as ordinary key events, and the person can remap the
 * gestures in Settings > Advanced features > S Pen > Air actions > SAI Pen.
 *
 * THE TRAP this class is for: Winlator's Keyboard.onKeyEvent puts Shift
 * around a key from the event's meta state, but not Ctrl. A real keyboard
 * sends its Ctrl key as a key of its own first, so X has it held; a
 * shortcut that arrives as one key with only META_CTRL_ON in its meta state
 * would reach SAI as a bare letter: "Z" instead of Undo. Not seen on a
 * device yet (2026-11-25): how Samsung sends the Ctrl is in the log
 * ("air key" lines, AirActionKeys.describe).
 *
 * No Android types: the JVM tests call it with plain numbers.
 */
public final class AirActionKeys {
    /** KeyEvent.META_CTRL_ON */
    static final int META_CTRL_ON = 0x1000;
    /** KeyEvent.KEYCODE_CTRL_LEFT, KEYCODE_CTRL_RIGHT */
    static final int KEYCODE_CTRL_LEFT = 113;
    static final int KEYCODE_CTRL_RIGHT = 114;

    private AirActionKeys() {}

    /**
     * True: press Ctrl in X before this key goes down (and let it go after
     * the key comes up). The event says Ctrl is held, the key is not Ctrl
     * itself, and X does not have Ctrl held already.
     */
    public static boolean addCtrl(int keyCode, int metaState, boolean ctrlHeldInX) {
        if (keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT) return false;
        return (metaState & META_CTRL_ON) != 0 && !ctrlHeldInX;
    }

    /** The log's line for one key event, to see how a pen press arrives. */
    public static String describe(int action, int keyCode, int metaState, int deviceId,
                                  String deviceName, boolean virtual, int keyboardType) {
        return "air key " + (action == 0 ? "down" : action == 1 ? "up" : "action " + action)
            + " code " + keyCode + " meta 0x" + Integer.toHexString(metaState)
            + " device " + deviceId + " \"" + deviceName + "\""
            + (virtual ? " virtual" : "") + " keyboard type " + keyboardType;
    }
}
