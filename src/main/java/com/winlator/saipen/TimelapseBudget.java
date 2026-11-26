package com.winlator.saipen;

/**
 * How much room the DLL's timelapse frames get on the device before it
 * starts thinning them (WT_TIMELAPSE_BUDGET_MB): a quarter of the free space,
 * at least 2 GB, at most 20 GB (owner, 2026-11-26). A fixed 512 MB was about 220 frames of a
 * 568x1024 canvas, and a 30-minute drawing lost most of its frames
 * (owner, 2026-11-26).
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class TimelapseBudget {
    public static final long MIN_MB = 2048, MAX_MB = 20480;

    private TimelapseBudget() {}

    public static long megabytes(long freeBytes) {
        long quarter = Math.max(0, freeBytes) / 4 / (1024 * 1024);
        return Math.max(MIN_MB, Math.min(MAX_MB, quarter));
    }
}
