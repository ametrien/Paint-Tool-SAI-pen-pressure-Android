package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.winlator.saipen.SaiEnded.Marker;
import com.winlator.saipen.SaiEnded.Why;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class SaiEndedTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long V = 58, STARTED = 1_800_000_000_000L, BOOT = STARTED - 3_600_000;

    private static Marker open() {
        return SaiEnded.parse(SaiEnded.text(SaiEnded.OPEN, V, STARTED));
    }

    @Test
    public void closingOnPurposeLeavesNothing() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.started(dir, V, STARTED);
        assertNotNull(SaiEnded.left(dir));
        SaiEnded.closed(dir);
        assertNull(SaiEnded.left(dir));
    }

    @Test
    public void goneInTheBackgroundOutlivesTheSessionsExit() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.started(dir, V, STARTED);
        SaiEnded.goneInBackground(dir);
        SaiEnded.closed(dir);               /* the session then ends as usual */
        Marker m = SaiEnded.left(dir);
        assertNotNull(m);
        assertEquals(Why.IN_BACKGROUND, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_EXIT_SELF, null));
        SaiEnded.forget(dir);
        assertNull(SaiEnded.left(dir));
    }

    @Test
    public void aSwipeOutlivesTheSessionsExitAndSaysSwiped() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.started(dir, V, STARTED);
        SaiEnded.taskRemoved(dir);          /* the screen's onDestroy */
        SaiEnded.goneInBackground(dir);     /* the session's watch then sees SAI gone */
        SaiEnded.taskRemoved(dir);          /* the service's onTaskRemoved */
        SaiEnded.closed(dir);
        Marker m = SaiEnded.left(dir);
        assertNotNull(m);
        assertEquals(SaiEnded.REMOVED, m.state);
        assertEquals(STARTED, m.startedMs);
        /* SAI Pen ends itself after a swipe: Android says EXIT_SELF */
        assertEquals(Why.SWIPED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_EXIT_SELF, null));
        assertEquals(Why.SWIPED, SaiEnded.why(m, V, BOOT, -1, null));
        assertEquals("an update still says updated", Why.UPDATED, SaiEnded.why(m, V + 1, BOOT, -1, null));
    }

    @Test
    public void aSwipeWithoutASessionDoesNothing() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.taskRemoved(dir);
        assertNull(SaiEnded.left(dir));
    }

    @Test
    public void goneInTheBackgroundWithoutASessionDoesNothing() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.goneInBackground(dir);
        assertNull(SaiEnded.left(dir));
    }

    @Test
    public void androidsReasons() {
        Marker m = open();
        assertEquals(Why.LOW_MEMORY, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_LOW_MEMORY, null));
        assertEquals(Why.CRASHED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_CRASH, null));
        assertEquals(Why.CRASHED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_CRASH_NATIVE, null));
        assertEquals(Why.CRASHED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_ANR, null));
        assertEquals(Why.SWIPED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_USER_REQUESTED, "stop sai.penpress"));
        assertEquals(Why.SWIPED, SaiEnded.why(m, V, BOOT, 13, "remove task"));
        assertEquals(Why.UPDATED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_PACKAGE_UPDATED, null));
        assertEquals(Why.IN_BACKGROUND, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_EXCESSIVE_RESOURCE_USAGE, null));
        assertEquals(Why.UNKNOWN, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_SIGNALED, null));
        assertEquals("before Android 11", Why.UNKNOWN, SaiEnded.why(m, V, BOOT, -1, null));
    }

    @Test
    public void aNewVersionMeansAnUpdate() {
        assertEquals(Why.UPDATED, SaiEnded.why(open(), V + 1, BOOT, SaiEnded.REASON_SIGNALED, null));
    }

    @Test
    public void startedBeforeTheDeviceDid() {
        assertEquals(Why.RESTARTED, SaiEnded.why(open(), V, STARTED + 60_000, -1, null));
        assertEquals("Android's own reason first", Why.LOW_MEMORY,
            SaiEnded.why(open(), V, STARTED + 60_000, SaiEnded.REASON_LOW_MEMORY, null));
    }

    @Test
    public void brokenFilesAreNotMarkers() {
        assertNull(SaiEnded.parse(null));
        assertNull(SaiEnded.parse(""));
        assertNull(SaiEnded.parse("state=open\nversion=x\nstarted=1\n"));
        assertNull(SaiEnded.parse("state=open\nversion=5\n"));
        assertNull(SaiEnded.parse("state=whatever\nversion=5\nstarted=1\n"));
    }

    @Test
    public void batteryHelpsOnlyWhereAndroidEndedIt() {
        assertTrue(SaiEnded.batteryHelps(Why.LOW_MEMORY));
        assertTrue(SaiEnded.batteryHelps(Why.IN_BACKGROUND));
        assertFalse(SaiEnded.batteryHelps(Why.SWIPED));
        assertFalse(SaiEnded.batteryHelps(Why.UPDATED));
        assertFalse(SaiEnded.batteryHelps(Why.CRASHED));
        assertEquals("low memory", SaiEnded.word(Why.LOW_MEMORY));
    }
}
