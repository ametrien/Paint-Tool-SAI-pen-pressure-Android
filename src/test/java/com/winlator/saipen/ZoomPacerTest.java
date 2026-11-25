package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ZoomPacerTest {
    @Test
    public void theFirstStepGoesAtOnce() {
        ZoomPacer p = new ZoomPacer();
        assertEquals(1, p.request(1, 0));
        assertTrue(p.busy());
    }

    @Test
    public void stepsWhileSaiDrawsWaitAndGoTogether() {
        ZoomPacer p = new ZoomPacer();
        assertEquals(1, p.request(1, 0));
        assertEquals(0, p.request(1, 30));      /* SAI is still on the first */
        assertEquals(0, p.request(1, 60));
        p.onContentUpdate(150);                 /* SAI draws the first step */
        p.onContentUpdate(260);
        assertEquals(0, p.tick(260 + ZoomPacer.QUIET_MS - 1));   /* maybe not done */
        assertEquals("the two that waited, in one go", 2, p.tick(260 + ZoomPacer.QUIET_MS));
        assertEquals(260, p.lastRedrawMs());
    }

    @Test
    public void aFastSaiGetsTheNextStepRightAway() {
        /* no fixed timer: when SAI is done, the next step is not held back */
        ZoomPacer p = new ZoomPacer();
        assertEquals(1, p.request(1, 0));
        p.onContentUpdate(40);
        assertEquals(0, p.tick(40 + ZoomPacer.QUIET_MS - 1));
        assertEquals(0, p.tick(40 + ZoomPacer.QUIET_MS));        /* done, nothing waiting */
        assertFalse(p.busy());
        assertEquals(1, p.request(1, 40 + ZoomPacer.QUIET_MS + 5));   /* the next goes at once */
    }

    @Test
    public void aStepSaiDoesNotDrawDoesNotStopTheRest() {
        /* THE TRAP: at SAI's largest zoom a step draws nothing */
        ZoomPacer p = new ZoomPacer();
        assertEquals(1, p.request(1, 0));
        assertEquals(0, p.request(1, 10));
        assertEquals(0, p.tick(ZoomPacer.MAX_WAIT_MS - 1));
        assertEquals(1, p.tick(ZoomPacer.MAX_WAIT_MS));
        assertEquals(-1, p.lastRedrawMs());
    }

    @Test
    public void inAndOutCancelOut() {
        ZoomPacer p = new ZoomPacer();
        assertEquals(1, p.request(1, 0));
        p.request(1, 20);
        p.request(-1, 40);                      /* the fingers went back */
        p.onContentUpdate(100);
        assertEquals("nothing left to send", 0, p.tick(200));
        assertFalse(p.busy());
    }

    @Test
    public void drawingBeforeTheStepDoesNotCount() {
        ZoomPacer p = new ZoomPacer();
        p.onContentUpdate(0);                   /* not busy: ignored */
        assertEquals(1, p.request(1, 10));
        assertEquals(0, p.tick(200));           /* SAI has not drawn it yet */
    }
}
