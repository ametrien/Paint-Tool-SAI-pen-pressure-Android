package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ModifierKeysTest {
    private static final int CTRL = 0, ALT = 1, SHIFT = 2;
    private final List<String> log = new ArrayList<>();
    private final ModifierKeys keys = new ModifierKeys(3, new ModifierKeys.Sink() {
        @Override public void press(int key) { log.add("down " + key); }
        @Override public void release(int key) { log.add("up " + key); }
    });

    @Test
    public void aTapLatchesTheKeyUntilTheNextTap() {
        keys.down(CTRL, 0);
        keys.up(CTRL, 120);
        assertEquals("[down 0]", log.toString());
        assertTrue(keys.isLatched(CTRL));
        assertTrue(keys.isDown(CTRL));

        keys.down(CTRL, 5000);
        assertEquals("still down while the finger is on it", "[down 0]", log.toString());
        keys.up(CTRL, 5100);
        assertEquals("[down 0, up 0]", log.toString());
        assertFalse(keys.isDown(CTRL));
    }

    @Test
    public void aHoldIsAnOrdinaryKeyPress() {
        keys.down(ALT, 0);
        keys.up(ALT, ModifierKeys.HOLD_MS);
        assertEquals("[down 1, up 1]", log.toString());
        assertFalse(keys.isLatched(ALT));
        assertFalse(keys.anyDown());
    }

    @Test
    public void theKeyGoesDownAtOnceEvenForAHold() {
        keys.down(SHIFT, 0);
        assertEquals("[down 2]", log.toString());
        assertTrue(keys.isDown(SHIFT));
        assertFalse("not latched while the finger is still on it", keys.isLatched(SHIFT));
    }

    @Test
    public void keysAreIndependent() {
        keys.down(CTRL, 0);
        keys.up(CTRL, 50);
        keys.down(ALT, 100);
        keys.up(ALT, 150);
        assertTrue(keys.isLatched(CTRL));
        assertTrue(keys.isLatched(ALT));
        assertFalse(keys.isDown(SHIFT));
        assertEquals("[down 0, down 1]", log.toString());
    }

    @Test
    public void releaseAllLetsEveryKeyGo() {
        keys.down(CTRL, 0);
        keys.up(CTRL, 50);           /* latched */
        keys.down(SHIFT, 100);       /* held */
        keys.releaseAll();
        assertEquals("[down 0, down 2, up 0, up 2]", log.toString());
        assertFalse(keys.anyDown());
        keys.releaseAll();
        assertEquals("nothing twice", 4, log.size());
    }

    @Test
    public void aLiftWithoutADownDoesNothing() {
        keys.up(CTRL, 10);
        assertTrue(log.isEmpty());
    }
}
