package com.winlator.saipen;

/**
 * The closing cover goes up the moment SAI's main window disappears.
 *
 * Closing SAI with its red cross used to leave a black screen with a white
 * cursor for 2 to 3 s (the owner, 2026-11-19): the app noticed only from
 * the process list, once a second, and waited for SAI's process to stay
 * gone. The window goes first, so the cover goes up then, and the app
 * leaves once the process is gone (SaiProcessWatch).
 *
 * THE TRAP: a window can go without SAI closing (minimised, or SAI making
 * its window anew). If SAI's window comes back, or SAI still runs
 * GIVE_UP_MS later, the cover comes down again.
 *
 * Plain milliseconds of a monotonic clock; no Android, the JVM tests drive it.
 */
public final class SaiClosing {
    public static final long GIVE_UP_MS = 5000;

    private long since = -1;

    /** SAI's main window was unmapped. True: put the cover up now. */
    public synchronized boolean onSaiWindowGone(long now) {
        if (since >= 0) return false;
        since = now;
        return true;
    }

    /** A SAI window was mapped. True: take the cover down, SAI is back. */
    public synchronized boolean onSaiWindowBack() {
        if (since < 0) return false;
        since = -1;
        return true;
    }

    /** A look at the processes. True: SAI did not close after all, take
     *  the cover down. */
    public synchronized boolean giveUp(boolean saiRunning, long now) {
        if (since < 0 || !saiRunning || now - since < GIVE_UP_MS) return false;
        since = -1;
        return true;
    }

    public synchronized boolean covered() {
        return since >= 0;
    }
}
