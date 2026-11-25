package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FramePacerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeHost implements FramePacer.Host {
        int renders, armed;
        @Override public void render() { renders++; }
        @Override public void atNextRefresh() { armed++; }
    }

    @Test
    public void theFirstRequestAfterARefreshDrawsAtOnce() {
        FakeHost h = new FakeHost();
        FramePacer p = new FramePacer(h, true);
        p.request();
        assertEquals(1, h.renders);
        assertEquals(1, h.armed);
    }

    @Test
    public void moreRequestsBeforeTheRefreshBecomeOneFrameAtIt() {
        FakeHost h = new FakeHost();
        FramePacer p = new FramePacer(h, true);
        p.request();
        p.request();
        p.request();
        p.request();
        assertEquals(1, h.renders);
        assertEquals(1, h.armed);              /* asked for the refresh once */
        p.onRefresh();
        assertEquals(2, h.renders);
        assertEquals(2, h.armed);              /* and the one after, to reset the budget */
        assertEquals("4 requests, 2 frames", p.counts());
    }

    @Test
    public void aRefreshWithNothingWaitingDrawsNothingAndStops() {
        FakeHost h = new FakeHost();
        FramePacer p = new FramePacer(h, true);
        p.request();
        p.onRefresh();
        assertEquals(1, h.renders);
        assertEquals(1, h.armed);              /* no more refreshes asked for */
        p.request();                           /* the budget is back: at once again */
        assertEquals(2, h.renders);
        assertEquals(2, h.armed);
    }

    @Test
    public void afterAWaitingFrameTheNextRequestWaitsForTheNextRefresh() {
        FakeHost h = new FakeHost();
        FramePacer p = new FramePacer(h, true);
        p.request();
        p.request();
        p.onRefresh();                         /* drew the waiting one */
        p.request();                           /* same refresh period: waits */
        assertEquals(2, h.renders);
        p.onRefresh();
        assertEquals(3, h.renders);
    }

    @Test
    public void freeDrawsEveryRequestAndNeverAsksForRefreshes() {
        FakeHost h = new FakeHost();
        FramePacer p = new FramePacer(h, false);
        for (int i = 0; i < 5; i++) p.request();
        assertEquals(5, h.renders);
        assertEquals(0, h.armed);
    }

    @Test
    public void theModeFile() throws IOException {
        File d = tmp.getRoot();
        assertTrue(FramePacer.readPaced(d));                 /* no file: paced */
        Files.write(new File(d, FramePacer.MODE_FILE).toPath(), "free\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(FramePacer.readPaced(d));
        Files.write(new File(d, FramePacer.MODE_FILE).toPath(), "vsync".getBytes(StandardCharsets.UTF_8));
        assertTrue(FramePacer.readPaced(d));
    }
}
