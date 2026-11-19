package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SaiStartupTest {
    private static final String SAI = "PaintTool SAI Ver.2 (64bit) Alpha.2026.10.19";

    @Test
    public void knowsSaisWindowByItsTitle() {
        assertTrue(SaiStartup.isSaiTitle(SAI));
        assertTrue(SaiStartup.isSaiTitle("PaintTool SAI Ver.2 (64bit) Alpha.2026.10.19 - NewCanvas1"));
        assertFalse(SaiStartup.isSaiTitle("Wine Desktop"));
        assertFalse(SaiStartup.isSaiTitle(""));
        assertFalse(SaiStartup.isSaiTitle(null));
    }

    @Test
    public void staysUntilSaisWindowAppears() {
        SaiStartup s = new SaiStartup(0);
        assertFalse(s.ready(5000));
        s.onWindowMapped("", 5000);              /* an untitled window: not SAI */
        assertFalse(s.ready(20000));
    }

    @Test
    public void staysWhileSaiIsStillDrawing() {
        SaiStartup s = new SaiStartup(0);
        s.onWindowMapped(SAI, 3000);
        for (long t = 3000; t <= 9000; t += 300) {
            s.onContentUpdate(t);
            assertFalse("still drawing at " + t, s.ready(t + 100));
        }
        /* quiet now: lifts 700 ms after the last update */
        assertFalse(s.ready(9000 + SaiStartup.QUIET_MS - 1));
        assertTrue(s.ready(9000 + SaiStartup.QUIET_MS));
    }

    @Test
    public void showsAtLeastABitAfterTheWindowAppears() {
        /* a window that maps and goes quiet at once is not yet laid out */
        SaiStartup s = new SaiStartup(0);
        s.onWindowMapped(SAI, 3000);
        assertFalse(s.ready(3000 + SaiStartup.QUIET_MS));
        assertTrue(s.ready(3000 + SaiStartup.MIN_SHOWN_AFTER_MAP_MS));
    }

    @Test
    public void aStatusBarThatNeverStopsDoesNotKeepItForever() {
        /* THE TRAP: SAI's memory meter may redraw all the time */
        SaiStartup s = new SaiStartup(0);
        s.onWindowMapped(SAI, 3000);
        long t = 3000;
        for (; t < 3000 + SaiStartup.MAX_AFTER_MAP_MS; t += 400) {
            s.onContentUpdate(t);
            assertFalse(s.ready(t));
        }
        s.onContentUpdate(t);
        assertTrue(s.ready(3000 + SaiStartup.MAX_AFTER_MAP_MS));
    }

    @Test
    public void anotherWindowIsShownSoonerThanNever() {
        /* an error or a dialog under another title must not stay hidden */
        SaiStartup s = new SaiStartup(0);
        s.onWindowMapped("Program Error", 4000);
        assertFalse(s.ready(4000 + SaiStartup.MAX_AFTER_OTHER_WINDOW_MS - 1));
        assertTrue(s.ready(4000 + SaiStartup.MAX_AFTER_OTHER_WINDOW_MS));
    }

    @Test
    public void givesUpWhenNothingEverAppears() {
        SaiStartup s = new SaiStartup(1000);
        assertFalse(s.ready(1000 + SaiStartup.MAX_TOTAL_MS - 1));
        assertTrue(s.ready(1000 + SaiStartup.MAX_TOTAL_MS));
    }

    @Test
    public void saysWhatIsHappening() {
        SaiStartup s = new SaiStartup(1000);
        assertEquals(0, s.stage());
        assertEquals(0, s.secondsSince(1999));
        assertEquals(9, s.secondsSince(10250));
        s.onWindowMapped("Program Error", 9000);
        assertEquals("another window is not SAI drawing", 0, s.stage());
        s.onWindowMapped(SAI, 10250);
        assertEquals(1, s.stage());
    }

    @Test
    public void theBarFillsAndNeverGoesBack() {
        SaiStartup s = new SaiStartup(0);
        int last = -1;
        for (long t = 0; t <= 7000; t += 250) {
            int p = s.progress(t);
            assertTrue("went back at " + t, p >= last);
            assertTrue("past 80 before SAI's window at " + t, p < SaiStartup.PROGRESS_BEFORE_WINDOW);
            last = p;
        }
        assertTrue("visibly moving after 3 s", s.progress(3000) >= 30);
        s.onWindowMapped(SAI, 7000);
        for (long t = 7000; t < 7000 + SaiStartup.MIN_SHOWN_AFTER_MAP_MS; t += 100) {
            int p = s.progress(t);
            assertTrue("went back at " + t, p >= last);
            assertTrue(p < 100);
            last = p;
        }
        assertEquals(100, s.progress(7000 + SaiStartup.MIN_SHOWN_AFTER_MAP_MS));
    }

    @Test
    public void theBarKeepsMovingWhileSaiStillDraws() {
        /* THE TRAP: 0.3.x reached 99 % 5 s after SAI's window and stood
         * there while SAI went on drawing: it looked stuck */
        SaiStartup s = new SaiStartup(0);
        s.onWindowMapped(SAI, 7000);
        int last = s.progress(7000);
        for (long t = 8000; t <= 7000 + SaiStartup.MAX_AFTER_MAP_MS - 2000; t += 1000) {
            s.onContentUpdate(t);                       /* still drawing */
            int p = s.progress(t);
            assertTrue("stood still at " + p + " % at " + t, p > last);
            last = p;
        }
    }

    @Test
    public void aSlowStartStillCreepsOnButWaitsForSai() {
        SaiStartup s = new SaiStartup(0);
        assertTrue(s.progress(20000) > s.progress(10000));
        assertTrue(s.progress(60000) < SaiStartup.PROGRESS_BEFORE_WINDOW);
    }

    @Test
    public void updatesBeforeSaisWindowDoNotCount() {
        SaiStartup s = new SaiStartup(0);
        s.onContentUpdate(1000);
        s.onWindowMapped(SAI, 5000);
        assertEquals(5000, s.saiWindowAfterMs());
        assertFalse(s.ready(5000 + SaiStartup.MIN_SHOWN_AFTER_MAP_MS - 1));
        assertTrue(s.ready(5000 + SaiStartup.MIN_SHOWN_AFTER_MAP_MS));
    }

    /** Ticks of SAI's process: `percent` of a core from `from` to `to`, sampled
     *  every 250 ms, on top of `ticks` so far. Returns the ticks at the end. */
    private static long busy(SaiStartup s, long ticks, int percent, long from, long to) {
        for (long t = from; t <= to; t += 250) {
            s.onSaiCpuTicks(ticks + (t - from) * percent / 1000, t);
        }
        return ticks + (to - from) * percent / 1000;
    }

    @Test
    public void theCoverStaysWhileSaiWorksWithItsWindowStill() {
        /* the 900-line start of 2026-11-19: window at 6.4 s, SAI at 55-65 %
         * of a core with nothing drawn until 12.2 s, then calm */
        SaiStartup s = new SaiStartup(0);
        long ticks = busy(s, 0, 120, 0, 6400);
        s.onWindowMapped("PaintTool SAI Ver.2 (64bit)", 6400);
        ticks = busy(s, ticks, 60, 6400, 12200);
        assertFalse("the window is still, SAI is not", s.ready(9400));
        assertFalse(s.ready(12200));
        busy(s, ticks, 8, 12200, 13250);
        assertTrue("a calm second", s.ready(13250));
    }

    @Test
    public void aCalmMomentBeforeSaisWindowDoesNotCount() {
        SaiStartup s = new SaiStartup(0);
        long ticks = busy(s, 0, 5, 0, 3000);       /* Wine waiting on something */
        s.onWindowMapped("PaintTool SAI Ver.2 (64bit)", 3000);
        busy(s, ticks, 70, 3000, 8000);
        assertFalse(s.ready(3000 + SaiStartup.MIN_SHOWN_AFTER_MAP_MS + 500));
    }

    @Test
    public void aSaiThatNeverCalmsIsUncoveredAtTheLimit() {
        SaiStartup s = new SaiStartup(0);
        s.onWindowMapped("PaintTool SAI Ver.2 (64bit)", 1000);
        busy(s, 0, 90, 0, 1000 + SaiStartup.MAX_AFTER_MAP_MS);
        assertFalse(s.ready(1000 + SaiStartup.MAX_AFTER_MAP_MS - 1));
        assertTrue(s.ready(1000 + SaiStartup.MAX_AFTER_MAP_MS));
    }
}
