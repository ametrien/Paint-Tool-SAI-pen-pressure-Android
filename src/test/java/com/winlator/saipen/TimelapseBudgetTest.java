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
    public void aChosenSizeIsUsedAsItIs() {
        assertEquals(10 * 1024, TimelapseBudget.megabytes(15 * GB, 10));
        assertEquals(20 * 1024, TimelapseBudget.megabytes(15 * GB, 99));   // capped at 20
        assertEquals(TimelapseBudget.AUTO, TimelapseBudget.choice(1));
    }

    @Test
    public void tooLittleSpaceWhenTheRoomIsMoreThanIsFree() {
        assertFalse(TimelapseBudget.tooLittleSpace(15 * GB, TimelapseBudget.AUTO));
        assertFalse(TimelapseBudget.tooLittleSpace(15 * GB, 10));
        assertTrue(TimelapseBudget.tooLittleSpace(15 * GB, 20));
        assertTrue(TimelapseBudget.tooLittleSpace(GB, TimelapseBudget.AUTO));      // under 2 GB free
    }
}
