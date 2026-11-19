package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EnvVarsEditTest {
    private static final String WINLATOR = "ZINK_DESCRIPTORS=lazy WINEESYNC=1 TU_DEBUG=noconform";

    @Test
    public void addsAtTheEnd() {
        assertEquals(WINLATOR + " WT_TIMELAPSE=1", EnvVarsEdit.set(WINLATOR, "WT_TIMELAPSE", "1"));
        assertEquals("WT_TIMELAPSE=1", EnvVarsEdit.set("", "WT_TIMELAPSE", "1"));
        assertEquals("WT_TIMELAPSE=1", EnvVarsEdit.set(null, "WT_TIMELAPSE", "1"));
    }

    @Test
    public void replacesInPlaceAndOnlyOnce() {
        assertEquals("A=1 WT_TIMELAPSE_BUDGET_MB=512 B=2",
                     EnvVarsEdit.set("A=1 WT_TIMELAPSE_BUDGET_MB=2048 B=2 WT_TIMELAPSE_BUDGET_MB=9",
                                     "WT_TIMELAPSE_BUDGET_MB", "512"));
    }

    @Test
    public void removesWithoutTouchingLookAlikes() {
        /* WT_TIMELAPSE_BUDGET_MB is not WT_TIMELAPSE */
        assertEquals("A=1 WT_TIMELAPSE_BUDGET_MB=512",
                     EnvVarsEdit.set("A=1 WT_TIMELAPSE=1 WT_TIMELAPSE_BUDGET_MB=512", "WT_TIMELAPSE", null));
        assertEquals(WINLATOR, EnvVarsEdit.set(WINLATOR, "WT_TIMELAPSE", null));
    }
}
