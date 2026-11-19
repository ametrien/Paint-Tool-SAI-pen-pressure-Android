package com.winlator.saipen;

import java.util.Locale;

/**
 * When the start-up cover may go: SAI's window is up and done drawing.
 *
 * For the first seconds after it starts, SAI shows a grey, half-drawn,
 * not yet centred window while it lays out its panels (seen on the Galaxy
 * Tab S6 Lite on 2026-11-18: black areas 7 s after the start, all drawn
 * 10 s later). That looks broken, so the session starts under a cover
 * ("Starting SAI") that lifts once:
 *
 * - a window titled "PaintTool SAI..." has appeared, at least
 *   MIN_SHOWN_AFTER_MAP_MS ago, and
 * - SAI has settled: its processor use over the last CPU_WINDOW_MS dropped
 *   sharply from the level it was loading at (SaiCalm). Where its processor
 *   time cannot be read, the older rule stands instead: no window content
 *   changed for QUIET_MS.
 *
 * Why the processor and not the window: with the window rule the cover
 * lifted 3 to 4 s too early, and SAI was sluggish meanwhile (the owner,
 * 2026-11-19). The speed log of that start showed why: after its window
 * appears SAI works at 55 to 70 % of a core for 5 to 7 s with the window
 * still, paints the whole window once (110+ updates in a second), and only
 * then drops under 10 %. 720 lines: window 8.3 s, cover 11.4 s, calm 15.3 s;
 * 900 lines: window 6.4 s, cover 9.4 s, calm 13.3 s.
 *
 * THE TRAP: never cover something the person must see, and never forever.
 * SAI's status bar may keep redrawing, so after MAX_AFTER_MAP_MS the cover
 * goes anyway. Another titled window (an error, SAI's missing-files dialog
 * under another title) lifts it after MAX_AFTER_OTHER_WINDOW_MS, and with
 * no window at all it gives up after MAX_TOTAL_MS.
 *
 * Times are plain milliseconds from any monotonic clock; no Android here,
 * the JVM tests drive it.
 */
public final class SaiStartup {
    /** At least this long after SAI's window appears. SAI goes on laying
     *  out its panels for a few seconds after that, sluggish meanwhile;
     *  1.5 s (0.3.0) showed that, 5 s (0.3.2) was longer than needed. */
    public static final long MIN_SHOWN_AFTER_MAP_MS = 3000;
    public static final long QUIET_MS = 1000;
    public static final long MAX_AFTER_MAP_MS = 15000;
    public static final long MAX_AFTER_OTHER_WINDOW_MS = 8000;
    public static final long MAX_TOTAL_MS = 120000;
    /** Over this long. */
    public static final long CPU_WINDOW_MS = 1000;
    /** Linux counts processor time in 1/100 s. */
    public static final int TICKS_PER_SECOND = 100;

    private final long start;
    private long saiMapped = -1;
    private long otherMapped = -1;
    private long lastUpdate = -1;
    private final java.util.ArrayDeque<long[]> cpu = new java.util.ArrayDeque<>();
    private boolean cpuKnown = false;
    private final SaiCalm calm = new SaiCalm();

    public SaiStartup(long now) {
        start = now;
    }

    public static boolean isSaiTitle(String title) {
        return title != null && title.toLowerCase(Locale.ROOT).contains("painttool sai");
    }

    /** A window became visible. Not for Wine's desktop window. */
    public synchronized void onWindowMapped(String title, long now) {
        if (isSaiTitle(title)) {
            if (saiMapped < 0) {
                saiMapped = now;
                lastUpdate = now;
            }
        }
        else if (title != null && !title.isEmpty() && otherMapped < 0) {
            otherMapped = now;
        }
    }

    /** Some window's content changed. */
    public synchronized void onContentUpdate(long now) {
        if (saiMapped >= 0) lastUpdate = now;
    }

    /**
     * SAI's processor time so far, in clock ticks (SaiProcessWatch.cpuTicks),
     * a few times a second; -1 when SAI's process is not found.
     */
    public synchronized void onSaiCpuTicks(long ticks, long now) {
        if (ticks < 0) return;
        cpu.addLast(new long[] {now, ticks});
        /* keep the newest sample that is at least CPU_WINDOW_MS old */
        while (cpu.size() > 2) {
            long[] second = cpu.toArray(new long[0][])[1];
            if (now - second[0] >= CPU_WINDOW_MS) cpu.pollFirst();
            else break;
        }
        long[] old = cpu.peekFirst();
        long dt = now - old[0];
        if (dt < CPU_WINDOW_MS) return;
        cpuKnown = true;
        long percent = (ticks - old[1]) * 1000L * 100 / TICKS_PER_SECOND / dt;
        /* only a stretch wholly after SAI's window appeared */
        if (saiMapped >= 0 && old[0] >= saiMapped) calm.onReading((int) percent, now);
    }

    public synchronized boolean ready(long now) {
        if (now - start >= MAX_TOTAL_MS) return true;
        if (saiMapped >= 0) {
            if (now - saiMapped >= MAX_AFTER_MAP_MS) return true;
            if (now - saiMapped < MIN_SHOWN_AFTER_MAP_MS) return false;
            /* THE TRAP: a calm second from before SAI's window does not
             * count (only readings after it reach SaiCalm) */
            if (cpuKnown) return calm.settled();
            return now - lastUpdate >= QUIET_MS;
        }
        return otherMapped >= 0 && now - otherMapped >= MAX_AFTER_OTHER_WINDOW_MS;
    }

    /** What the cover says: 0 = Windows (Wine) and SAI are starting,
     *  1 = SAI's window is up and SAI is drawing it. */
    public synchronized int stage() {
        return saiMapped >= 0 ? 1 : 0;
    }

    /** Whole seconds since the start, for "7 s" on the cover: it shows the
     *  start is alive (a still cover read as frozen on the tablet). */
    public long secondsSince(long now) {
        return Math.max(0, (now - start) / 1000);
    }

    /** Where the progress bar stands while Windows and SAI start: it
     *  slows down towards this and never passes it before SAI's window. */
    public static final int PROGRESS_BEFORE_WINDOW = 80;
    /** How fast it gets there: about 2/3 of the way in this time. On the
     *  Galaxy Tab S6 Lite SAI's window came after 6 to 9 s (2026-11-18). */
    public static final long PROGRESS_TIME_MS = 5000;

    /**
     * The progress bar, 0..100. A proper bar that fills (on the tablet a
     * spinner read as a "refresh" button and the start looked frozen), but
     * there is nothing to count while Wine starts, so it is an estimate:
     * it rises fast and then ever slower towards 80 % until SAI's window is
     * up, then goes on to 100 % while SAI draws it. It never goes back.
     */
    public synchronized int progress(long now) {
        if (ready(now)) return 100;
        double before = PROGRESS_BEFORE_WINDOW
            * (1 - Math.exp(-(double) Math.max(0, now - start) / PROGRESS_TIME_MS));
        if (saiMapped < 0) return (int) before;
        double atMap = PROGRESS_BEFORE_WINDOW
            * (1 - Math.exp(-(double) Math.max(0, saiMapped - start) / PROGRESS_TIME_MS));
        /* THE TRAP: a line to 99 % over MIN_SHOWN_AFTER_MAP_MS then stood
         * there for as long as SAI kept drawing (up to 15 s): it looked
         * stuck. Now an even line that reaches 99 % only at the cover's
         * limit, MAX_AFTER_MAP_MS; when SAI is ready sooner it jumps to 100. */
        double part = Math.min(1.0, (double) (now - saiMapped) / MAX_AFTER_MAP_MS);
        return (int) Math.min(99, atMap + (99 - atMap) * part);
    }

    /** For the log: how long it took, or -1 while it lasts. */
    public synchronized long saiWindowAfterMs() {
        return saiMapped >= 0 ? saiMapped - start : -1;
    }
}
