package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import com.winlator.saipen.DeviceReadiness.Check;
import com.winlator.saipen.DeviceReadiness.Kind;
import com.winlator.saipen.DeviceReadiness.Level;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class DeviceReadinessTest {
    private static DeviceReadiness.Input fine() {
        DeviceReadiness.Input in = new DeviceReadiness.Input();
        in.availMb = 4000;
        in.totalMb = 8000;
        in.thermal = 0;
        in.freeDiskMb = 50_000;
        in.sharpness = 1200;
        in.pastMs = 30;
        return in;
    }

    private static Level level(DeviceReadiness.Input in, Kind kind) {
        for (Check c : DeviceReadiness.checks(in)) if (c.kind == kind) return c.level;
        throw new AssertionError(kind);
    }

    private static CoresCompare.Result result(int sessions, int ms) {
        CoresCompare.Result r = new CoresCompare.Result();
        r.sessions = sessions;
        for (int i = 0; i < CoresCompare.MIN_STROKES; i++) r.toScreen.add(ms);
        return r;
    }

    @Test
    public void aReadyDeviceIsAllGreen() {
        List<Check> c = DeviceReadiness.checks(fine());
        assertEquals("[MEMORY OK, BATTERY OK, TEMPERATURE OK, STORAGE OK, SHARPNESS OK, PAST OK]", c.toString());
        assertEquals(Level.OK, DeviceReadiness.overall(c));
        assertEquals(0, DeviceReadiness.toImprove(c));
    }

    @Test
    public void memory() {
        DeviceReadiness.Input in = fine();
        in.availMb = 1100;                         /* an idle 4 GB tablet (Tab S6 Lite) */
        assertEquals(Level.OK, level(in, Kind.MEMORY));
        in.availMb = 600;
        assertEquals(Level.BETTER, level(in, Kind.MEMORY));
        in.availMb = 300;
        assertEquals(Level.LAG, level(in, Kind.MEMORY));
        in.availMb = 3000;
        in.lowMemory = true;
        assertEquals("Android says so", Level.LAG, level(in, Kind.MEMORY));
        in = fine();
        in.availMb = -1;
        assertEquals(Level.UNKNOWN, level(in, Kind.MEMORY));
    }

    @Test
    public void batteryHeatAndStorage() {
        DeviceReadiness.Input in = fine();
        in.powerSave = true;
        in.thermal = DeviceReadiness.THERMAL_MODERATE;
        in.freeDiskMb = 2000;
        assertEquals(Level.BETTER, level(in, Kind.BATTERY));
        assertEquals(Level.BETTER, level(in, Kind.TEMPERATURE));
        assertEquals(Level.BETTER, level(in, Kind.STORAGE));
        in.thermal = DeviceReadiness.THERMAL_SEVERE;
        in.freeDiskMb = 500;
        assertEquals(Level.LAG, level(in, Kind.TEMPERATURE));
        assertEquals(Level.LAG, level(in, Kind.STORAGE));
        in.thermal = 1;                             /* light warmth is fine */
        assertEquals(Level.OK, level(in, Kind.TEMPERATURE));
        in.thermal = -1;
        assertEquals(Level.UNKNOWN, level(in, Kind.TEMPERATURE));
    }

    @Test
    public void pastSessions() {
        DeviceReadiness.Input in = fine();
        in.pastMs = DeviceReadiness.PAST_OK_MS;
        assertEquals(Level.OK, level(in, Kind.PAST));
        in.pastMs = DeviceReadiness.PAST_OK_MS + 1;
        assertEquals(Level.BETTER, level(in, Kind.PAST));
        in.pastMs = DeviceReadiness.PAST_BETTER_MS + 1;
        assertEquals(Level.LAG, level(in, Kind.PAST));
        in.pastMs = -1;
        assertEquals("a new install", Level.UNKNOWN, level(in, Kind.PAST));
    }

    @Test
    public void sharpnessIsAdvisedOnlyFromThisDevicesLogs() {
        DeviceReadiness.Input in = fine();
        in.totalMb = 3400;                          /* the old guess: 4 GB at 1200 lags */
        assertEquals("no logs: no advice", Level.OK, level(in, Kind.SHARPNESS));

        Map<Integer, CoresCompare.Result> by = new TreeMap<>();
        by.put(1200, result(3, 40));
        by.put(900, result(3, 30));                 /* clearly faster */
        in.bySharpness = by;
        Check c = DeviceReadiness.sharpness(in);
        assertEquals(Level.BETTER, c.level);
        assertEquals(900, c.fasterSharpness);
        assertEquals(30, c.fasterMs);
        assertEquals(40, c.currentMs);

        by.put(900, result(3, 37));                 /* within 15 %: not worth it */
        assertEquals(Level.OK, DeviceReadiness.sharpness(in).level);

        by.put(900, result(1, 20));                 /* one session only: not enough */
        assertEquals(Level.OK, DeviceReadiness.sharpness(in).level);

        by.put(900, result(3, 30));
        by.put(1200, result(1, 40));                /* the current one with too few sessions */
        assertEquals(Level.OK, DeviceReadiness.sharpness(in).level);
    }

    @Test
    public void theSummaryIsTheWorstLevel() {
        DeviceReadiness.Input in = fine();
        in.powerSave = true;
        List<Check> c = DeviceReadiness.checks(in);
        assertEquals(Level.BETTER, DeviceReadiness.overall(c));
        assertEquals(1, DeviceReadiness.toImprove(c));
        in.availMb = 300;
        c = DeviceReadiness.checks(in);
        assertEquals(Level.LAG, DeviceReadiness.overall(c));
        assertEquals(2, DeviceReadiness.toImprove(c));
        in = fine();
        in.pastMs = -1;
        assertEquals("unknown counts as fine", Level.OK, DeviceReadiness.overall(DeviceReadiness.checks(in)));
    }
}
