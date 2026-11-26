package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FloatingKeysColumnsTest {
    @Test
    public void oneRowWhenTheyFit() {
        assertEquals(7, Shortcut.columns(7, 9));
        assertEquals(7, Shortcut.columns(7, 7));
        assertEquals(2, Shortcut.columns(2, 9));
    }

    @Test
    public void evenRowsOnANarrowScreen() {
        assertEquals("4 and 3", 4, Shortcut.columns(7, 5));
        assertEquals("three rows: 3, 3 and 1", 3, Shortcut.columns(7, 3));
        assertEquals("never fewer than 3 a row", 3, Shortcut.columns(6, 1));
        assertEquals("ten on a tablet that fits 8: two rows of 5", 5, Shortcut.columns(10, 8));
        assertEquals("ten where 4 fit: 4, 3 and 3", 4, Shortcut.columns(10, 4));
    }
}
