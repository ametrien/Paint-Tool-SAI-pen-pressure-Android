package com.winlator.saipen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * When SAI has finished loading: the moment its processor use drops sharply
 * from the level it was loading at.
 *
 * On the Galaxy Tab S6 Lite SAI loads at 55 to 70 % of a core for 5 to 7 s
 * after its window appears, then falls to 8 to 10 % in one step (speed logs
 * of 2026-11-19). A fixed line such as "under 25 %" fits only this tablet:
 * a faster device may load at 20 % (and pass the line while still loading),
 * a slower one may idle above 25 % (and never pass it). So the line is
 * SAI's own: DROP_TO of the median of what it used so far. The median, not
 * the peak, so one busy spike does not lift the line into the loading level.
 *
 * Readings are percent of one core over the last second, all taken after
 * SAI's window appeared. No Android: the JVM tests drive it.
 */
public final class SaiCalm {
    /** Settled under this share of the loading level: about a third. On the
     *  tablet it dropped to a sixth. */
    public static final double DROP_TO = 0.35;
    /** This little is calm on any device, whatever came before. */
    public static final int FLOOR = 5;
    /** Readings of the loading level needed before a drop can count. */
    public static final int MIN_LOADING_READINGS = 3;

    private final List<Integer> loading = new ArrayList<>();
    private long settledAt = -1;

    /** One reading. True the moment SAI has settled (once). */
    public synchronized boolean onReading(int percent, long now) {
        if (settledAt >= 0 || percent < 0) return false;
        if (percent < FLOOR
            || (loading.size() >= MIN_LOADING_READINGS && percent < loadingLevel() * DROP_TO)) {
            settledAt = now;
            return true;
        }
        loading.add(percent);
        return false;
    }

    public synchronized boolean settled() {
        return settledAt >= 0;
    }

    public synchronized long settledAt() {
        return settledAt;
    }

    /** The median reading so far, -1 before any. */
    public synchronized int loadingLevel() {
        if (loading.isEmpty()) return -1;
        List<Integer> s = new ArrayList<>(loading);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
