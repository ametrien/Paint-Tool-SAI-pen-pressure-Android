package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SpeedHistoryTest {
    /** A speed log at this sharpness with `strokes` strokes of `ms` each after the first ten. */
    private static String log(int height, int strokes, int ms) {
        StringBuilder b = new StringBuilder("SAI Pen 0.4.29 speed log\nSAI's screen 2000x" + height + ", display 60 Hz\n");
        for (int i = 1; i <= 10 + strokes; i++) {
            int t = i <= 10 ? 500 : ms;              /* the first ten are slow and must not count */
            b.append("  1.0s  stroke ").append(i).append("  100 ms  ink 10 ms  to screen ").append(t)
             .append(" ms (input 3, SAI 10, frame 4)  draws/s 60\n");
        }
        return b.toString();
    }

    @Test
    public void perSharpnessSkippingTheFirstStrokes() {
        SpeedHistory h = SpeedHistory.fromTexts(Arrays.asList(log(1200, 20, 40), log(900, 20, 30), log(1200, 20, 44)));
        assertEquals(2, h.bySharpness.get(1200).sessions);
        assertEquals(42, h.bySharpness.get(1200).median());
        assertEquals(1, h.bySharpness.get(900).sessions);
        assertEquals(30, h.bySharpness.get(900).median());
        assertNull(h.bySharpness.get(720));
    }

    @Test
    public void recentIsTheLastSessionsOnly() {
        List<String> logs = new ArrayList<>();
        for (int i = 0; i < 4; i++) logs.add(log(1200, 20, 90));        /* old and slow */
        for (int i = 0; i < SpeedHistory.RECENT; i++) logs.add(log(1200, 20, 30));
        assertEquals(30, SpeedHistory.fromTexts(logs).recentMs);
    }

    @Test
    public void tooFewStrokesSayNothing() {
        assertEquals(-1, SpeedHistory.fromTexts(Arrays.asList(log(1200, 5, 30))).recentMs);
        assertEquals(-1, SpeedHistory.fromTexts(new ArrayList<>()).recentMs);
        assertEquals("a log with only the first strokes", 0,
            SpeedHistory.fromTexts(Arrays.asList(log(1200, 0, 30))).bySharpness.size());
    }
}
