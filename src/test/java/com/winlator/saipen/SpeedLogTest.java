package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SpeedLogTest {
    private final List<String> lines = new ArrayList<>();

    private SpeedLog log() {
        return new SpeedLog(1000, lines::add);
    }

    private String find(String part) {
        for (String l : lines) if (l.contains(part)) return l;
        return null;
    }

    @Test
    public void aNoteIsALineAtItsTime() {
        SpeedLog s = log();
        s.note(3500, "keyboard \"K\": typing keyboard, sources 0x4103");
        assertEquals("   2.5s  keyboard \"K\": typing keyboard, sources 0x4103", lines.get(0));
    }

    @Test
    public void inkIsPenDownToSaisFirstDrawing() {
        SpeedLog s = log();
        s.onContentUpdate(1500);                /* before the pen: not the stroke */
        s.onPenDown(2000);
        s.onContentUpdate(2085);
        s.onContentUpdate(2120);
        s.onPenUp(2400);
        s.onPenDown(3000);                      /* the next stroke ends the first */
        String l = find("stroke 1");
        assertTrue(l, l.contains("400 ms  ink 85 ms"));
    }

    @Test
    public void drawsPerSecondCountOnlyWhileThePenIsDown() {
        SpeedLog s = log();
        s.onPenDown(2000);
        for (long t = 2010; t < 2500; t += 20) s.onContentUpdate(t);   /* 25 draws in 500 ms */
        s.onPenUp(2500);
        s.onContentUpdate(2560);                /* after: the tail, not the rate */
        s.tick(3100, 10, 50);
        String l = find("stroke 1");
        assertTrue(l, l.contains("draws/s 50"));
        assertTrue(l, l.contains("tail 60 ms"));
    }

    @Test
    public void drawingLongAfterThePenIsNotItsTail() {
        /* THE TRAP: the status bar redraws now and then */
        SpeedLog s = log();
        s.onPenDown(2000);
        s.onContentUpdate(2050);
        s.onPenUp(2300);
        s.onContentUpdate(2300 + SpeedLog.TAIL_MS + 100);
        s.tick(2300 + SpeedLog.TAIL_MS + 200, 10, 50);
        assertTrue(find("stroke 1").contains("tail 0 ms"));
    }

    @Test
    public void aStrokeSaiNeverDrewSaysSo() {
        SpeedLog s = log();
        s.onPenDown(2000);
        s.onPenUp(2100);
        s.finish(5000);
        assertTrue(find("stroke 1").contains("ink none"));
        assertTrue(find("ink, median").contains("?"));
    }

    @Test
    public void saiSettlesWhenItsProcessorUseStaysLow() {
        SpeedLog s = log();
        s.tick(2000, 5, 5);                     /* before the window: Wine starting, not counted */
        s.onSaiWindow(8000);
        s.tick(9000, 180, 220);
        s.tick(10000, 20, 60);                  /* no loading level yet: not settled */
        s.tick(11000, 90, 120);
        s.tick(12000, 10, 40);
        s.tick(13000, 12, 40);
        String l = find("SAI settled");
        assertTrue(l, l.startsWith("  11.0s"));  /* 10 % after loading at 90 %: 12 s, 11 s after the start */
        s.finish(20000);
        assertEquals("  SAI settled:    11.0 s after the start", find("SAI settled:"));
    }

    @Test
    public void theSummaryHasMediansAndTheSlowest() {
        SpeedLog s = log();
        long t = 2000;
        for (long ink : new long[] {50, 60, 70, 80, 400}) {
            s.onPenDown(t);
            s.onContentUpdate(t + ink);
            s.onPenUp(t + 450);
            t += 1000;
        }
        s.finish(t);
        assertEquals("  ink, median:    70 ms   slowest 10%: 400 ms", find("ink, median"));
        assertTrue(find("strokes:").endsWith("5"));
    }

    @Test
    public void touchToScreenRunsFromTheTabletsTouchTimeToTheFrameWithSaisDrawing() {
        SpeedLog s = log();
        s.onPenDown(2010, 2000);                /* the tablet felt the pen at 2000, the app heard at 2010 */
        s.onContentUpdate(2040);                /* SAI drew: ink 30 */
        s.onFrame(2038, 2046);                  /* began before SAI drew: not in this frame */
        s.onFrame(2047, 2052);                  /* this frame holds it */
        s.onPenUp(2300);
        s.onPenDown(3000, 2995);
        String l = find("stroke 1");
        assertTrue(l, l.contains("ink 30 ms  to screen 52 ms (input 10, SAI 30, frame 12)"));
    }

    @Test
    public void aStrokeWithNoFrameAfterSaisDrawingHasNoScreenTime() {
        SpeedLog s = log();
        s.onPenDown(2010, 2000);
        s.onContentUpdate(2040);
        s.onFrame(2030, 2045);                  /* began before SAI drew */
        s.onPenUp(2300);
        s.finish(5000);
        assertTrue(find("stroke 1"), find("stroke 1").contains("to screen ?"));
        assertEquals("  touch to screen, median: ?   slowest 10%: ?", find("touch to screen, median"));
    }

    @Test
    public void inputCountsEveryPenSampleWhileTouching() {
        SpeedLog s = log();
        s.onPenDown(2010, 2000);                /* 10 */
        s.onPenMove(2030, 2026);                /* 4 */
        s.onPenMove(2050, 2044);                /* 6 */
        s.onPenMove(2060, 2070);                /* a time from the future is no sample */
        s.onPenUp(2300);
        s.onPenMove(2500, 2400);                /* hovering: not counted (would be 100) */
        s.finish(5000);
        assertEquals("  input, median:  6 ms   slowest 10%: 10 ms", find("input, median"));
    }

    @Test
    public void framesWhileThePenIsDown() {
        SpeedLog s = log();
        s.onPenDown(2000, 2000);
        for (long t = 2000; t < 3000; t += 40) {       /* 25 frames in the second the pen is down */
            s.onContentUpdate(t + 5);
            s.onFrame(t + 10, t + 15);                 /* 5 ms to draw, SAI's drawing 10 ms old */
        }
        s.onPenUp(3000);
        s.onContentUpdate(3200);
        s.onFrame(3400, 3480);                         /* pen up: neither counted */
        s.finish(6000);
        assertTrue(find("stroke 1"), find("stroke 1").contains("frames/s 25"));
        assertEquals("  SAI to frame, median: 10 ms   slowest 10%: 10 ms", find("SAI to frame, median"));
        assertEquals("  frame drawing, median: 5 ms   slowest 10%: 5 ms", find("frame drawing, median"));
        assertEquals("  frames/s, median: 25", find("frames/s, median"));
    }

    @Test
    public void aScreenThatStallsIsCountedInFull() {
        /* SAI drew 5000 times and no frame came: the screen lagged 5 s, and
         * the list of undrawn drawings must neither grow without end nor
         * forget the oldest one */
        SpeedLog s = log();
        s.onPenDown(2000, 2000);
        for (long t = 2000; t < 7000; t++) s.onContentUpdate(t);
        assertTrue(String.valueOf(s.undrawnCount()), s.undrawnCount() <= SpeedLog.MAX_UNDRAWN);
        s.onFrame(7000, 7005);
        s.onPenUp(7100);
        s.finish(9000);
        assertEquals("  SAI to frame, median: 5005 ms   slowest 10%: 5005 ms", find("SAI to frame, median"));
    }

    @Test
    public void percentiles() {
        List<Long> v = Arrays.asList(5L, 1L, 4L, 2L, 3L);
        assertEquals(3, SpeedLog.median(v));
        assertEquals(5, SpeedLog.percentile(v, 90));
        assertEquals(-1, SpeedLog.median(new ArrayList<>()));
    }

    @Test
    public void processorTimeFromProcStat() {
        /* laid out like /proc/<pid>/stat on Linux: utime 1234, stime 56 */
        String stat = "4321 (sai2.exe) S 4000 4000 0 0 -1 4194560 120000 0 3 0 1234 56 0 0 20 0 30 0 99999 "
                      + "6000000000 50000 18446744073709551615";
        assertEquals(1290, SpeedLog.cpuTicks(stat));
        /* a name with a space and a bracket in it */
        assertEquals(1290, SpeedLog.cpuTicks(stat.replace("(sai2.exe)", "(Wine (x) Desktop)")));
        assertEquals(-1, SpeedLog.cpuTicks("garbage"));
    }
}
