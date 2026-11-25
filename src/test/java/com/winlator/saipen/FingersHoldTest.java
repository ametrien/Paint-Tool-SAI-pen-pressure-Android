package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FingersHoldTest {
    private static final float[] X = {100, 200, 300}, Y = {500, 500, 500};
    private final FingersHold hold = new FingersHold(20);

    @Test
    public void threeStillFingersFireOnceAfterTheHold() {
        hold.onTouch(3, X, Y, 1000);
        assertEquals(1000 + FingersHold.HOLD_MS, hold.when());
        assertFalse(hold.due(1000 + FingersHold.HOLD_MS - 1));
        assertTrue(hold.due(1000 + FingersHold.HOLD_MS));
        assertFalse("once", hold.due(5000));
        hold.onTouch(3, X, Y, 5000);
        assertFalse("still the same hold", hold.due(9000));
    }

    @Test
    public void againAfterAllFingersLift() {
        hold.onTouch(3, X, Y, 0);
        assertTrue(hold.due(FingersHold.HOLD_MS));
        hold.onTouch(0, X, Y, 2000);
        hold.onTouch(3, X, Y, 3000);
        assertTrue(hold.due(3000 + FingersHold.HOLD_MS));
    }

    @Test
    public void movingFingersAreNotAHold() {
        hold.onTouch(3, X, Y, 0);
        hold.onTouch(3, new float[]{100, 200, 340}, Y, 100);
        assertEquals(-1, hold.when());
        assertFalse(hold.due(FingersHold.HOLD_MS));
        hold.onTouch(3, X, Y, 200);
        assertFalse("not until the fingers lift", hold.due(200 + FingersHold.HOLD_MS));
    }

    @Test
    public void aLittleWobbleIsStillAHold() {
        hold.onTouch(3, X, Y, 0);
        hold.onTouch(3, new float[]{110, 195, 305}, new float[]{505, 495, 510}, 300);
        assertTrue(hold.due(FingersHold.HOLD_MS));
    }

    @Test
    public void aFourthFingerOrTwoFingersAreNotIt() {
        hold.onTouch(3, X, Y, 0);
        hold.onTouch(4, new float[]{100, 200, 300, 400}, new float[]{500, 500, 500, 500}, 100);
        assertFalse(hold.due(FingersHold.HOLD_MS));
        hold.onTouch(2, X, Y, 0);
        assertEquals(-1, hold.when());
    }

    @Test
    public void aQuickThreeFingerTapIsLeftToTheKeyboard() {
        hold.onTouch(3, X, Y, 0);
        hold.onTouch(0, X, Y, 150);
        assertFalse(hold.due(FingersHold.HOLD_MS));
    }

    @Test
    public void fourFingersForTheGesturesNotThree() {
        FingersHold h = new FingersHold(10, 4);
        float[] x4 = {0, 10, 20, 30}, y4 = {0, 0, 0, 0}, x3 = {0, 10, 20}, y3 = {0, 0, 0};
        h.onTouch(3, x3, y3, 0);
        assertEquals(-1, h.when());
        h.onTouch(4, x4, y4, 100);
        assertEquals(100 + FingersHold.HOLD_MS, h.when());
        assertTrue(h.due(100 + FingersHold.HOLD_MS));
    }
}
