package com.winlator.saipen;

/**
 * The Exit SAI button of the floating keys (FloatingKeys, Shortcut.EXIT_SAI)
 * wants two taps: the first lights it and asks "Exit?", the second, within
 * CONFIRM_MS, closes SAI. SAI asks about unsaved drawings itself, but a
 * drawing just saved closes at once, so one stray tap must not do it.
 *
 * Why not a hold: a long press on the dot already hides the keys, and held
 * buttons keep their keys down; a second tap is the one thing no other
 * button asks for.
 *
 * THE TRAP: a resting palm on the Tab S6 Lite flickers touches 3 to 17 ms
 * apart, two "taps" in a row. A second tap sooner than MIN_GAP_MS after the
 * first does not count (the button stays asking).
 *
 * Plain milliseconds of a monotonic clock; no Android, the JVM tests drive it.
 */
public final class ExitTaps {
    public static final long CONFIRM_MS = 3000;
    public static final long MIN_GAP_MS = 300;

    private long armedAt = -1;

    /** A tap on the button. True: close SAI now. */
    public synchronized boolean tap(long now) {
        if (armedAt >= 0 && now - armedAt > CONFIRM_MS) armedAt = -1;
        if (armedAt < 0) {
            armedAt = now;
            return false;
        }
        if (now - armedAt < MIN_GAP_MS) return false;
        armedAt = -1;
        return true;
    }

    /** The button asks "Exit?" now. */
    public synchronized boolean asking(long now) {
        return armedAt >= 0 && now - armedAt <= CONFIRM_MS;
    }

    /** The keys closed or hid: the next tap asks again. */
    public synchronized void reset() {
        armedAt = -1;
    }
}
