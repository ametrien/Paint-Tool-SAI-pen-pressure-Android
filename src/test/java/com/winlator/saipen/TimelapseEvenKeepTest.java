package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimelapseEvenKeepTest {
    private static int count(boolean[] b) {
        int n = 0;
        for (boolean x : b) if (x) n++;
        return n;
    }

    @Test
    public void keepsHalfAndTheEnds() {
        long[] t = new long[100];
        for (int i = 0; i < t.length; i++) t[i] = i * 1000L;
        boolean[] k = TimelapsePack.evenKeep(t, 50);
        assertEquals(50, count(k));
        assertTrue(k[0]);
        assertTrue(k[99]);
    }

    /** The furisode case: frames thinned before, then new ones at full
     *  density. Kept by time, the first and the second half of the drawing
     *  end up with about as many frames each. */
    @Test
    public void oldAndNewEndUpEquallyDense() {
        long[] t = new long[150];
        int n = 0;
        for (int i = 0; i < 50; i++) t[n++] = i * 8000L;            // 0..400 s, thinned to one frame in 8 s
        for (int i = 0; i < 100; i++) t[n++] = 400_000L + i * 4000L; // 400..800 s, one frame in 4 s
        boolean[] k = TimelapsePack.evenKeep(t, 75);
        int first = 0, second = 0;
        for (int i = 0; i < t.length; i++) if (k[i]) { if (t[i] < 400_000L) first++; else second++; }
        assertTrue("first half " + first + ", second half " + second, Math.abs(first - second) <= 6);
    }

    @Test
    public void smallCases() {
        assertEquals(3, count(TimelapsePack.evenKeep(new long[] {1, 2, 3}, 5)));
        assertEquals(2, count(TimelapsePack.evenKeep(new long[] {1, 2, 3}, 1)));
        assertEquals(0, TimelapsePack.evenKeep(new long[0], 3).length);
    }
}
