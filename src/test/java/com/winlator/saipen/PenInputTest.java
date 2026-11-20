package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class PenInputTest {
    private static final int PMAX = 4095;

    /** Records what the pen did, one readable line per call. */
    private static final class Rec implements PenInput.Sink {
        final List<String> log = new ArrayList<>();
        Boolean inRange = null;
        public void movePointer(int x, int y) { log.add("move " + x + " " + y); }
        public void pressButton(int b) { log.add("press " + b); }
        public void releaseButton(int b) { log.add("release " + b); }
        public void sample(int p, float sx, float sy, long evUs) {
            log.add("sample " + p + " " + (int) sx + " " + (int) sy + " t" + evUs);
        }
        public void setInRange(boolean r) { inRange = r; }
        List<String> only(String prefix) {
            List<String> out = new ArrayList<>();
            for (String s : log) if (s.startsWith(prefix)) out.add(s);
            return out;
        }
    }

    /** A pen pointer with history: points[i] = {x, y, pressure, timeUs}. */
    private static PenInput.Event ev(boolean side, float[]... points) {
        return new PenInput.Event() {
            public int historySize() { return points.length - 1; }
            public float x(int pos) { return points[pos][0]; }
            public float y(int pos) { return points[pos][1]; }
            public float pressure(int pos) { return points[pos][2]; }
            public long timeUs(int pos) { return (long) points[pos][3]; }
            public boolean sideButton() { return side; }
        };
    }

    private static float[] pt(float x, float y, float p, long t) {
        return new float[] {x, y, p, t};
    }

    private Rec rec;
    private PenInput pen;

    @Before
    public void setUp() {
        rec = new Rec();
        /* view to X screen: halve, as a 2x phone screen over a 1x X screen */
        pen = new PenInput(rec, (vx, vy, out) -> { out[0] = vx / 2; out[1] = vy / 2; }, PMAX, 1000, 500);
    }

    @Test
    public void stylusAndEraserArePensFingersAndMiceAreNot() {
        assertTrue(PenInput.isPenTool(PenInput.TOOL_TYPE_STYLUS));
        assertTrue(PenInput.isPenTool(PenInput.TOOL_TYPE_ERASER));
        assertFalse(PenInput.isPenTool(PenInput.TOOL_TYPE_FINGER));
        assertFalse(PenInput.isPenTool(PenInput.TOOL_TYPE_MOUSE));
    }

    /** An S Pen: it hovers at the point before it touches there. */
    private void hoverAt(float x, float y, long t) {
        pen.onHover(PenInput.ACTION_HOVER_MOVE, ev(false, pt(x, y, 0f, t)));
    }

    @Test
    public void penDownPutsTheCursorUnderTheTipThenSamplesThenPresses() {
        hoverAt(200, 100, 9);
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(200, 100, 0.5f, 10)));
        assertEquals(Arrays.asList("move 100 50", "sample 2048 100 50 t10", "press 1"), rec.log);
        assertTrue(pen.isTouching());
    }

    @Test
    public void aHeldMovingPenKeepsTheButtonDown() {
        /* the official Winlator bug: a moving pen released the button at
         * once (tap only), so no stroke. One press, no release until up. */
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.3f, 1)));
        for (int i = 1; i <= 20; i++) {
            pen.onTouch(PenInput.ACTION_MOVE, ev(false, pt(i * 10, i * 4, 0.4f, 1 + i)));
        }
        assertEquals(Arrays.asList("press 1"), rec.only("press"));
        assertEquals(0, rec.only("release").size());
        assertEquals("move 100 40", rec.only("move").get(20));
    }

    @Test
    public void everyBatchedSampleIsSentInOrderWithItsOwnPressure() {
        /* THE TRAP: Android packs the samples since the last frame into ONE
         * event. Reading only getX()/getPressure() keeps the last of them,
         * and a quick stroke loses most of its pressure curve. */
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.1f, 100)));
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_MOVE, ev(false,
                pt(10, 10, 0.2f, 104), pt(20, 20, 0.3f, 108), pt(30, 30, 0.4f, 112),
                pt(40, 40, 0.5f, 116)));
        assertEquals(Arrays.asList(
                "sample 819 5 5 t104", "sample 1229 10 10 t108",
                "sample 1638 15 15 t112", "sample 2048 20 20 t116"), rec.only("sample"));
    }

    @Test
    public void theCursorMovesOncePerEventToItsNewestSample() {
        /* THE TRAP, the other way round: every cursor move is a trip through
         * the X server, Wine, wineserver and SAI's message loop, emulated.
         * One per sample crowded the tablet's two fast cores while drawing
         * (2026-11-18). The DLL has every sample; the cursor needs the last. */
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.1f, 100)));
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_MOVE, ev(false,
                pt(10, 10, 0.2f, 104), pt(20, 20, 0.3f, 108), pt(30, 30, 0.4f, 112),
                pt(40, 40, 0.5f, 116)));
        assertEquals(Arrays.asList("move 20 20"), rec.only("move"));
        /* and it moves before that newest sample goes out, as at pen-down */
        assertEquals("move 20 20", rec.log.get(3));
        assertEquals("sample 2048 20 20 t116", rec.log.get(4));

        rec.log.clear();
        pen.onTouch(PenInput.ACTION_UP, ev(false, pt(50, 50, 0.3f, 120), pt(60, 60, 0.2f, 124)));
        assertEquals(Arrays.asList("move 30 30"), rec.only("move"));
        assertEquals(Arrays.asList("sample 1229 25 25 t120", "sample 0 30 30 t124"), rec.only("sample"));
    }

    @Test
    public void liftingSendsZeroThenReleases() {
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.5f, 1)));
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_UP, ev(false, pt(60, 80, 0.4f, 9)));
        assertEquals(Arrays.asList("move 30 40", "sample 0 30 40 t9", "release 1"), rec.log);
        assertFalse(pen.isTouching());
    }

    @Test
    public void aFeatherTouchIsStillATouch() {
        hoverAt(0, 0, 0);
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0f, 1)));
        assertEquals("sample 1 0 0 t1", rec.only("sample").get(0));
    }

    @Test
    public void sideButtonAtTouchIsARightClickWithoutInk() {
        pen.onTouch(PenInput.ACTION_DOWN, ev(true, pt(10, 10, 0.8f, 1)));
        pen.onTouch(PenInput.ACTION_MOVE, ev(true, pt(12, 12, 0.9f, 2)));
        pen.onTouch(PenInput.ACTION_UP, ev(true, pt(12, 12, 0.9f, 3)));
        assertEquals(Arrays.asList("press 3"), rec.only("press"));
        assertEquals(Arrays.asList("release 3"), rec.only("release"));
        for (String s : rec.only("sample")) assertTrue(s, s.startsWith("sample 0 "));
    }

    @Test
    public void aFingerLandingMidStrokeIsJustAPenMove() {
        /* pen is pointer 0, a palm lands as pointer 1 and lifts again */
        assertEquals(PenInput.ACTION_MOVE, PenInput.penAction(PenInput.ACTION_POINTER_DOWN, 1, 0));
        assertEquals(PenInput.ACTION_MOVE, PenInput.penAction(PenInput.ACTION_POINTER_UP, 1, 0));
        /* the pen itself as the second pointer */
        assertEquals(PenInput.ACTION_DOWN, PenInput.penAction(PenInput.ACTION_POINTER_DOWN, 1, 1));
        assertEquals(PenInput.ACTION_UP, PenInput.penAction(PenInput.ACTION_POINTER_UP, 1, 1));
        assertEquals(PenInput.ACTION_MOVE, PenInput.penAction(PenInput.ACTION_MOVE, 0, 0));

        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.5f, 1)));
        pen.onTouch(PenInput.penAction(PenInput.ACTION_POINTER_DOWN, 1, 0), ev(false, pt(4, 4, 0.5f, 2)));
        pen.onTouch(PenInput.penAction(PenInput.ACTION_POINTER_UP, 1, 0), ev(false, pt(8, 8, 0.5f, 3)));
        assertEquals(Arrays.asList("press 1"), rec.only("press"));
        assertEquals(0, rec.only("release").size());
        assertTrue(pen.isTouching());
    }

    @Test
    public void cancelNeverLeavesTheButtonHeld() {
        hoverAt(0, 0, 0);
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.5f, 1)));
        pen.onTouch(PenInput.ACTION_CANCEL, ev(false, pt(0, 0, 0.5f, 2)));
        assertEquals(Arrays.asList("release 1"), rec.only("release"));
        assertEquals("sample 0 0 0 t2", rec.only("sample").get(1));
        assertFalse(pen.isTouching());
    }

    @Test
    public void aMoveWithoutADownStartsTheStroke() {
        pen.onTouch(PenInput.ACTION_MOVE, ev(false, pt(20, 20, 0.5f, 1)));
        assertEquals(Arrays.asList("press 1"), rec.only("press"));
    }

    @Test
    public void anUpWithoutADownDoesNothing() {
        pen.onTouch(PenInput.ACTION_UP, ev(false, pt(20, 20, 0.5f, 1)));
        assertEquals(0, rec.log.size());
    }

    /* Issue #1, Ugee UT2: a pen without hover. Without a packet before the
     * touch SAI took the touch as a mouse click: a full-size dot. */
    @Test
    public void aTouchWithoutHoverTellsSaiThePenIsHereFirst() {
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(200, 100, 0.5f, 10)));
        assertEquals(Arrays.asList("move 100 50", "sample 0 100 50 t10", "sample 2048 100 50 t10", "press 1"),
                     rec.log);
    }

    @Test
    public void aPenThatHoveredSendsNothingExtra() {
        hoverAt(200, 100, 9);
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(200, 100, 0.5f, 10)));
        assertEquals(Arrays.asList("sample 0 100 50 t9", "sample 2048 100 50 t10"), rec.only("sample"));
    }

    @Test
    public void everyTouchWithoutHoverIsAnnouncedNotOnlyTheFirst() {
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.5f, 1)));
        pen.onTouch(PenInput.ACTION_UP, ev(false, pt(10, 0, 0.5f, 2)));
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(200, 100, 0.5f, 10)));
        assertEquals("sample 0 100 50 t10", rec.only("sample").get(0));
    }

    @Test
    public void aPenLeavingRangeIsAnnouncedOnItsNextTouch() {
        hoverAt(0, 0, 1);
        pen.onHover(PenInput.ACTION_HOVER_EXIT, ev(false, pt(0, 0, 0f, 2)));
        rec.log.clear();
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(200, 100, 0.5f, 10)));
        assertEquals("sample 0 100 50 t10", rec.only("sample").get(0));
    }

    @Test
    public void theNoHoverTestDropsHoverAndStillAnnouncesTheTouch() {
        pen.setIgnoreHover(true);
        hoverAt(200, 100, 9);
        assertEquals(0, rec.log.size());
        assertEquals(null, rec.inRange);
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(200, 100, 0.5f, 10)));
        assertEquals(Arrays.asList("sample 0 100 50 t10", "sample 2048 100 50 t10"), rec.only("sample"));
    }

    @Test
    public void noSystemPropertiesOffAndroidMeansEmpty() {
        assertEquals("", PenInput.systemProperty(PenInput.PROP_TEST_NO_HOVER));
    }

    @Test
    public void hoverMovesTheCursorAndSendsZeroPressure() {
        pen.onHover(PenInput.ACTION_HOVER_ENTER, ev(false, pt(100, 100, 0f, 1)));
        pen.onHover(PenInput.ACTION_HOVER_MOVE, ev(false, pt(110, 100, 0f, 2), pt(120, 100, 0f, 3)));
        assertEquals(Arrays.asList("move 50 50", "sample 0 50 50 t1", "sample 0 55 50 t2",
                                   "move 60 50", "sample 0 60 50 t3"), rec.log);
        assertEquals(Boolean.TRUE, rec.inRange);
        pen.onHover(PenInput.ACTION_HOVER_EXIT, ev(false, pt(120, 100, 0f, 4)));
        assertEquals(Boolean.FALSE, rec.inRange);
    }

    @Test
    public void theCursorStaysOnTheScreenOverTheLetterbox() {
        /* X screen is 1000x500 here; the pen is beyond its left/top and
         * right/bottom edges. The cursor clamps; the sample keeps the raw
         * point (PenWire.toWire clamps it the same way for the DLL). */
        pen.onHover(PenInput.ACTION_HOVER_MOVE, ev(false, pt(-40, -10, 0f, 1)));
        pen.onHover(PenInput.ACTION_HOVER_MOVE, ev(false, pt(2400, 1200, 0f, 2)));
        assertEquals(Arrays.asList("move 0 0", "move 999 499"), rec.only("move"));
    }

    @Test
    public void hoverWhileTouchingIsIgnored() {
        pen.onTouch(PenInput.ACTION_DOWN, ev(false, pt(0, 0, 0.5f, 1)));
        rec.log.clear();
        pen.onHover(PenInput.ACTION_HOVER_MOVE, ev(false, pt(50, 50, 0f, 2)));
        assertEquals(0, rec.log.size());
    }
}
