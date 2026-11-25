package com.winlator.saipen;

/**
 * How far the picture moves up while the on-screen keyboard is open.
 *
 * Winlator lifts the picture so the part around the cursor is not under
 * the keyboard, by Winlator's own formula (below), but recomputed it on
 * every frame from the cursor, and touches knew nothing of it. On the
 * tablet (2026-11-18), with the keyboard open: the cursor landed well above
 * the finger, and moving the canvas made the picture jump with the cursor.
 *
 * Now the shift is taken once, when the keyboard opens (the cursor is then
 * where the person tapped the text field), and kept until it closes; the
 * same shift is added to every touch and pen point.
 *
 * No Android types: the JVM tests check the numbers.
 */
public final class KeyboardShift {
    private KeyboardShift() {}

    /** X screen pixels the picture moves up, for the cursor at pointerY on
     *  a screen screenH high: Winlator's formula, cursor a quarter down. */
    public static float offsetY(float pointerY, int screenH) {
        float shift = pointerY - screenH * 0.25f;
        return Math.max(0f, Math.min(shift, screenH * 0.5f));
    }

    /** A point under the finger (X screen, picture unshifted) while the
     *  picture is shifted up by `offsetY`. */
    public static float touchY(float transformedY, float offsetY) {
        return transformedY + offsetY;
    }
}
