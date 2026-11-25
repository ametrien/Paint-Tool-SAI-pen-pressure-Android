package com.winlator.saipen;

/**
 * One finger that starts at the left or right edge of the screen and
 * swipes inward (Gestures' EDGE_LEFT, EDGE_RIGHT).
 *
 * The finger must land within edgePx of the edge and travel inward at
 * least distPx, more sideways than up or down. Another finger cancels it.
 * It fires once per touch, as soon as the finger has gone far enough.
 *
 * Android's own Back gesture takes edge swipes on phones and tablets with
 * gesture navigation; TouchpadView asks Android to leave the middle of
 * each side to SAI Pen (View.setSystemGestureExclusionRects), which Android
 * allows for at most 200 dp of each side.
 *
 * No Android types: the JVM tests drive it with plain numbers.
 */
public final class EdgeSwipe {
    public static final int NONE = 0, LEFT = 1, RIGHT = 2;

    private final float edgePx, distPx;
    private int side;
    private float startX, startY;

    public EdgeSwipe(float edgePx, float distPx) {
        this.edgePx = edgePx;
        this.distPx = distPx;
    }

    /** The first finger touched at (x, y) of a view `width` wide. wantLeft,
     *  wantRight: whether that side has an action at all. */
    public void down(float x, float y, float width, boolean wantLeft, boolean wantRight) {
        side = NONE;
        if (wantLeft && x <= edgePx) side = LEFT;
        else if (wantRight && x >= width - edgePx) side = RIGHT;
        startX = x;
        startY = y;
    }

    /** The finger is at (x, y): LEFT or RIGHT once the swipe is complete
     *  (then never again in this touch), else NONE. */
    public int move(float x, float y) {
        if (side == NONE) return NONE;
        float in = side == LEFT ? x - startX : startX - x;
        float across = Math.abs(y - startY);
        if (in < 0 && -in > edgePx) {
            side = NONE;   /* went the wrong way */
            return NONE;
        }
        if (in >= distPx && in > 2 * across) {
            int done = side;
            side = NONE;
            return done;
        }
        if (across > distPx && across > 2 * Math.max(0, in)) side = NONE;   /* a drag along the edge */
        return NONE;
    }

    /** Another finger, the finger lifted, the pen: no swipe. */
    public void cancel() {
        side = NONE;
    }

    public boolean active() {
        return side != NONE;
    }
}
