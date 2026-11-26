package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DebugEnvTest {
    @Test
    public void addsWhatThePropertySays() {
        String[] r = DebugEnv.apply("WT_TIMELAPSE=1", "BOX64_DYNACACHE=1 BOX64_X=2", "");
        assertTrue(r[0].contains("BOX64_DYNACACHE=1"));
        assertTrue(r[0].contains("BOX64_X=2"));
        assertTrue(r[0].contains("WT_TIMELAPSE=1"));
        assertEquals("BOX64_DYNACACHE,BOX64_X", r[1]);
    }

    @Test
    public void anEmptyPropertyTakesLastTimesBackOut() {
        String[] r = DebugEnv.apply("WT_TIMELAPSE=1 BOX64_DYNACACHE=1", "", "BOX64_DYNACACHE");
        assertFalse(r[0].contains("BOX64_DYNACACHE"));
        assertTrue(r[0].contains("WT_TIMELAPSE=1"));
        assertEquals("", r[1]);
    }

    @Test
    public void nonsenseIsSkipped() {
        String[] r = DebugEnv.apply("", "=1 1A=2 noequals A-B=3", null);
        assertEquals("", r[1]);
    }
}
