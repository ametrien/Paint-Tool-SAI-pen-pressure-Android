package com.winlator.saipen;

/**
 * How much room the DLL's timelapse frames get on the device before it
 * starts thinning them (WT_TIMELAPSE_BUDGET_MB). The artist picks it with a
 * slider under the timelapse switch: Auto, or 2 GB up to a quarter of the
 * free space (never more than 20 GB). Auto is a quarter of the free space, at
 * least 2 GB. A choice bigger than today's quarter is cut to it, so the
 * frames can never fill the device (owner, 2026-11-26). A fixed 512 MB was about
 * 220 frames of a 568x1024 canvas, and a 30-minute drawing lost most of its
 * frames (owner, 2026-11-26).
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class TimelapseBudget {
    public static final long MIN_MB = 2048, MAX_MB = 20480;
    /** 0 for Auto, else gigabytes, MIN_GB to MAX_GB. */
    public static final String PREF_GB = "saipen_timelapse_space_gb";
    public static final int AUTO = 0, MIN_GB = 2, MAX_GB = 20;
    private static final long MB = 1024 * 1024;

    private TimelapseBudget() {}

    /** Auto's size for `freeBytes`. */
    public static long megabytes(long freeBytes) {
        long quarter = Math.max(0, freeBytes) / 4 / MB;
        return Math.max(MIN_MB, Math.min(MAX_MB, quarter));
    }

    /** A saved choice as AUTO or MIN_GB..MAX_GB. */
    public static int choice(int saved) {
        return saved < MIN_GB ? AUTO : Math.min(MAX_GB, saved);
    }

    /** The slider's right end: a quarter of the free space in whole GB,
     *  MIN_GB to MAX_GB. */
    public static int maxGb(long freeBytes) {
        long quarterGb = Math.max(0, freeBytes) / 4 / (1024 * MB);
        return (int) Math.max(MIN_GB, Math.min(MAX_GB, quarterGb));
    }

    /** The room for the artist's choice, cut to today's quarter. */
    public static long megabytes(long freeBytes, int savedGb) {
        int gb = choice(savedGb);
        return gb == AUTO ? megabytes(freeBytes) : Math.min(gb, maxGb(freeBytes)) * 1024L;
    }

    /** Below this much free space even the smallest room is a big share of
     *  it: the yellow note says so. */
    public static final long LOW_FREE_MB = 4 * MIN_MB;

    public static boolean tooLittleSpace(long freeBytes) {
        return Math.max(0, freeBytes) / MB < LOW_FREE_MB;
    }
}
