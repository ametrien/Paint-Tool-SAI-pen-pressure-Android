package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SaiCalmTest {
    private static int feed(SaiCalm c, int... readings) {
        long t = 0;
        for (int i = 0; i < readings.length; i++, t += 1000) {
            if (c.onReading(readings[i], t)) return i;
        }
        return -1;
    }

    @Test
    public void theTabletsStart() {
        /* 1200 lines, 2026-11-19: SAI per second after its window appeared */
        SaiCalm c = new SaiCalm();
        assertEquals("settles at the 9 %", 7, feed(c, 64, 62, 65, 59, 54, 57, 38, 9, 10, 15));
    }

    @Test
    public void aFastDeviceLoadingAt20PercentIsNotSettledYet() {
        /* THE TRAP of a fixed line: under 25 % but still loading */
        SaiCalm c = new SaiCalm();
        assertEquals(5, feed(c, 20, 22, 19, 21, 20, 6, 5));
    }

    @Test
    public void aSlowDeviceIdlingAbove25PercentDoesSettle() {
        SaiCalm c = new SaiCalm();
        assertEquals(4, feed(c, 150, 140, 160, 145, 40, 42));
    }

    @Test
    public void aGentleEaseIsNotTheStep() {
        /* 60 to 30 is half, not the sharp step: wait (the cover's time
         * limit ends it if nothing better comes) */
        SaiCalm c = new SaiCalm();
        assertEquals(-1, feed(c, 60, 60, 60, 30, 30, 30, 30));
        assertFalse(c.settled());
    }

    @Test
    public void oneSpikeDoesNotRaiseTheLine() {
        /* with the peak (200) as the level, 60 would pass for calm */
        SaiCalm c = new SaiCalm();
        assertEquals(-1, feed(c, 60, 200, 62, 58, 61));
        assertEquals(61, c.loadingLevel());
    }

    @Test
    public void almostNothingIsCalmAnywhere() {
        SaiCalm c = new SaiCalm();
        assertEquals("SAI was idle when its window came", 0, feed(c, 3));
        assertFalse("once", c.onReading(1, 5000));
        assertEquals(0, c.settledAt());
    }

    @Test
    public void aDropNeedsALevelToDropFrom() {
        SaiCalm c = new SaiCalm();
        assertEquals("two readings are not a level yet", -1, feed(c, 60, 60, 15));
    }
}
