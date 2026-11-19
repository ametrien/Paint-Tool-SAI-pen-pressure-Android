package com.winlator.saipen;

/**
 * Sends a pinch's zoom steps to SAI as fast as SAI can redraw them, and
 * no faster.
 *
 * Every step makes SAI redraw its canvas, which on a tablet (emulated)
 * takes a visible moment. Steps sent faster than that pile up and play out
 * after the fingers have stopped; steps held back by a fixed timer (0.3.1:
 * 200 ms) wait for nothing when SAI is already done. So the next step goes
 * as soon as SAI has finished drawing the previous one: SAI drew something
 * after the step, then nothing for QUIET_MS. Steps that arrive meanwhile are
 * added up and go together.
 *
 * THE TRAP: SAI may draw nothing at all for a step (already at its largest
 * zoom), so waiting for a redraw could wait forever; after MAX_WAIT_MS the
 * next step goes anyway.
 *
 * It also keeps how long the last redraw took, for the log (adb logcat -s
 * SaiPen), to compare sharpness settings by numbers.
 *
 * No Android types: the JVM tests drive it with plain times.
 */
public final class ZoomPacer {
    /** SAI is done with a step when it drew after it and then not for this
     *  long. Measured on the Galaxy Tab S6 Lite at 2000x1200: SAI draws a
     *  step in 46 to 163 ms, 68 ms typically; 80 ms of quiet on top made
     *  each step wait longer than the drawing itself. */
    public static final long QUIET_MS = 40;
    /** Never wait longer than this for SAI to show it drew a step. */
    public static final long MAX_WAIT_MS = 600;

    private int pending;
    private boolean busy;
    private long sentAt, lastUpdate;
    private boolean drewSinceSent;
    private long lastRedrawMs = -1;

    /** Steps the pinch wants now (+ in, - out). Returns the steps to send
     *  to SAI right away, 0 to wait. */
    public synchronized int request(int steps, long now) {
        pending += steps;
        return flushIfFree(now);
    }

    /** Some window's content changed. */
    public synchronized void onContentUpdate(long now) {
        if (!busy) return;
        lastUpdate = now;
        drewSinceSent = true;
    }

    /** Call every few tens of ms while busy(). Returns the steps to send
     *  now, 0 to wait. */
    public synchronized int tick(long now) {
        if (busy) {
            if (drewSinceSent && now - lastUpdate >= QUIET_MS) {
                busy = false;
                lastRedrawMs = lastUpdate - sentAt;
            }
            else if (now - sentAt >= MAX_WAIT_MS) {
                busy = false;
                lastRedrawMs = drewSinceSent ? lastUpdate - sentAt : -1;
            }
        }
        return flushIfFree(now);
    }

    public synchronized boolean busy() {
        return busy || pending != 0;
    }

    /** How long SAI took to draw the last step that finished, -1 if unknown. */
    public synchronized long lastRedrawMs() {
        return lastRedrawMs;
    }

    private int flushIfFree(long now) {
        if (busy || pending == 0) return 0;
        int steps = pending;
        pending = 0;
        busy = true;
        sentAt = now;
        lastUpdate = now;
        drewSinceSent = false;
        return steps;
    }
}
