package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MultiTapTest {
    @Test
    public void withoutADoubleTapEveryTapIsSingleAtOnce() {
        MultiTap t = new MultiTap();
        assertEquals(MultiTap.SINGLE, t.tap(2, false, 0, 0, 0));
        assertEquals(MultiTap.SINGLE, t.tap(2, false, 0, 0, 100));
        assertEquals(-1, t.due());
    }

    @Test
    public void twoQuickTapsAreADoubleTap() {
        MultiTap t = new MultiTap();
        assertEquals(MultiTap.WAIT, t.tap(2, true, 10, 20, 1000));
        assertEquals(1000 + MultiTap.DOUBLE_MS, t.due());
        assertEquals(MultiTap.DOUBLE, t.tap(2, true, 12, 22, 1250));
        assertEquals(-1, t.due());
        assertEquals(0, t.expire(5000));
    }

    @Test
    public void aLoneTapIsSingleOnceTheWaitIsOver() {
        MultiTap t = new MultiTap();
        t.tap(3, true, 10, 20, 1000);
        assertEquals(0, t.expire(1000 + MultiTap.DOUBLE_MS - 1));
        assertEquals(3, t.expire(1000 + MultiTap.DOUBLE_MS));
        assertEquals(10, t.x());
        assertEquals(20, t.y());
        assertEquals(0, t.expire(9999));
    }

    @Test
    public void aSlowSecondTapWaitsAfresh() {
        MultiTap t = new MultiTap();
        t.tap(2, true, 0, 0, 1000);
        assertEquals(MultiTap.WAIT, t.tap(2, true, 0, 0, 1000 + MultiTap.DOUBLE_MS + 1));
    }

    @Test
    public void anotherNumberOfFingersLetsTheWaitingTapGo() {
        MultiTap t = new MultiTap();
        t.tap(2, true, 5, 6, 1000);
        assertEquals(0, t.flushOther(2));
        assertEquals(2, t.flushOther(3));
        assertEquals(-1, t.due());
        assertEquals(MultiTap.SINGLE, t.tap(3, false, 0, 0, 1100));
    }
}
