package com.winlator.saipen;

/**
 * Tells a single tap of n fingers from a double one (Gestures'
 * TWO_DOUBLE_TAP, THREE_DOUBLE_TAP).
 *
 * When no double tap is set for n fingers, a tap is a single tap at once,
 * exactly as before. When one is set, the first tap waits up to DOUBLE_MS
 * for a second tap of as many fingers; without one, it is a single tap
 * after all. A tap of another number of fingers first lets a waiting tap
 * go as the single tap it was.
 *
 * TouchpadView feeds it every finger tap and, at due(), calls expire().
 * No Android types: the JVM tests drive it with plain numbers.
 */
public final class MultiTap {
    public static final long DOUBLE_MS = 300;

    /** tap()'s answers */
    public static final int SINGLE = 1, DOUBLE = 2, WAIT = 0;

    private int waiting;        /* fingers of the tap waiting, 0 = none */
    private long waitingSince;
    private int waitingX, waitingY;

    /**
     * Before tap(): a tap of another number of fingers is waiting. Returns
     * its fingers (to run as a single tap at x()/y()) and forgets it, or 0.
     */
    public int flushOther(int fingers) {
        if (waiting == 0 || waiting == fingers) return 0;
        int f = waiting;
        waiting = 0;
        return f;
    }

    /** A tap of `fingers` at (x, y); wantsDouble: a double tap is set for it. */
    public int tap(int fingers, boolean wantsDouble, int x, int y, long nowMs) {
        if (!wantsDouble) return SINGLE;
        if (waiting == fingers && nowMs - waitingSince <= DOUBLE_MS) {
            waiting = 0;
            return DOUBLE;
        }
        waiting = fingers;
        waitingSince = nowMs;
        waitingX = x;
        waitingY = y;
        return WAIT;
    }

    /** When the waiting tap becomes a single tap, or -1 if none waits. */
    public long due() {
        return waiting == 0 ? -1 : waitingSince + DOUBLE_MS;
    }

    /** At due(): the fingers of the tap that now is a single tap, or 0. */
    public int expire(long nowMs) {
        if (waiting == 0 || nowMs - waitingSince < DOUBLE_MS) return 0;
        int f = waiting;
        waiting = 0;
        return f;
    }

    /** Where the waiting (or just flushed or expired) tap was. */
    public int x() { return waitingX; }
    public int y() { return waitingY; }

    /** The pen came, or the view went: nothing waits any more. */
    public void reset() {
        waiting = 0;
    }
}
