package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyboardShiftTest {
    @Test
    public void aFieldNearTheTopNeedsNoShift() {
        assertEquals(0f, KeyboardShift.offsetY(100, 1200), 0f);
        assertEquals(0f, KeyboardShift.offsetY(300, 1200), 0f);     /* a quarter down */
    }

    @Test
    public void aFieldLowerDownComesUpToAQuarter() {
        assertEquals(500f, KeyboardShift.offsetY(800, 1200), 0f);   /* 800 shows at 300 */
    }

    @Test
    public void neverMoreThanHalfTheScreen() {
        assertEquals(600f, KeyboardShift.offsetY(1199, 1200), 0f);
    }

    @Test
    public void aTouchMeansThePointThatIsShownThere() {
        /* THE TRAP: the picture is up by 500, so a finger at 300 is on
         * what is really at 800; without the shift the cursor went to 300,
         * well above the finger */
        assertEquals(800f, KeyboardShift.touchY(300, KeyboardShift.offsetY(800, 1200)), 0f);
        assertEquals("keyboard closed: no change", 300f, KeyboardShift.touchY(300, 0), 0f);
    }
}
