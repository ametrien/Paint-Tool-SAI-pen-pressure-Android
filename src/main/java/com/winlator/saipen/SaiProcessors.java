package com.winlator.saipen;

/**
 * How many processors SAI sees: SAI splits every stroke into about one piece
 * per processor it sees, and the stroke is done when its slowest piece is.
 * On a device with a few fast cores and more slow ones (Galaxy Tab S6 Lite:
 * 2 fast, 6 slow), some pieces land on the slow cores and the ink waits for
 * them. Showing SAI only as many processors as there are fast cores lets
 * every piece run on a fast core (SaiCores' Drawing mode puts them there).
 *
 * Measured on the Tab S6 Lite, 2026-11-25, 20 quick strokes with a size-300
 * brush: SAI's CPU 11.4-15.2 core-seconds with all 8 processors, 8.5 with 2,
 * the ink catching up within 160 ms instead of up to 340. The same as SAI's
 * own setting (Options > Environment, CPU Cores Assignment C, 2 cores), which
 * SAI keeps in an encrypted file (settings2.ssd) we do not touch.
 *
 * How: BOX64_MAXCPU in the container's environment. Wine counts processors
 * with sysconf(_SC_NPROCESSORS_ONLN) (dlls/ntdll/unix/system.c), and Box64
 * answers that with its own count. WINE_CPU_TOPOLOGY does nothing here: it
 * is only in Proton's Wine, not in ours (tried, 2026-11-25).
 *
 * BOX64_MAXCPU only changes the number SAI sees, not where its threads run.
 *
 * The choice: AUTO (the default) follows the device: its fast cores
 * (FastCores), at least 2; ALL when it has no slow cores. Or a number the
 * person picked, or ALL (SAI's own default, every core).
 *
 * No Android types: the JVM tests check the numbers.
 */
public final class SaiProcessors {
    private SaiProcessors() {}

    public static final String PREF = "saipen_sai_processors";
    /** Follow the device (the default). */
    public static final int AUTO = -1;
    /** Every core: SAI's own default, no BOX64_MAXCPU. */
    public static final int ALL = 0;
    /** Fewer would make SAI do everything on one thread. */
    public static final int MIN = 2;
    public static final String ENV = "BOX64_MAXCPU";

    /** The device's own choice: its fast cores, at least MIN; ALL with no
     *  slow cores or unknown speeds (fastCount 0), or when that is every core. */
    public static int suggested(int fastCount, int total) {
        if (fastCount <= 0 || total <= MIN) return ALL;
        int n = Math.max(MIN, fastCount);
        return n >= total ? ALL : n;
    }

    /** What a stored choice means on this device: a number, or ALL. */
    public static int resolve(int stored, int fastCount, int total) {
        if (stored == AUTO) return suggested(fastCount, total);
        if (stored <= ALL || stored >= total) return ALL;
        return Math.max(MIN, stored);
    }

    /** BOX64_MAXCPU's value for a resolved choice; null: leave it out. */
    public static String envValue(int resolved) {
        return resolved > ALL ? String.valueOf(resolved) : null;
    }

    /** The choices to offer, in order: MIN to total - 1, then ALL. */
    public static int[] choices(int total) {
        if (total <= MIN) return new int[] {ALL};
        int[] out = new int[total - MIN + 1];
        for (int i = 0; i < total - MIN; i++) out[i] = MIN + i;
        out[out.length - 1] = ALL;
        return out;
    }
}
