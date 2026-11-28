package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CpuMeterTest {
    @Test
    public void labelSaysHowMuchOfWhatSaiCanUse() {
        assertEquals("SAI 70% of 200%", CpuMeter.label(70, 2, false));
        assertEquals("SAI 199% of 200% · every core", CpuMeter.label(199, 2, true));
        assertEquals("SAI ?", CpuMeter.label(-1, 2, false));
    }

    @Test
    public void greenThenYellowThenRedAtFull() {
        assertEquals(0, CpuMeter.level(110, 2));    /* busy drawing */
        assertEquals(1, CpuMeter.level(120, 2));    /* 60% of 200% */
        assertEquals(1, CpuMeter.level(179, 2));
        assertEquals(2, CpuMeter.level(180, 2));    /* BusySai's full */
        assertEquals(0, CpuMeter.level(400, 8));
    }

    @Test
    public void theBarStopsAtTheEnd() {
        assertEquals(0.5f, CpuMeter.fill(100, 2), 1e-6);
        assertEquals(1f, CpuMeter.fill(250, 2), 1e-6);
        assertEquals(0f, CpuMeter.fill(-1, 2), 1e-6);
    }
}
