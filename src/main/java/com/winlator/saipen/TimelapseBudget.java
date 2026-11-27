package com.winlator.saipen;

/**
 * How much room the DLL's timelapse frames get on the device before it
 * starts thinning them (WT_TIMELAPSE_BUDGET_MB). The artist picks it with a
 * slider under the timelapse switch: 2 GB up to a quarter of the free
 * space in half-GB steps, and Auto at the right end (owner, 2026-11-27: the
 * top is the natural place for "as much as fits", and sizes in between). Auto is a quarter of the free space, at
 * least 2 GB. A choice bigger than today's quarter is cut to it, so the
 * frames can never fill the device (owner, 2026-11-26). A fixed 512 MB was about
 * 220 frames of a 568x1024 canvas, and a 30-minute drawing lost most of its
 * frames (owner, 2026-11-26).
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class TimelapseBudget {
    public static final long MIN_MB = 2048, MAX_MB = 20480;
    /** The slider's step. */
    public static final int STEP_MB = 512;
    /** 0 for Auto, else megabytes, MIN_MB up. */
    public static final String PREF_MB = "saipen_timelapse_space_mb";
    /** Before 2026-11-27: 0 for Auto, else whole gigabytes. Read once, if PREF_MB is not there. */
    public static final String PREF_GB = "saipen_timelapse_space_gb";
    public static final int AUTO = 0;
    private static final long MB = 1024 * 1024;

    private TimelapseBudget() {}

    /** Auto's size for `freeBytes`, and the most any choice gets: a quarter
     *  of the free space, MIN_MB to MAX_MB. */
    public static long megabytes(long freeBytes) {
        long quarter = Math.max(0, freeBytes) / 4 / MB;
        return Math.max(MIN_MB, Math.min(MAX_MB, quarter));
    }

    /** The room for the artist's choice (megabytes, AUTO below MIN_MB), cut to today's quarter. */
    public static long megabytes(long freeBytes, int savedMb) {
        long most = megabytes(freeBytes);
        return savedMb < MIN_MB ? most : Math.min(savedMb, most);
    }

    /** An old whole-GB choice as megabytes (AUTO stays AUTO). */
    public static int fromGb(int gb) {
        return gb < 2 ? AUTO : Math.min(gb, (int) (MAX_MB / 1024)) * 1024;
    }

    /** The slider's last step, which is Auto; the steps before it are
     *  MIN_MB, MIN_MB + STEP_MB, ... below today's most. */
    public static int lastStep(long freeBytes) {
        long most = megabytes(freeBytes);
        return (int) ((most - MIN_MB + STEP_MB - 1) / STEP_MB);
    }

    /** The choice at a slider step: AUTO at the last one. */
    public static int mbAt(int step, long freeBytes) {
        return step >= lastStep(freeBytes) ? AUTO : (int) (MIN_MB + (long) step * STEP_MB);
    }

    /** Where a saved choice sits on the slider; one at or past today's most sits on Auto. */
    public static int stepOf(int savedMb, long freeBytes) {
        int last = lastStep(freeBytes);
        if (savedMb < MIN_MB) return last;
        return (int) Math.min(last, (savedMb - MIN_MB) / STEP_MB);
    }

    /** Below this much free space even the smallest room is a big share of
     *  it: the yellow note says so. */
    public static final long LOW_FREE_MB = 4 * MIN_MB;

    public static boolean tooLittleSpace(long freeBytes) {
        return Math.max(0, freeBytes) / MB < LOW_FREE_MB;
    }
}
