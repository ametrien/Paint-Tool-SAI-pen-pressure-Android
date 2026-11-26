package com.winlator.saipen;

/**
 * How much room the DLL's timelapse frames get on the device before it
 * starts thinning them (WT_TIMELAPSE_BUDGET_MB). The artist picks it with a
 * slider under the timelapse switch: Auto, or 2 to 20 GB. Auto is a quarter
 * of the free space, at least 2 GB, at most 20 GB. A fixed 512 MB was about
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

    /** The room for the artist's choice. */
    public static long megabytes(long freeBytes, int savedGb) {
        int gb = choice(savedGb);
        return gb == AUTO ? megabytes(freeBytes) : gb * 1024L;
    }

    /** Too little free space for that room: frames will be thinned, or the
     *  device fills up. */
    public static boolean tooLittleSpace(long freeBytes, int savedGb) {
        return Math.max(0, freeBytes) / MB < megabytes(freeBytes, savedGb);
    }
}
