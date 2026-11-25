package com.winlator.saipen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Is the device ready to draw? SAI Pen's screen shows, above Start SAI, one
 * summary line with a bar (one piece per check: green, yellow, red, gray)
 * and, folded under it, every check with its number and what helps.
 *
 * The checks: free memory, battery saver (Android slows the processor down
 * on purpose), heat (the processor slows itself down), free storage, the
 * screen sharpness, and how fast SAI drew in the last sessions here.
 *
 * The sharpness advice comes only from this device's own speed logs
 * (SpeedHistory): the first rule (4 GB and above 900 lags) was a guess from
 * one tablet, and its own logs said otherwise (2026-11-19, Galaxy Tab S6
 * Lite: ink 29 ms at 720, 22 at 900, 20 at 1200).
 *
 * What it cannot do: close other apps (Android lets no app do that) or see
 * other apps' processor use (hidden from apps since Android 8). So it only
 * says what helps.
 *
 * No Android types: the launcher reads the numbers (ActivityManager,
 * PowerManager, StatFs, the speed logs) and the JVM tests check the rules.
 */
public final class DeviceReadiness {
    public enum Kind { MEMORY, BATTERY, TEMPERATURE, STORAGE, SHARPNESS, PAST }

    /** OK: green; BETTER: could be better, yellow; LAG: will lag, red; UNKNOWN: gray. */
    public enum Level { OK, BETTER, LAG, UNKNOWN }

    /** Free memory below these: other apps are taking what SAI needs.
     *  Android's "free" counts what it can take back from cached apps too,
     *  and a 4 GB tablet with nothing open shows about 1.1 GB (the Galaxy
     *  Tab S6 Lite, 2026-11-23). */
    public static final long LOW_MEMORY_MB = 700, VERY_LOW_MEMORY_MB = 400;
    /** Free storage below these: saving, Wine and the timelapse suffer. */
    public static final long LOW_STORAGE_MB = 3072, VERY_LOW_STORAGE_MB = 1024;
    /** PowerManager thermal status: MODERATE, the processor starts slowing
     *  down; SEVERE, it slows down a lot. */
    public static final int THERMAL_MODERATE = 2, THERMAL_SEVERE = 3;
    /** Pen to screen in the last sessions: up to OK_MS is fine, up to BETTER_MS could be better. */
    public static final int PAST_OK_MS = 40, PAST_BETTER_MS = 70;
    /** Another sharpness is worth it when the current one is this much slower. */
    public static final double SHARPNESS_SLOWER = 1.15;

    /** What the launcher measured; -1 for a number it couldn't read. */
    public static final class Input {
        public long availMb = -1, totalMb = -1, freeDiskMb = -1;
        public boolean lowMemory, powerSave;
        public int thermal = -1;
        /** SAI's screen sharpness (its height in pixels) */
        public int sharpness;
        /** pen to screen per sharpness, from this device's speed logs (SpeedHistory) */
        public Map<Integer, CoresCompare.Result> bySharpness;
        /** pen to screen in the last sessions, ms (SpeedHistory), -1 if not enough */
        public int pastMs = -1;
    }

    public static final class Check {
        public final Kind kind;
        public final Level level;
        /** SHARPNESS at BETTER: the faster sharpness and both medians */
        public final int fasterSharpness, fasterMs, currentMs;

        Check(Kind kind, Level level) {
            this(kind, level, 0, -1, -1);
        }

        Check(Kind kind, Level level, int fasterSharpness, int fasterMs, int currentMs) {
            this.kind = kind;
            this.level = level;
            this.fasterSharpness = fasterSharpness;
            this.fasterMs = fasterMs;
            this.currentMs = currentMs;
        }

        @Override
        public String toString() {
            return kind + " " + level;
        }
    }

    private DeviceReadiness() {}

    /** Every check, in the order shown. */
    public static List<Check> checks(Input in) {
        List<Check> out = new ArrayList<>();
        Level memory = in.availMb < 0 && !in.lowMemory ? Level.UNKNOWN
            : in.lowMemory || in.availMb < VERY_LOW_MEMORY_MB ? Level.LAG
            : in.availMb < LOW_MEMORY_MB ? Level.BETTER : Level.OK;
        out.add(new Check(Kind.MEMORY, memory));
        out.add(new Check(Kind.BATTERY, in.powerSave ? Level.BETTER : Level.OK));
        Level heat = in.thermal < 0 ? Level.UNKNOWN
            : in.thermal >= THERMAL_SEVERE ? Level.LAG
            : in.thermal >= THERMAL_MODERATE ? Level.BETTER : Level.OK;
        out.add(new Check(Kind.TEMPERATURE, heat));
        Level disk = in.freeDiskMb < 0 ? Level.UNKNOWN
            : in.freeDiskMb < VERY_LOW_STORAGE_MB ? Level.LAG
            : in.freeDiskMb < LOW_STORAGE_MB ? Level.BETTER : Level.OK;
        out.add(new Check(Kind.STORAGE, disk));
        out.add(sharpness(in));
        Level past = in.pastMs < 0 ? Level.UNKNOWN
            : in.pastMs <= PAST_OK_MS ? Level.OK
            : in.pastMs <= PAST_BETTER_MS ? Level.BETTER : Level.LAG;
        out.add(new Check(Kind.PAST, past));
        return out;
    }

    /** BETTER only if another sharpness drew clearly faster on this device,
     *  each with enough strokes in at least SpeedHistory.MIN_SESSIONS sessions. */
    static Check sharpness(Input in) {
        Map<Integer, CoresCompare.Result> by = in.bySharpness;
        CoresCompare.Result cur = by == null ? null : by.get(in.sharpness);
        int curMs = enough(cur) ? cur.median() : -1;
        if (curMs < 0) return new Check(Kind.SHARPNESS, Level.OK);
        int bestH = 0, bestMs = Integer.MAX_VALUE;
        for (Map.Entry<Integer, CoresCompare.Result> e : by.entrySet()) {
            if (e.getKey() == in.sharpness || !enough(e.getValue())) continue;
            int ms = e.getValue().median();
            if (ms < bestMs) {
                bestMs = ms;
                bestH = e.getKey();
            }
        }
        if (bestH > 0 && curMs >= bestMs * SHARPNESS_SLOWER) {
            return new Check(Kind.SHARPNESS, Level.BETTER, bestH, bestMs, curMs);
        }
        return new Check(Kind.SHARPNESS, Level.OK, 0, -1, curMs);
    }

    private static boolean enough(CoresCompare.Result r) {
        return r != null && r.sessions >= SpeedHistory.MIN_SESSIONS && r.median() >= 0;
    }

    /** The worst level among the checks (UNKNOWN counts as OK). */
    public static Level overall(List<Check> checks) {
        Level worst = Level.OK;
        for (Check c : checks) {
            if (c.level == Level.LAG) return Level.LAG;
            if (c.level == Level.BETTER) worst = Level.BETTER;
        }
        return worst;
    }

    /** How many checks are BETTER or LAG. */
    public static int toImprove(List<Check> checks) {
        int n = 0;
        for (Check c : checks) if (c.level == Level.BETTER || c.level == Level.LAG) n++;
        return n;
    }
}
