package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class TwoFingerGestureTest {
    private final List<String> log = new ArrayList<>();
    private TwoFingerGesture g;

    @Before
    public void setUp() {
        g = new TwoFingerGesture(new TwoFingerGesture.Sink() {
            public void moveCursor(int x, int y) { log.add("cursor " + x + " " + y); }
            public void wheel(int button) { log.add("wheel " + button); }
            public void zoom(int steps) { log.add("zoom " + steps); }
            public void rotateBegin(int x, int y) { log.add("rotbegin " + x + " " + y); }
            public void rotateDrag(int x, int y) { log.add("rotdrag " + x + " " + y); }
            public void rotateEnd() { log.add("rotend"); }
        });
    }

    private List<String> only(String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : log) if (s.startsWith(prefix)) out.add(s);
        return out;
    }

    private int zoomTotal() {
        int sum = 0;
        for (String s : only("zoom")) sum += Integer.parseInt(s.substring(5));
        return sum;
    }

    /** two fingers 200 px apart, side by side, centred on (cx, cy) */
    private void pair(float cx, float cy) {
        g.move(cx - 100, cy, cx + 100, cy);
    }

    /** two fingers `spread` apart, centred on (cx, cy) */
    private void spread(float cx, float cy, float spread) {
        g.move(cx - spread / 2, cy, cx + spread / 2, cy);
    }

    private void end() {
        g.end();
    }

    @Test
    public void theFirstTouchOnlySetsTheStart() {
        pair(500, 300);
        assertTrue(log.isEmpty());
    }

    /* ---- scroll ---- */

    @Test
    public void fingersDownScrollTheViewUp() {
        /* like any touch screen: the canvas follows the fingers */
        pair(500, 300);
        pair(500, 420);               /* 120 px down: one click, 20 kept */
        assertEquals(Arrays.asList("wheel 4"), only("wheel"));
        pair(500, 500);               /* 80 more: 100 in all since the click */
        assertEquals(Arrays.asList("wheel 4", "wheel 4"), only("wheel"));
    }

    @Test
    public void fingersUpScrollTheViewDown() {
        pair(500, 500);
        pair(500, 280);
        assertEquals(Arrays.asList("wheel 5", "wheel 5"), only("wheel"));
    }

    @Test
    public void sidewaysScrollsToo() {
        /* what Winlator lacked: its two fingers only scrolled up and down.
         * Fingers right pull the canvas right, so the view goes left (6). */
        pair(300, 300);
        pair(530, 300);
        assertEquals(Arrays.asList("wheel 6", "wheel 6"), only("wheel"));
        end();
        log.clear();
        pair(300, 300);
        pair(190, 300);
        assertEquals(Arrays.asList("wheel 7"), only("wheel"));
    }

    @Test
    public void theWheelGoesWhereTheFingersAre() {
        pair(500, 300);
        pair(500, 420);
        assertEquals("cursor 500 420", log.get(0));
    }

    @Test
    public void aScrollWhoseFingersDriftDoesNotZoom() {
        g.move(400, 300, 600, 300);
        g.move(395, 360, 605, 360);   /* moved 60 down first: a scroll */
        g.move(385, 500, 615, 500);   /* spread +15 % now: too late to zoom */
        g.end();
        assertEquals(0, only("zoom").size());
        assertEquals(Arrays.asList("wheel 4", "wheel 4"), only("wheel"));
    }

    @Test
    public void fingersThatDriftApartWhileScrollingStillScroll() {
        /* THE TRAP of 0.3.3: 10 px down and 6 px apart per move read as a
         * zoom ("spread +10 %" came before "40 px of travel") */
        float spread = 200, y = 300;
        g.move(500 - spread / 2, y, 500 + spread / 2, y);
        for (int i = 0; i < 12; i++) {
            spread += 6;
            y += 10;
            g.move(500 - spread / 2, y, 500 + spread / 2, y);
        }
        end();
        assertEquals(0, only("zoom").size());
        assertEquals(Arrays.asList("wheel 4"), only("wheel"));
    }

    /* ---- zoom ---- */

    @Test
    public void aPinchZoomsSaiAsSoonAsTheFingersAskForIt() {
        spread(500, 300, 200);
        spread(500, 300, 218);        /* +9 %: not a zoom yet */
        assertEquals(0, only("zoom").size());
        spread(500, 300, 230);        /* +15 %: the first step, already */
        assertEquals(Arrays.asList("zoom 1"), only("zoom"));
        spread(500, 300, 290);        /* 2.2 steps */
        spread(500, 300, 400);        /* x2 = 4.2 steps of 18 % */
        assertEquals(Arrays.asList("zoom 1", "zoom 1", "zoom 2"), only("zoom"));
        end();
        assertEquals("nothing owed at the end", 4, zoomTotal());
    }

    @Test
    public void quickWithoutATimer() {
        /* THE TRAP of 0.3.1: a 30 % spread before the first step and then at
         * most one message per 200 ms felt "very laggy". Every step goes as
         * soon as it is due; ZoomPacer then sends them at SAI's own pace. */
        spread(500, 300, 100);
        for (int i = 1; i <= 15; i++) spread(500, 300, 100 + 20 * i);   /* x4 */
        end();
        assertEquals(8, zoomTotal());
        assertEquals(7, only("zoom").size());
    }

    @Test
    public void zoomIsAtThePointWhereThePinchBegan() {
        spread(500, 300, 200);
        g.move(250, 200, 650, 200);   /* spread to 400, centre now (450, 200) */
        assertEquals("cursor 500 300", log.get(0));
        assertEquals("zoom 4", log.get(1));
    }

    @Test
    public void pinchingInZoomsOut() {
        spread(500, 300, 400);
        spread(500, 300, 200);
        end();
        assertEquals(-4, zoomTotal());
    }

    @Test
    public void restingNearAHalfStepDoesNotFlicker() {
        /* fingers held still at about half a step: no zoom in and out by
         * turns; what is left goes once, at the end */
        spread(500, 300, 200);
        spread(500, 300, 233);                               /* one step */
        for (int i = 0; i < 20; i++) {
            /* 1.40 and 1.58 steps: either side of the next half step */
            spread(500, 300, i % 2 == 0 ? 252 : 260);
        }
        assertEquals(Arrays.asList("zoom 1"), only("zoom"));
        end();                                               /* at 1.58: rounds to two */
        assertEquals(Arrays.asList("zoom 1", "zoom 1"), only("zoom"));
    }

    @Test
    public void aPinchUnderTenPercentDoesNothing() {
        spread(500, 300, 200);
        spread(500, 300, 218);        /* x1.09: not yet a zoom */
        end();
        assertEquals(0, only("zoom").size());
    }

    @Test
    public void aZoomDoesNotScroll() {
        spread(500, 300, 200);
        spread(500, 300, 260);        /* a zoom first */
        spread(500, 500, 300);        /* then the fingers wander 200 px down */
        end();
        assertEquals(0, only("wheel").size());
        assertEquals(2, zoomTotal());
    }

    @Test
    public void aViolentPinchIsCapped() {
        spread(500, 300, 8);
        spread(500, 300, 4000);       /* x500: 37.5 steps of 18 % */
        end();
        assertEquals(TwoFingerGesture.MAX_ZOOM_STEPS, zoomTotal());
    }

    @Test
    public void endingWithoutAGestureDoesNothing() {
        end();
        pair(500, 300);
        end();
        assertTrue(log.isEmpty());
    }

    @Test
    public void zoomLinesAreTheDllsFormat() {
        assertEquals("Z 3", PenWire.zoomLine(3));
        assertEquals("Z -2", PenWire.zoomLine(-2));
        assertEquals(null, PenWire.zoomLine(0));          /* the DLL refuses 0 */
        assertEquals("Z 64", PenWire.zoomLine(500));      /* and more than 64 */
        assertEquals("Z -64", PenWire.zoomLine(-500));
    }
    /* ---- rotate ---- */

    /** two fingers 200 px apart around (cx, cy), the line between them at `deg` */
    private void turn(float cx, float cy, double deg) {
        double a = Math.toRadians(deg);
        float dx = (float) (100 * Math.cos(a)), dy = (float) (100 * Math.sin(a));
        g.move(cx - dx, cy - dy, cx + dx, cy + dy);
    }

    /** the angle of the last drag point around (cx, cy), degrees */
    private double dragAngle(float cx, float cy) {
        List<String> d = only("rotdrag");
        String[] p = d.get(d.size() - 1).split(" ");
        return Math.toDegrees(Math.atan2(Integer.parseInt(p[2]) - cy, Integer.parseInt(p[1]) - cx));
    }

    @Test
    public void turningClockwiseDragsTheCursorClockwise() {
        for (int d = 0; d <= 45; d += 3) turn(500, 300, d);
        /* starts on the first finger's side: left of the centre, 180 deg */
        assertEquals("rotbegin 350 300", only("rotbegin").get(0));
        assertEquals(-135, dragAngle(500, 300), 1.5);
        end();
        assertEquals(Arrays.asList("rotend"), only("rotend"));
        assertTrue(only("zoom").isEmpty());
        assertTrue(only("wheel").isEmpty());
    }

    @Test
    public void turningCounterClockwiseDragsTheOtherWay() {
        for (int d = 0; d >= -30; d -= 3) turn(500, 300, d);
        end();
        assertEquals(150, dragAngle(500, 300), 1.5);
    }

    @Test
    public void turningAcrossTheHalfTurnDoesNotJump() {
        for (int d = 170; d <= 200; d += 3) turn(500, 300, d);
        end();
        /* started at 170 (first finger at -10), turned 30 */
        assertEquals(20, dragAngle(500, 300), 1.5);
    }

    @Test
    public void aRotationIsEndedOnce() {
        for (int d = 0; d <= 45; d += 3) turn(500, 300, d);
        end();
        end();
        assertEquals(1, only("rotend").size());
        assertEquals(1, only("rotbegin").size());
    }

    @Test
    public void aPinchIsNotARotation() {
        for (float sp = 200; sp <= 400; sp += 10) spread(500, 300, sp);
        end();
        assertTrue(only("rot").isEmpty());
        assertTrue(zoomTotal() > 0);
    }

    @Test
    public void aScrollIsNotARotation() {
        for (float y = 300; y <= 600; y += 10) pair(500, y);
        end();
        assertTrue(only("rot").isEmpty());
    }
    @Test
    public void fingersInSwappedOrderAreNotATurn() {
        for (float y = 300; y <= 600; y += 10) {
            if (((int) y / 10) % 2 == 0) g.move(400, y, 600, y);
            else g.move(600, y, 400, y);
        }
        end();
        assertTrue(only("rot").isEmpty());
        assertTrue(!only("wheel").isEmpty());
    }
    /* ---- cancel (a palm) ---- */

    @Test
    public void cancelSendsNoOwedZoom() {
        spread(500, 300, 400);
        spread(500, 300, 438);   /* a zoom (38 px), 0.55 of a step: owed only at the end */
        assertTrue(only("zoom").isEmpty());
        g.cancel();
        assertTrue(only("zoom").isEmpty());
        assertTrue(!g.isActive());
    }

    @Test
    public void endWouldHaveSentTheOwedStep() {
        spread(500, 300, 400);
        spread(500, 300, 438);
        end();
        assertEquals(1, zoomTotal());
    }

    @Test
    public void theSecondRotationStartsItsOwnDrag() {
        for (int d = 0; d <= 45; d += 3) turn(500, 300, d);
        end();
        List<String> d1 = only("rotdrag");
        String lastOfFirst = d1.get(d1.size() - 1);
        log.clear();
        /* from 27 deg the turn is decided at 45 deg (18 deg of a 100 px
         * radius = 30 px of arc): the new drag's first point is exactly
         * where the last drag ended, and must still go out */
        for (int d = 27; d <= 60; d += 3) turn(500, 300, d);
        end();
        assertEquals(lastOfFirst, only("rotdrag").get(0));
    }

    @Test
    public void cancelLetsGoOfARotateDrag() {
        for (int d = 0; d <= 45; d += 3) turn(500, 300, d);
        g.cancel();
        assertEquals(1, only("rotend").size());
        g.end();
        assertEquals(1, only("rotend").size());
    }
}
