package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class EdgeSwipeTest {
    private static final float W = 1000;

    private static EdgeSwipe swipe() {
        return new EdgeSwipe(30, 100);
    }

    @Test
    public void fromTheLeftEdgeInward() {
        EdgeSwipe s = swipe();
        s.down(10, 500, W, true, true);
        assertEquals(EdgeSwipe.NONE, s.move(60, 505));
        assertEquals(EdgeSwipe.LEFT, s.move(115, 510));
        /* once per touch */
        assertEquals(EdgeSwipe.NONE, s.move(300, 510));
        assertFalse(s.active());
    }

    @Test
    public void fromTheRightEdgeInward() {
        EdgeSwipe s = swipe();
        s.down(990, 500, W, true, true);
        assertEquals(EdgeSwipe.RIGHT, s.move(880, 490));
    }

    @Test
    public void notFromTheMiddleNorASideWithNoAction() {
        EdgeSwipe s = swipe();
        s.down(200, 500, W, true, true);
        assertEquals(EdgeSwipe.NONE, s.move(400, 500));
        s.down(10, 500, W, false, true);
        assertEquals(EdgeSwipe.NONE, s.move(200, 500));
    }

    @Test
    public void notAlongTheEdge() {
        EdgeSwipe s = swipe();
        s.down(10, 500, W, true, true);
        assertEquals(EdgeSwipe.NONE, s.move(20, 700));
        assertFalse(s.active());
        assertEquals(EdgeSwipe.NONE, s.move(200, 700));
    }

    @Test
    public void notWhenTooSteep() {
        EdgeSwipe s = swipe();
        s.down(10, 500, W, true, true);
        /* 110 in, 90 down: more up/down than half the way in */
        assertEquals(EdgeSwipe.NONE, s.move(120, 590));
    }

    @Test
    public void anotherFingerCancels() {
        EdgeSwipe s = swipe();
        s.down(10, 500, W, true, true);
        s.cancel();
        assertEquals(EdgeSwipe.NONE, s.move(200, 500));
    }
}
