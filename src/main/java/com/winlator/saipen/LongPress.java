package com.winlator.saipen;

/**
 * One finger held still for HOLD_MS (Gestures' LONG_PRESS). Only looked
 * for when an action is set, so by default one finger works as before.
 *
 * It is not a long press when the finger moves more than the slop (a drag
 * of the cursor), when another finger comes (a two-finger gesture or a
 * three-finger hold), or when the finger lifts first (a tap).
 *
 * No Android types: the JVM tests drive it with plain numbers.
 */
public final class LongPress {
    public static final long HOLD_MS = 500;

    private final float slopPx;
    private boolean holding;
    private long since;
    private float startX, startY;

    public LongPress(float slopPx) {
        this.slopPx = slopPx;
    }

    /** The first finger touched. */
    public void down(float x, float y, long nowMs) {
        holding = true;
        since = nowMs;
        startX = x;
        startY = y;
    }

    /** The finger is at (x, y) now. */
    public void move(float x, float y) {
        if (holding && Math.hypot(x - startX, y - startY) > slopPx) holding = false;
    }

    /** Another finger, the finger lifted, the pen: not a long press. */
    public void cancel() {
        holding = false;
    }

    /** When it would be due, or -1 if no press is going on. */
    public long when() {
        return holding ? since + HOLD_MS : -1;
    }

    /** True once, when the finger has been still for HOLD_MS. */
    public boolean due(long nowMs) {
        if (!holding || nowMs - since < HOLD_MS) return false;
        holding = false;
        return true;
    }
}
