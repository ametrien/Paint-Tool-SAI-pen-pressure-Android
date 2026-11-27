package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class PenPressureTest {
    private static final float E = 1e-6f;

    @After
    public void backToNormal() {
        PenPressure.setSoftness(0);
    }

    @Test
    public void normalLeavesThePressureAsItIs() {
        PenPressure.setSoftness(0);
        for (float p : new float[] {0f, 0.01f, 0.25f, 0.5f, 0.99f, 1f, 1.05f})
            assertEquals(p, PenPressure.apply(p), E);
    }

    @Test
    public void softerGivesMoreForTheSamePressButNeverChangesTheEnds() {
        float last = 0.25f;
        for (int s = 1; s <= PenPressure.MAX_SOFTNESS; s++) {
            PenPressure.setSoftness(s);
            float now = PenPressure.apply(0.25f);
            assertTrue("step " + s, now > last);
            last = now;
            assertEquals(0f, PenPressure.apply(0f), E);
            assertEquals(1f, PenPressure.apply(1f), E);
        }
    }

    @Test
    public void curveRisesAllTheWay() {
        PenPressure.setSoftness(PenPressure.MAX_SOFTNESS);
        float last = -1f;
        for (int i = 0; i <= 100; i++) {
            float now = PenPressure.apply(i / 100f);
            assertTrue(now > last || i == 0);
            last = now;
        }
    }

    @Test
    public void badInputIsNoPress() {
        PenPressure.setSoftness(5);
        assertEquals(0f, PenPressure.apply(Float.NaN), E);
        assertEquals(0f, PenPressure.apply(-0.2f), E);
    }

    @Test
    public void stepsOutsideTheSliderAreClamped() {
        assertEquals(1f, PenPressure.exponentFor(-3), E);
        assertEquals(PenPressure.MIN_EXPONENT, PenPressure.exponentFor(99), E);
    }

    @Test
    public void percentShownUnderTheSlider() {
        assertEquals(25, PenPressure.percentFor(0.25f, 0));
        assertEquals(57, PenPressure.percentFor(0.25f, PenPressure.MAX_SOFTNESS));
        assertEquals(100, PenPressure.percentFor(1.1f, PenPressure.MAX_SOFTNESS));
    }

    /* THE TRAP (PenWire.pressureLevel): a feather-light touch must still
       reach SAI as touching, never as 0, at any softness */
    @Test
    public void lightTouchStillTouches() {
        PenPressure.setSoftness(PenPressure.MAX_SOFTNESS);
        assertEquals(1, PenWire.pressureLevel(PenPressure.apply(0f), true, PenWire.PMAX));
        assertTrue(PenWire.pressureLevel(PenPressure.apply(0.001f), true, PenWire.PMAX) >= 1);
    }
}
