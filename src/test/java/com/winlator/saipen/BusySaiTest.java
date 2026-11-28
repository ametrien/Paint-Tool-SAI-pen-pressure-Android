package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BusySaiTest {
    /** One tick a second from t = 0 at `percent`; the steps seen. */
    private static BusySai.Step[] run(BusySai b, long from, int seconds, int percent) {
        BusySai.Step[] out = new BusySai.Step[seconds];
        for (int i = 0; i < seconds; i++) out[i] = b.tick((from + i) * 1000, percent);
        return out;
    }

    private static int count(BusySai.Step[] steps, BusySai.Step want) {
        int n = 0;
        for (BusySai.Step s : steps) if (s == want) n++;
        return n;
    }

    @Test
    public void whatSaiCanUseIsTheSmallerOfSeenAndAllowed() {
        assertEquals(2, new BusySai(2, 8).cores());     /* Tab S6 Lite: sees 2 */
        assertEquals(2, new BusySai(0, 2).cores());     /* sees all, All of SAI on 2 fast cores */
        assertEquals(8, new BusySai(0, 8).cores());
        assertEquals(3, new BusySai(4, 3).cores());
    }

    @Test
    public void theFreezeOf20261128OpensEveryCoreAfterTenSeconds() {
        BusySai b = new BusySai(2, 2);
        BusySai.Step[] s = run(b, 0, 9, 199);
        assertEquals(0, count(s, BusySai.Step.OPEN_ALL));
        assertEquals(BusySai.Step.NONE, b.tick(9_000, 198));
        assertEquals(BusySai.Step.OPEN_ALL, b.tick(10_000, 197));
        assertTrue(b.isOpen());
        /* only once while it stays full */
        assertEquals(0, count(run(b, 11, 60, 199), BusySai.Step.OPEN_ALL));
        assertTrue(b.fullMs() >= 60_000);
        assertTrue(b.longestFullMs() >= 60_000);
    }

    @Test
    public void busyDrawingIsNotFull() {
        /* the busiest drawing on 2026-11-28 reached 88% to 155% of 200% */
        BusySai b = new BusySai(2, 2);
        int[] drawing = {80, 120, 155, 140, 90, 170, 150, 60, 130, 175, 160, 140, 120};
        for (int i = 0; i < drawing.length * 5; i++)
            assertEquals(BusySai.Step.NONE, b.tick(i * 1000L, drawing[i % drawing.length]));
        assertFalse(b.isOpen());
    }

    @Test
    public void aDipStartsTheTenSecondsAgain() {
        BusySai b = new BusySai(2, 2);
        run(b, 0, 8, 199);
        b.tick(8_000, 120);
        /* full again from 9 s: ten seconds later, at 19 s */
        assertEquals(0, count(run(b, 9, 10, 199), BusySai.Step.OPEN_ALL));
        assertEquals(BusySai.Step.OPEN_ALL, b.tick(19_000, 199));
    }

    @Test
    public void backAfterThirtyCalmSeconds() {
        BusySai b = new BusySai(2, 2);
        run(b, 0, 11, 199);
        assertTrue(b.isOpen());
        /* busy but not full: stays open */
        assertEquals(0, count(run(b, 11, 60, 150), BusySai.Step.BACK));
        BusySai.Step[] calm = run(b, 71, 31, 40);
        assertEquals(1, count(calm, BusySai.Step.BACK));
        assertEquals(BusySai.Step.BACK, calm[30]);
        assertFalse(b.isOpen());
        /* and full again opens again */
        assertEquals(1, count(run(b, 102, 12, 199), BusySai.Step.OPEN_ALL));
    }

    @Test
    public void unknownUseCountsAsNothing() {
        BusySai b = new BusySai(2, 2);
        run(b, 0, 5, 199);
        b.tick(5_000, -1);
        assertEquals(0, count(run(b, 6, 10, 199), BusySai.Step.OPEN_ALL));
        assertEquals(BusySai.Step.OPEN_ALL, b.tick(16_000, 199));
    }

    @Test
    public void aTestsLowerShareOpensOnOrdinaryDrawingAndComesBack() {
        BusySai b = new BusySai(2, 2, 0.2);                 /* full from 40%, calm under 20% */
        assertEquals(1, count(run(b, 0, 11, 60), BusySai.Step.OPEN_ALL));
        assertEquals(0, count(run(b, 11, 40, 30), BusySai.Step.BACK));
        assertEquals(1, count(run(b, 51, 31, 5), BusySai.Step.BACK));
        /* nonsense falls back to the normal share */
        assertEquals(0, count(run(new BusySai(2, 2, 7), 0, 20, 150), BusySai.Step.OPEN_ALL));
    }

    @Test
    public void stillFullThirtySecondsAfterOpeningSaysSaveAndClose() {
        BusySai b = new BusySai(2, 2);
        run(b, 0, 11, 199);                                  /* opened at 10 s */
        BusySai.Step[] s = run(b, 11, 30, 199);              /* 11 to 40 s */
        assertEquals(1, count(s, BusySai.Step.STILL_FULL));
        assertEquals(BusySai.Step.STILL_FULL, s[29]);        /* at 40 s */
        assertEquals(0, count(run(b, 41, 60, 199), BusySai.Step.STILL_FULL));
    }

    @Test
    public void notStillFullWhenItEasedAfterOpening() {
        BusySai b = new BusySai(2, 2);
        run(b, 0, 11, 199);
        run(b, 11, 10, 150);                                  /* better on every core */
        assertEquals(0, count(run(b, 21, 25, 199), BusySai.Step.STILL_FULL));
        assertEquals(BusySai.Step.STILL_FULL, b.tick(51_000, 199));   /* 30 s full again, from 21 s */
    }

    @Test
    public void theLimitIsNinetyPercentOfWhatSaiCanUse() {
        assertEquals(180, BusySai.limitPercent(2));
        assertEquals(720, BusySai.limitPercent(8));
    }
}
