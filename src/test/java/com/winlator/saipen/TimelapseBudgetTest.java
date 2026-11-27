package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimelapseBudgetTest {
    private static final long GB = 1024L * 1024 * 1024;

    @Test
    public void autoIsAQuarterOfTheFreeSpace() {
        assertEquals(3875, TimelapseBudget.megabytes(15_500L * 1024 * 1024));   // the owner's 15.5 GB
        assertEquals(3875, TimelapseBudget.megabytes(15_500L * 1024 * 1024, TimelapseBudget.AUTO));
        assertEquals(2048, TimelapseBudget.megabytes(GB));
        assertEquals(2048, TimelapseBudget.megabytes(-1));
        assertEquals(20480, TimelapseBudget.megabytes(200 * GB));
    }

    @Test
    public void aChoiceIsCutToTodaysQuarter() {
        assertEquals(3 * 1024, TimelapseBudget.megabytes(12 * GB, 10 * 1024));
        assertEquals(2560, TimelapseBudget.megabytes(15 * GB, 2560));          // a half-GB size stays
        assertEquals(20 * 1024, TimelapseBudget.megabytes(500 * GB, 99 * 1024));
    }

    @Test
    public void autoIsTheRightEndAndEveryHalfGbBeforeIt() {
        long free = 15_500L * 1024 * 1024;                  // most 3875 MB
        assertEquals(4, TimelapseBudget.lastStep(free));    // 2, 2.5, 3, 3.5 GB, then Auto
        assertEquals(2048, TimelapseBudget.mbAt(0, free));
        assertEquals(3584, TimelapseBudget.mbAt(3, free));
        assertEquals(TimelapseBudget.AUTO, TimelapseBudget.mbAt(4, free));
        assertEquals(4, TimelapseBudget.stepOf(TimelapseBudget.AUTO, free));
        assertEquals(1, TimelapseBudget.stepOf(2560, free));
        assertEquals(4, TimelapseBudget.stepOf(12 * 1024, free));   // more than fits: on Auto
        for (int st = 0; st < 4; st++)                               // sizes grow left to right
            assertTrue(TimelapseBudget.megabytes(free, TimelapseBudget.mbAt(st, free))
                       < TimelapseBudget.megabytes(free, TimelapseBudget.mbAt(st + 1, free)));
    }

    @Test
    public void littleSpaceIsOnlyAuto() {
        assertEquals(0, TimelapseBudget.lastStep(GB));
        assertEquals(TimelapseBudget.AUTO, TimelapseBudget.mbAt(0, GB));
    }

    @Test
    public void oldWholeGbChoicesCarryOver() {
        assertEquals(TimelapseBudget.AUTO, TimelapseBudget.fromGb(0));
        assertEquals(TimelapseBudget.AUTO, TimelapseBudget.fromGb(1));
        assertEquals(12 * 1024, TimelapseBudget.fromGb(12));
        assertEquals(20 * 1024, TimelapseBudget.fromGb(99));
    }

    @Test
    public void littleFreeSpaceIsNoted() {
        assertFalse(TimelapseBudget.tooLittleSpace(15 * GB));
        assertTrue(TimelapseBudget.tooLittleSpace(7 * GB));
    }
}
