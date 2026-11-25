package com.winlator.saipen;

/**
 * Three fingers held still on the screen for HOLD_MS: shows or hides the
 * floating keys (FloatingKeys).
 *
 * The taps are all taken: two fingers are the right click, three the
 * keyboard, four the menu. A three-finger HOLD is free: the canvas reads
 * three fingers only as a tap, and a hold is no tap (TouchpadView's
 * MAX_TAP_MILLISECONDS), so the keyboard does not open as well. A fourth
 * finger, or the fingers moving, is not this gesture.
 *
 * Fed every touch event's finger count and positions; when() says when to
 * look again, due() whether the hold is complete. Fires once per hold.
 *
 * No Android types: the JVM tests drive it with plain numbers.
 */
public final class FingersHold {
    public static final int FINGERS = 3;
    /* how many fingers this hold is: 3 for the on-screen keys, 4 for the
     * gestures' settings over SAI (GesturesOverlay) */
    private final int fingers;
    public static final long HOLD_MS = 800;
    /** How far a finger may wander, in pixels, and still be held still. */
    private final float slopPx;

    private boolean holding, fired;
    private long since;
    private final float[] startX, startY;

    public FingersHold(float slopPx) {
        this(slopPx, FINGERS);
    }

    public FingersHold(float slopPx, int fingers) {
        this.slopPx = slopPx;
        this.fingers = fingers;
        startX = new float[fingers];
        startY = new float[fingers];
    }

    /** Any touch event: `count` fingers down now, at xs/ys. */
    public void onTouch(int count, float[] xs, float[] ys, long nowMs) {
        if (count != fingers) {
            holding = false;
            if (count == 0) fired = false;
            return;
        }
        if (!holding) {
            if (fired) return;   /* lift the fingers before the next one */
            holding = true;
            since = nowMs;
            for (int i = 0; i < fingers; i++) { startX[i] = xs[i]; startY[i] = ys[i]; }
            return;
        }
        for (int i = 0; i < fingers; i++) {
            if (Math.hypot(xs[i] - startX[i], ys[i] - startY[i]) > slopPx) {
                holding = false;
                fired = true;   /* moved: not this gesture until all fingers lift */
                return;
            }
        }
    }

    /** When the hold would be complete, or -1 if no hold is going on. */
    public long when() {
        return holding ? since + HOLD_MS : -1;
    }

    /** True once, when three still fingers have been down HOLD_MS. */
    public boolean due(long nowMs) {
        if (!holding || nowMs - since < HOLD_MS) return false;
        holding = false;
        fired = true;
        return true;
    }
}
