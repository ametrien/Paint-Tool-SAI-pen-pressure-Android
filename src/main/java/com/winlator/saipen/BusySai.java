package com.winlator.saipen;

/**
 * SAI filling every core it can use, and what SAI Pen does about it.
 *
 * Why (2026-11-28, Galaxy Tab S6 Lite): with "Cores SAI sees" at 2 and
 * Fast cores on All of SAI, SAI's two drawing threads went to 100% each
 * after a line and an undo and stayed there. Every other SAI thread, its
 * window's among them, had to wait for the same two cores: SAI stopped
 * answering, the red cross too, and the drawing was lost.
 *
 * So once a second the speed log's thread gives SAI's processor use here.
 * When SAI has used at least FULL_SHARE of all it can use for FULL_FOR_MS,
 * SAI Pen lets SAI's threads onto every core (OPEN_ALL), so the waiting
 * ones get a core of their own and SAI can answer again. When SAI has been
 * under CALM_SHARE for CALM_FOR_MS, the fast cores come back (BACK).
 *
 * What SAI can use: the cores it sees (BOX64_MAXCPU, SaiProcessors) split
 * its work into as many threads, and the cores it is let onto. With 2 seen,
 * 200% is all of it whatever it runs on.
 *
 * It cannot help a SAI going round in a loop forever; it helps a SAI that
 * is only too busy to answer.
 *
 * No Android types: the JVM tests feed it numbers.
 */
public final class BusySai {
    public static final long FULL_FOR_MS = 10_000;
    public static final long CALM_FOR_MS = 30_000;
    static final double FULL_SHARE = 0.9;
    static final double CALM_SHARE = 0.6;
    /** A speed test's lower "full" (adb shell setprop debug.saipen.busy_share 0.2),
     *  so ordinary drawing shows the switch to every core and back. */
    public static final String PROP_SHARE = "debug.saipen.busy_share";

    private final double fullShare, calmShare;

    public enum Step { NONE, OPEN_ALL, BACK }

    /** Cores' worth SAI can use at once (2 = 200%). */
    private final int cores;
    private long fullSince = -1, calmSince = -1;
    private boolean open;
    /** Seconds' worth of time SAI spent full (at least FULL_SHARE), and the longest stretch. */
    private long fullMs, longestFullMs, lastAt = -1;

    /**
     * @param seen    the cores SAI sees (a number), or 0 for all of them
     * @param allowed the cores SAI's threads may run on now (the fast
     *                cores with Fast cores: All of SAI, else every core)
     */
    public BusySai(int seen, int allowed) {
        this(seen, allowed, FULL_SHARE);
    }

    /** fullShare: "full" from this share of all SAI can use (a test's lower one);
     *  calm is then under CALM_SHARE or half of it, whichever is less. */
    public BusySai(int seen, int allowed, double fullShare) {
        this.fullShare = fullShare > 0 && fullShare <= 1 ? fullShare : FULL_SHARE;
        this.calmShare = Math.min(CALM_SHARE, this.fullShare / 2);
        int c = allowed;
        if (seen > 0 && seen < c) c = seen;
        cores = Math.max(1, c);
    }

    public int cores() {
        return cores;
    }

    public boolean isOpen() {
        return open;
    }

    /** SAI's use as a share of all it can use: 1.0 is full. */
    public double share(int saiPercent) {
        return saiPercent / (100.0 * cores);
    }

    /** Once a second. saiPercent: percent of one core, -1 if not known. */
    public Step tick(long now, int saiPercent) {
        if (saiPercent < 0) {
            /* not known (SAI starting or gone): no stretch goes on over it */
            lastAt = fullSince = calmSince = -1;
            return Step.NONE;
        }
        double s = share(saiPercent);
        boolean full = s >= fullShare;
        if (full && lastAt >= 0) {
            fullMs += now - lastAt;
            if (fullSince >= 0) longestFullMs = Math.max(longestFullMs, now - fullSince);
        }
        lastAt = now;
        if (full) {
            if (fullSince < 0) fullSince = now;
        }
        else fullSince = -1;
        if (s < calmShare) {
            if (calmSince < 0) calmSince = now;
        }
        else calmSince = -1;

        if (!open && fullSince >= 0 && now - fullSince >= FULL_FOR_MS) {
            open = true;
            calmSince = -1;
            return Step.OPEN_ALL;
        }
        if (open && calmSince >= 0 && now - calmSince >= CALM_FOR_MS) {
            open = false;
            fullSince = -1;
            return Step.BACK;
        }
        return Step.NONE;
    }

    /** How long SAI was full in all, ms. */
    public long fullMs() {
        return fullMs;
    }

    /** The longest time SAI was full in one go, ms. */
    public long longestFullMs() {
        return longestFullMs;
    }
}
