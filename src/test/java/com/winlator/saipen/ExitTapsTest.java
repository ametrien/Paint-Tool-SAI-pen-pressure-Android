package com.winlator.saipen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExitTapsTest {
    @Test
    public void oneTapOnlyAsks() {
        ExitTaps t = new ExitTaps();
        assertFalse(t.tap(1000));
        assertTrue(t.asking(1000));
        assertTrue(t.asking(1000 + ExitTaps.CONFIRM_MS));
        assertFalse("not answered: it stops asking", t.asking(1001 + ExitTaps.CONFIRM_MS));
    }

    @Test
    public void aSecondTapCloses() {
        ExitTaps t = new ExitTaps();
        t.tap(1000);
        assertTrue(t.tap(1800));
        assertFalse("done: it asks no more", t.asking(1800));
        assertFalse("and the next tap asks again", t.tap(2500));
    }

    @Test
    public void aLateSecondTapAsksAgain() {
        ExitTaps t = new ExitTaps();
        t.tap(1000);
        assertFalse(t.tap(1001 + ExitTaps.CONFIRM_MS));
        assertTrue("asking anew from the late tap", t.asking(1001 + ExitTaps.CONFIRM_MS));
        assertTrue(t.tap(1500 + ExitTaps.CONFIRM_MS));
    }

    @Test
    public void aPalmFlickerIsNotTwoTaps() {
        /* THE TRAP: touches 3 to 17 ms apart under a resting palm */
        ExitTaps t = new ExitTaps();
        t.tap(1000);
        assertFalse(t.tap(1003));
        assertFalse(t.tap(1017));
        assertFalse(t.tap(999 + ExitTaps.MIN_GAP_MS));
        assertTrue("still asking", t.asking(1100));
        assertTrue("a real second tap still closes", t.tap(1000 + ExitTaps.MIN_GAP_MS));
    }

    @Test
    public void closingTheKeysForgetsTheFirstTap() {
        ExitTaps t = new ExitTaps();
        t.tap(1000);
        t.reset();
        assertFalse(t.asking(1100));
        assertFalse(t.tap(1500));
    }
}
