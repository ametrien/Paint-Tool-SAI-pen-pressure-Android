package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirtyRectTest {
    private static int[] take(DirtyRect d) {
        int[] out = new int[4];
        assertTrue(d.take(out));
        return out;
    }

    @Test public void nothingChanged() {
        assertFalse(new DirtyRect(100, 50).take(new int[4]));
    }

    @Test public void oneChange() {
        DirtyRect d = new DirtyRect(100, 50);
        d.add(10, 20, 30, 5);
        assertArrayEquals(new int[]{10, 20, 30, 5}, take(d));
        assertFalse("taking starts a new box", d.take(new int[4]));
    }

    @Test public void twoChangesMakeOneBox() {
        DirtyRect d = new DirtyRect(100, 50);
        d.add(10, 20, 5, 5);
        d.add(50, 2, 10, 3);
        assertArrayEquals(new int[]{10, 2, 50, 23}, take(d));
    }

    @Test public void clippedToTheWindow() {
        DirtyRect d = new DirtyRect(100, 50);
        d.add(-5, 40, 20, 30);
        assertArrayEquals(new int[]{0, 40, 15, 10}, take(d));
    }

    @Test public void outsideOrEmptyIsNoChange() {
        DirtyRect d = new DirtyRect(100, 50);
        d.add(100, 0, 10, 10);
        d.add(0, 50, 10, 10);
        d.add(5, 5, 0, 10);
        d.add(5, 5, -3, 10);
        assertFalse(d.take(new int[4]));
    }

    @Test public void all() {
        DirtyRect d = new DirtyRect(100, 50);
        d.add(1, 1, 1, 1);
        d.addAll();
        d.add(1, 1, 1, 1);
        assertArrayEquals(new int[]{0, 0, 100, 50}, take(d));
    }
}
