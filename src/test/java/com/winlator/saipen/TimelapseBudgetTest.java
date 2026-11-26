package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimelapseBudgetTest {
    private static final long GB = 1024L * 1024 * 1024;

    @Test
    public void aQuarterOfTheFreeSpace() {
        assertEquals(3875, TimelapseBudget.megabytes(15_500L * 1024 * 1024));   // the owner's 15.5 GB
    }

    @Test
    public void neverBelowTwoGigabytesNorAboveTwenty() {
        assertEquals(2048, TimelapseBudget.megabytes(GB));
        assertEquals(2048, TimelapseBudget.megabytes(-1));
        assertEquals(20480, TimelapseBudget.megabytes(200 * GB));
    }
}
