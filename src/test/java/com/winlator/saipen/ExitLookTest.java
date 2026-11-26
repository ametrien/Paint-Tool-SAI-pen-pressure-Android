package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExitLookTest {
    @Test
    public void untouchedItIsSaisRedAtTheKeysOpacity() {
        assertEquals(0xD8A00040, ExitLook.color(0xA00040, 0, ExitLook.opacity(ExitLook.SAME_AS_KEYS, 85)));
    }

    @Test
    public void darkerGoesTowardsBlackButNeverAllTheWay() {
        assertEquals(0xFF500020, ExitLook.color(0xA00040, 50, 100));
        assertEquals(0xFF100006, ExitLook.color(0xA00040, 100, 100));   // clamped to 90 %
    }

    @Test
    public void itsOwnOpacityStaysInRange() {
        assertEquals(55, ExitLook.opacity(55, 85));
        assertEquals(20, ExitLook.opacity(5, 85));
        assertEquals(100, ExitLook.opacity(300, 85));
        assertEquals(85, ExitLook.opacity(ExitLook.SAME_AS_KEYS, 85));
    }
}
