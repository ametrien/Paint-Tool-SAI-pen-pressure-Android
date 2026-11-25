package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LongPressTest {
    @Test
    public void aStillFingerIsALongPressOnce() {
        LongPress p = new LongPress(10);
        p.down(100, 100, 0);
        assertEquals(LongPress.HOLD_MS, p.when());
        p.move(104, 103);
        assertFalse(p.due(LongPress.HOLD_MS - 1));
        assertTrue(p.due(LongPress.HOLD_MS));
        assertFalse(p.due(LongPress.HOLD_MS + 100));
        assertEquals(-1, p.when());
    }

    @Test
    public void aDragIsNot() {
        LongPress p = new LongPress(10);
        p.down(100, 100, 0);
        p.move(120, 100);
        assertFalse(p.due(LongPress.HOLD_MS));
    }

    @Test
    public void anotherFingerOrALiftIsNot() {
        LongPress p = new LongPress(10);
        p.down(100, 100, 0);
        p.cancel();
        assertFalse(p.due(LongPress.HOLD_MS));
        assertEquals(-1, p.when());
    }
}
