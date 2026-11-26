package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeysOpacityTest {
    @Test
    public void unknownSavedValuesReadAsTheDefault() {
        assertEquals(85, KeysOpacity.of(85));
        assertEquals(40, KeysOpacity.of(40));
        assertEquals(85, KeysOpacity.of(0));
        assertEquals(73, KeysOpacity.of(73));
        assertEquals(20, KeysOpacity.of(20));
        assertEquals(85, KeysOpacity.of(19));
        assertEquals(85, KeysOpacity.of(250));
    }

    @Test
    public void theDefaultIsHowTheKeysAlwaysLooked() {
        assertEquals(0xD83D2C5A, KeysOpacity.apply(0xD83D2C5A, KeysOpacity.BY_DEFAULT));
    }

    @Test
    public void onlyTheAlphaChanges() {
        assertEquals(0xFFA00040, KeysOpacity.apply(0x00A00040, 100));
        assertEquals(0x66A00040, KeysOpacity.apply(0xFFA00040, 40));
    }
}
