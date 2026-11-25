package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.saipen.PowerWatch.Change;

import org.junit.Test;

public class PowerWatchTest {
    @Test public void batterySaver() {
        assertEquals(Change.SAVER_ON, PowerWatch.change(false, 0, true, 0));
        assertEquals(Change.SAVER_OFF, PowerWatch.change(true, 0, false, 0));
        assertEquals(Change.NONE, PowerWatch.change(true, 0, true, 0));
    }

    @Test public void heatByDeviceReadinessLevels() {
        assertEquals("light is still fine", Change.NONE, PowerWatch.change(false, 0, false, 1));
        assertEquals(Change.WARM, PowerWatch.change(false, 1, false, 2));
        assertEquals(Change.HOT, PowerWatch.change(false, 2, false, 3));
        assertEquals(Change.HOT, PowerWatch.change(false, 0, false, 4));
        assertEquals("severe to critical: still hot", Change.NONE, PowerWatch.change(false, 3, false, 4));
        assertEquals(Change.COOLED, PowerWatch.change(false, 3, false, 2));
        assertEquals(Change.COOLED, PowerWatch.change(false, 2, false, 0));
        assertEquals("unknown is fine", Change.NONE, PowerWatch.change(false, -1, false, 0));
    }

    @Test public void onlySlowerIsShown() {
        assertTrue(PowerWatch.worthAMessage(Change.SAVER_ON));
        assertTrue(PowerWatch.worthAMessage(Change.HOT));
        assertFalse(PowerWatch.worthAMessage(Change.WARM));
        assertFalse(PowerWatch.worthAMessage(Change.SAVER_OFF));
        assertFalse(PowerWatch.worthAMessage(Change.COOLED));
    }

    @Test public void words() {
        assertEquals("battery saver on, heat ? (0 none, 1 light, 2 moderate, 3 severe)", PowerWatch.words(true, -1));
    }
}
