package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExitButtonSizeTest {
    @Test
    public void mediumIsTheFirst() {
        assertEquals(ExitButtonSize.MEDIUM, ExitButtonSize.BY_DEFAULT);
        assertEquals(1f, ExitButtonSize.scale(ExitButtonSize.MEDIUM), 0f);
    }

    @Test
    public void smallerAndBigger() {
        assertTrue(ExitButtonSize.scale(ExitButtonSize.SMALL) < 1f);
        assertTrue(ExitButtonSize.scale(ExitButtonSize.LARGE) > 1f);
    }

    @Test
    public void anythingElseIsMedium() {
        assertEquals(ExitButtonSize.MEDIUM, ExitButtonSize.of(-1));
        assertEquals(ExitButtonSize.MEDIUM, ExitButtonSize.of(7));
        assertEquals(1f, ExitButtonSize.scale(99), 0f);
    }
}
