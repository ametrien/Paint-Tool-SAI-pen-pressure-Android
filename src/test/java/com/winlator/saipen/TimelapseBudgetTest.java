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
    }

    @Test
    public void autoNeverBelowTwoGigabytesNorAboveTwenty() {
        assertEquals(2048, TimelapseBudget.megabytes(GB));
        assertEquals(2048, TimelapseBudget.megabytes(-1));
        assertEquals(20480, TimelapseBudget.megabytes(200 * GB));
    }

    @Test
    public void aChosenSizeIsCutToAQuarterOfTheFreeSpace() {
        assertEquals(3 * 1024, TimelapseBudget.megabytes(15 * GB, 10));     // 15 GB free: 3 GB at most
        assertEquals(2 * 1024, TimelapseBudget.megabytes(15 * GB, 2));
        assertEquals(20 * 1024, TimelapseBudget.megabytes(200 * GB, 99));  // never past 20
        assertEquals(TimelapseBudget.AUTO, TimelapseBudget.choice(1));
    }

    @Test
    public void theSlidersRightEnd() {
        assertEquals(3, TimelapseBudget.maxGb(15 * GB));
        assertEquals(2, TimelapseBudget.maxGb(GB));
        assertEquals(20, TimelapseBudget.maxGb(500 * GB));
    }

    @Test
    public void aNoteOnlyWhenFreeSpaceIsReallyLow() {
        assertFalse(TimelapseBudget.tooLittleSpace(15 * GB));
        assertTrue(TimelapseBudget.tooLittleSpace(7 * GB));
    }
}
