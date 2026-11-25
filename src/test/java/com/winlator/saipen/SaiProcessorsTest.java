package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SaiProcessorsTest {
    @Test
    public void tabS6LiteSuggestsItsTwoFastCores() {
        assertEquals(2, SaiProcessors.suggested(2, 8));
    }

    @Test
    public void oneTopCoreAndFourMiddleOnesCountTogether() {
        /* Snapdragon 8 Gen 2 kind: FastCores gives the top and middle cores */
        assertEquals(5, SaiProcessors.suggested(5, 8));
    }

    @Test
    public void neverFewerThanTwo() {
        assertEquals(2, SaiProcessors.suggested(1, 8));
    }

    @Test
    public void noSlowCoresOrUnknownKeepsEveryCore() {
        assertEquals(SaiProcessors.ALL, SaiProcessors.suggested(0, 8));
        assertEquals(SaiProcessors.ALL, SaiProcessors.suggested(8, 8));
        assertEquals(SaiProcessors.ALL, SaiProcessors.suggested(2, 2));
    }

    @Test
    public void autoFollowsTheDevice() {
        assertEquals(2, SaiProcessors.resolve(SaiProcessors.AUTO, 2, 8));
        assertEquals(SaiProcessors.ALL, SaiProcessors.resolve(SaiProcessors.AUTO, 0, 8));
    }

    @Test
    public void aPickedNumberStaysWithinTheDevice() {
        assertEquals(3, SaiProcessors.resolve(3, 2, 8));
        assertEquals(SaiProcessors.ALL, SaiProcessors.resolve(8, 2, 8));
        assertEquals(SaiProcessors.ALL, SaiProcessors.resolve(12, 2, 8));
        assertEquals(2, SaiProcessors.resolve(1, 2, 8));
        assertEquals(SaiProcessors.ALL, SaiProcessors.resolve(SaiProcessors.ALL, 2, 8));
    }

    @Test
    public void allLeavesTheVariableOut() {
        assertNull(SaiProcessors.envValue(SaiProcessors.ALL));
        assertEquals("2", SaiProcessors.envValue(2));
    }

    @Test
    public void choicesFromTwoUpToAll() {
        assertArrayEquals(new int[] {2, 3, 4, 5, 6, 7, SaiProcessors.ALL}, SaiProcessors.choices(8));
        assertArrayEquals(new int[] {2, 3, SaiProcessors.ALL}, SaiProcessors.choices(4));
        assertArrayEquals(new int[] {SaiProcessors.ALL}, SaiProcessors.choices(2));
    }
}
