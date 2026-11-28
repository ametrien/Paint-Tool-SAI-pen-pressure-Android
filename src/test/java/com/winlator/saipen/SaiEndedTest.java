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
    public void saiCrashingIsNotAClose() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.started(dir, V, STARTED);
        assertEquals("nothing told yet", -1, SaiEnded.programExitCode());
        SaiEnded.programExited(0xC0000005);          /* winhandler.exe: an access violation */
        long code = SaiEnded.programExitCode();
        assertEquals(0xC0000005L, code);
        assertTrue(SaiEnded.saiCrashed(code));
        SaiEnded.saiError(dir, code);
        SaiEnded.closed(dir);                        /* the session's exit after it */
        Marker m = SaiEnded.left(dir);
        assertNotNull(m);
        assertEquals("0xC0000005", m.detail);
        assertTrue("the end is noted", m.endedMs > 0);
        assertEquals(Why.SAI_CRASHED, SaiEnded.why(m, V, BOOT, SaiEnded.REASON_EXIT_SELF, null));
        assertEquals("a new session forgets the old code", -1, restarted(dir));
    }

    private static long restarted(File dir) {
        SaiEnded.started(dir, V, STARTED + 1);
        return SaiEnded.programExitCode();
    }

    @Test
    public void saiClosingItselfOrNotToldIsNoCrash() {
        assertFalse("0: SAI closed itself", SaiEnded.saiCrashed(0));
        assertFalse("-1: not told (older winhandler)", SaiEnded.saiCrashed(-1));
        assertTrue(SaiEnded.saiCrashed(1));
        SaiEnded.programExited(-1);                  /* 0xFFFFFFFF from Wine is a code, not "not told" */
        assertEquals(0xFFFFFFFFL, SaiEnded.programExitCode());
        assertEquals("0xFFFFFFFF", SaiEnded.code(SaiEnded.programExitCode()));
    }

    @Test
    public void saiPensOwnErrorIsKept() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.started(dir, V, STARTED);
        SaiEnded.appError(dir, new IllegalStateException("bad\nthing"));
        SaiEnded.closed(dir);
        Marker m = SaiEnded.left(dir);
        assertNotNull(m);
        assertEquals("java.lang.IllegalStateException: bad thing", m.detail);
        assertEquals(Why.CRASHED, SaiEnded.why(m, V, BOOT, -1, null));
    }

    @Test
    public void theFirstThingThatEndedSaiIsWhatItSays() throws Exception {
        File dir = tmp.newFolder();
        SaiEnded.started(dir, V, STARTED);
        SaiEnded.goneInBackground(dir);
        SaiEnded.taskRemoved(dir);                   /* swiped away later, SAI long gone */
        SaiEnded.saiError(dir, 5);
        assertEquals(Why.IN_BACKGROUND, SaiEnded.why(SaiEnded.left(dir), V, BOOT, -1, null));
    }

    @Test
    public void anOldMarkReadsAsBefore() {
        Marker m = SaiEnded.parse("state=open\nversion=58\nstarted=" + STARTED + "\n");
        assertNotNull(m);
        assertEquals(-1, m.endedMs);
        assertEquals("", m.detail);
    }

    @Test
    public void theBugReportSaysWhatAndWhen() {
        Marker m = SaiEnded.parse(SaiEnded.text(SaiEnded.SAI_ERROR, V, STARTED, STARTED + 60_000, "0xC0000005"));
        String s = SaiEnded.summary(Why.SAI_CRASHED, m);
        assertTrue(s, s.startsWith("sai crashed (0xC0000005), SAI started "));
        assertTrue(s, s.contains(", ended "));
        String plain = SaiEnded.summary(Why.SWIPED, open());
        assertFalse(plain, plain.contains("ended"));
    }

    @Test
    public void anUncaughtErrorWritesItsReasonFirst() throws Exception {
        File dir = tmp.newFolder();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        final boolean[] passedOn = {false};
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> passedOn[0] = true);
        try {
            SaiEnded.started(dir, V, STARTED);
            SaiEnded.catchAppErrors(dir);
            Thread t = new Thread(() -> { throw new RuntimeException("boom"); });
            t.start();
            t.join();
            Marker m = SaiEnded.left(dir);
            assertNotNull(m);
            assertEquals(SaiEnded.APP_ERROR, m.state);
            assertEquals("java.lang.RuntimeException: boom", m.detail);
            assertTrue("Android's own crash still follows", passedOn[0]);
        }
        finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
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
