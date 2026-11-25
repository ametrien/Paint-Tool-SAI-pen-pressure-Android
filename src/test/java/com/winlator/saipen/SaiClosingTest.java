package com.winlator.saipen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SaiClosingTest {
    @Test
    public void theCoverGoesUpWhenSaisWindowGoes() {
        SaiClosing c = new SaiClosing();
        assertTrue(c.onSaiWindowGone(1000));
        assertFalse("once", c.onSaiWindowGone(1100));
        assertTrue(c.covered());
        assertFalse("SAI is gone: the app leaves, the cover stays", c.giveUp(false, 1000 + SaiClosing.GIVE_UP_MS * 2));
    }

    @Test
    public void aMinimisedSaiIsUncoveredAgain() {
        /* THE TRAP: the window went, SAI did not */
        SaiClosing c = new SaiClosing();
        c.onSaiWindowGone(1000);
        assertFalse(c.giveUp(true, 1000 + SaiClosing.GIVE_UP_MS - 1));
        assertTrue(c.giveUp(true, 1000 + SaiClosing.GIVE_UP_MS));
        assertFalse(c.covered());
        assertTrue("and goes up again the next time", c.onSaiWindowGone(9000));
    }

    @Test
    public void saisWindowComingBackUncovers() {
        SaiClosing c = new SaiClosing();
        assertFalse("nothing to uncover", c.onSaiWindowBack());
        c.onSaiWindowGone(1000);
        assertTrue(c.onSaiWindowBack());
        assertFalse(c.covered());
    }
}
