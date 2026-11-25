package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PalmFilterTest {
    @Test
    public void aCanceledLiftIsAPalm() {
        assertTrue(PalmFilter.isCanceledLift(PalmFilter.ACTION_POINTER_UP, PalmFilter.FLAG_CANCELED));
        assertTrue(PalmFilter.isCanceledLift(PalmFilter.ACTION_UP, PalmFilter.FLAG_CANCELED | 0x1));
    }

    @Test
    public void anOrdinaryLiftIsAFinger() {
        assertFalse(PalmFilter.isCanceledLift(PalmFilter.ACTION_POINTER_UP, 0));
        assertFalse(PalmFilter.isCanceledLift(PalmFilter.ACTION_UP, 0x1));
    }

    @Test
    public void theFlagOnlyCountsOnALift() {
        assertFalse(PalmFilter.isCanceledLift(PalmFilter.ACTION_DOWN, PalmFilter.FLAG_CANCELED));
        assertFalse(PalmFilter.isCanceledLift(2 /* MOVE */, PalmFilter.FLAG_CANCELED));
    }

    @Test
    public void cancelIsCancel() {
        assertTrue(PalmFilter.isCancel(PalmFilter.ACTION_CANCEL));
        assertFalse(PalmFilter.isCancel(PalmFilter.ACTION_UP));
    }

    @Test
    public void aPalmToolLandingIsAPalm() {
        assertTrue(PalmFilter.isPalmDown(PalmFilter.ACTION_DOWN, PalmFilter.TOOL_TYPE_PALM));
        assertTrue(PalmFilter.isPalmDown(PalmFilter.ACTION_POINTER_DOWN, PalmFilter.TOOL_TYPE_PALM));
        assertFalse(PalmFilter.isPalmDown(PalmFilter.ACTION_DOWN, 1 /* FINGER */));
        assertFalse(PalmFilter.isPalmDown(PalmFilter.ACTION_UP, PalmFilter.TOOL_TYPE_PALM));
    }

    /* ---- pen near ---- */

    @Test
    public void fingersAreFreeBeforeThePenIsSeen() {
        assertFalse(PalmFilter.penNear(10_000, PalmFilter.NEVER, false));
    }

    @Test
    public void thePenTouchingBlocksFingers() {
        assertTrue(PalmFilter.penNear(10_000, PalmFilter.NEVER, true));
    }

    @Test
    public void fingersStayBlockedForAWhileAfterThePen() {
        assertTrue(PalmFilter.penNear(10_000, 10_000, false));
        assertTrue(PalmFilter.penNear(11_499, 10_000, false));
        assertFalse(PalmFilter.penNear(11_500, 10_000, false));
        assertFalse(PalmFilter.penNear(60_000, 10_000, false));
    }

    @Test
    public void aClockGoingBackDoesNotBlockForever() {
        assertFalse(PalmFilter.penNear(5_000, 10_000, false));
    }

    /* ---- big contact ---- */

    @Test
    public void aFingertipIsNotBig() {
        /* 10 mm at 160 dpi = 63 px */
        assertFalse(PalmFilter.isBigContact(63, 160));
    }

    @Test
    public void aPalmIsBig() {
        /* 30 mm at 160 dpi = 189 px */
        assertTrue(PalmFilter.isBigContact(189, 160));
    }

    @Test
    public void noDpiMeansNotBig() {
        assertFalse(PalmFilter.isBigContact(500, 0));
    }

    @Test
    public void theHoveringPenDropsALoneFingerButNotAGesture() {
        assertTrue(PalmFilter.dropOnPenHover(1));
        assertFalse(PalmFilter.dropOnPenHover(0));
        assertFalse(PalmFilter.dropOnPenHover(2));
    }

    @Test
    public void distanceInMm() {
        /* 300-400-500 px at 254 dpi: 500 px is 50 mm */
        assertEquals(50f, PalmFilter.distanceMm(300, 400, 254), 0.01f);
        assertEquals(50f, PalmFilter.distanceMm(-300, -400, 254), 0.01f);
        assertEquals(0f, PalmFilter.distanceMm(300, 400, 0), 0f);
    }
}
