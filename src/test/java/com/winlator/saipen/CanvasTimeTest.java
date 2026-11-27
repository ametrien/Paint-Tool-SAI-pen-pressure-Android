package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CanvasTimeTest {
    private static final String SAI = "PaintTool SAI Ver.2 (64bit) Alpha.2026.11.20";

    @Test
    public void keyFromSaisTitle() {
        assertEquals("NewCanvas2noga.sai2", CanvasTime.key(SAI + " - My Computer /  (F:) / NewCanvas2noga.sai2 (*)"));
        assertEquals("NewCanvas2noga.sai2", CanvasTime.key(SAI + " - My Computer /  (F:) / NewCanvas2noga.sai2"));
        assertEquals("comics/page 1/a b.sai2", CanvasTime.key(SAI + " - My Computer / (F:) / comics / page 1 / a b.sai2 (*)"));
        assertEquals(":NewCanvas1", CanvasTime.key(SAI + " - NewCanvas1 (*)"));
        assertNull(CanvasTime.key(SAI));
        assertNull(CanvasTime.key(SAI + " - My Computer / (C:) / x.sai2"));
        assertNull(CanvasTime.key("Open"));
        assertNull(CanvasTime.key(null));
    }

    @Test
    public void strokesAndShortPausesCount() {
        CanvasTime t = new CanvasTime();
        t.penDown(100); t.penUp(102, "a.sai2", 1000);        // 2 s
        t.penDown(110); t.penUp(111, "a.sai2", 2000);        // 1 s + 8 s pause
        t.penDown(500); t.penUp(501, "a.sai2", 3000);        // 1 s, the long pause is not drawing
        CanvasTime.Canvas c = t.get("a.sai2");
        assertEquals(3, c.strokes);
        assertEquals(12.0, c.seconds, 0.01);
        assertEquals(1000, c.first);
        assertEquals(3000, c.last);
    }

    @Test
    public void aPauseBetweenTwoDrawingsCountsForNeither() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(1, "a.sai2", 1);
        t.penDown(10); t.penUp(11, "b.sai2", 2);
        assertEquals(1.0, t.get("b.sai2").seconds, 0.01);
    }

    @Test
    public void noDrawingKnownCountsNothing() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(1, null, 1);
        t.penDown(2); t.penUp(3, "a.sai2", 2);
        assertEquals(1.0, t.get("a.sai2").seconds, 0.01);
    }

    @Test
    public void unsavedCanvasMovesToTheFileItIsSavedAs() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(10, ":NewCanvas1", 1);
        t.titleChanged(":NewCanvas1", "blanket.sai2", 50_000);
        t.fileSaved("blanket.sai2", 52_000);
        assertNull(t.get(":NewCanvas1"));
        assertEquals(10.0, t.get("blanket.sai2").seconds, 0.01);
        /* the next stroke continues the same drawing: its 10 s pause counts, 10 + 10 + 1 */
        t.penDown(20); t.penUp(21, "blanket.sai2", 2);
        assertEquals(21.0, t.get("blanket.sai2").seconds, 0.01);
    }

    @Test
    public void fileWrittenFirstThenTitleMergesToo() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(5, ":NewCanvas1", 1);
        t.fileSaved("x.sai2", 1_000);
        t.titleChanged(":NewCanvas1", "x.sai2", 2_000);
        assertEquals(5.0, t.get("x.sai2").seconds, 0.01);
    }

    @Test
    public void aTabSwitchMovesNothing() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(5, ":NewCanvas1", 1);
        t.titleChanged(":NewCanvas1", "old.sai2", 10_000);   // no file written
        t.fileSaved("old.sai2", 100_000);                     // saved much later
        assertEquals(5.0, t.get(":NewCanvas1").seconds, 0.01);
        assertNull(t.get("old.sai2"));
    }

    @Test
    public void aSavedNewCanvasIsRememberedForTheTimelapse() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(5, ":NewCanvas1", 1);
        t.titleChanged(":NewCanvas1", "hands/hand.sai2", 50_000);
        t.fileSaved("hands/hand.sai2", 51_000);
        assertEquals("hand.sai2", t.savedAs("NewCanvas1", 40_000));
        assertNull(t.savedAs("NewCanvas1", 60_000));      // saved before that session began
        assertNull(t.savedAs("NewCanvas2", 0));
        CanvasTime back = new CanvasTime();
        back.parse(t.serialize());
        assertEquals("hand.sai2", back.savedAs("NewCanvas1", 40_000));
        assertEquals("hand.sai2", TimelapseVideo.titleOf("NewCanvas1", 40_000, back));
        assertEquals("furisode.sai2", TimelapseVideo.titleOf("furisode.sai2", 40_000, back));
        assertEquals("NewCanvas1", TimelapseVideo.titleOf("NewCanvas1", 40_000, null));
    }

    @Test
    public void sessionsSplitOnLongBreaksAndSaiRestarts() {
        CanvasTime t = new CanvasTime();
        long min = 60_000;
        t.penDown(0); t.penUp(2, "a.sai2", 1_000 * min);
        t.penDown(10); t.penUp(11, "a.sai2", 1_005 * min);          // 5 min later: same sitting
        t.penDown(2000); t.penUp(2001, "a.sai2", 1_030 * min);      // 25 min later: a new one
        t.cancel();                                                 // SAI closed
        t.penDown(3000); t.penUp(3001, "a.sai2", 1_032 * min);      // 2 min later, after a restart
        CanvasTime.Canvas c = t.get("a.sai2");
        assertEquals(3, c.sessions.size());
        assertEquals(2, c.sessions.get(0).strokes);
        assertEquals(1_000 * min, c.sessions.get(0).start);
        assertEquals(1_005 * min, c.sessions.get(0).end);
        assertEquals(1, c.sessions.get(2).strokes);
        CanvasTime back = new CanvasTime();
        back.parse(t.serialize());
        CanvasTime.Canvas b = back.get("a.sai2");
        assertEquals(3, b.sessions.size());
        assertEquals(4, b.strokes);
        assertEquals(1_030 * min, b.sessions.get(1).start);
    }

    @Test
    public void aSavedNewCanvasTakesItsSessionsAlong() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(5, ":NewCanvas1", 1_000);
        t.titleChanged(":NewCanvas1", "x.sai2", 50_000);
        t.fileSaved("x.sai2", 51_000);
        assertEquals(1, t.get("x.sai2").sessions.size());
    }

    @Test
    public void fileRoundTrip() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(3, "comics/a b.sai2", 1700000000000L);
        CanvasTime back = new CanvasTime();
        back.parse(t.serialize());
        CanvasTime.Canvas c = back.get("comics/a b.sai2");
        assertEquals(1, c.strokes);
        assertEquals(3.0, c.seconds, 0.01);
        assertEquals(1700000000000L, c.first);
    }

    /* ---- renames (2026-11-27) ---- */

    @Test
    public void saveAsOfASavedDrawingCopiesItsHistory() {
        CanvasTime t = new CanvasTime();
        t.titleChanged(null, "art.sai2", 0);
        t.penDown(0); t.penUp(10, "art.sai2", 1000);
        t.titleChanged("art.sai2", "art-v2.sai2", 50_000);
        t.fileSaved("art-v2.sai2", 51_000);
        assertEquals("the new file has the history", 1, t.get("art-v2.sai2").strokes);
        assertEquals("and the old file keeps it", 1, t.get("art.sai2").strokes);
        assertEquals(1, t.get("art-v2.sai2").sessions.size());
        t.penDown(20); t.penUp(21, "art-v2.sai2", 60_000);
        assertEquals(2, t.get("art-v2.sai2").strokes);
        assertEquals(1, t.get("art.sai2").strokes);
    }

    @Test
    public void aSwitchToADocumentOpenBeforeCopiesNothing() {
        CanvasTime t = new CanvasTime();
        t.titleChanged(null, "a.sai2", 0);
        t.titleChanged("a.sai2", "b.sai2", 1_000);      /* b opened */
        t.titleChanged("b.sai2", "a.sai2", 2_000);
        t.fileSaved("b.sai2", 3_000);                   /* not b's Save As: we left it */
        assertEquals(null, t.get("b.sai2"));
        t.penDown(0); t.penUp(10, "a.sai2", 3_000);
        t.titleChanged("a.sai2", "b.sai2", 50_000);     /* back to b, Ctrl+S on it */
        t.fileSaved("b.sai2", 51_000);
        assertEquals(null, t.get("b.sai2"));
        assertEquals(1, t.get("a.sai2").strokes);
    }

    @Test
    public void aSaveAsLongAfterTheSwitchCopiesNothing() {
        CanvasTime t = new CanvasTime();
        t.titleChanged(null, "a.sai2", 0);
        t.penDown(0); t.penUp(10, "a.sai2", 1);
        t.titleChanged("a.sai2", "c.sai2", 50_000);
        t.fileSaved("c.sai2", 50_000 + CanvasTime.MERGE_MS + 1);
        assertEquals(null, t.get("c.sai2"));
    }

    static java.util.Map<String, long[]> files(Object... kv) {
        java.util.Map<String, long[]> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 3) m.put((String) kv[i], new long[] {(Long) kv[i + 1], (Long) kv[i + 2]});
        return m;
    }

    @Test
    public void aRenameInAFileManagerIsFollowed() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(10, "art.sai2", 1000);
        assertTrue("its file noted", t.follow(files("art.sai2", 5000L, 777L, "other.sai2", 9000L, 888L)));
        assertTrue(t.follow(files("comics/portrait.sai2", 5000L, 777L, "other.sai2", 9000L, 888L)));
        assertEquals(null, t.get("art.sai2"));
        assertEquals(1, t.get("comics/portrait.sai2").strokes);
        assertFalse("nothing more to do", t.follow(files("comics/portrait.sai2", 5000L, 777L)));
    }

    @Test
    public void aRenameIsOnlyFollowedWhenItIsSure() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(10, "art.sai2", 1000);
        t.penDown(20); t.penUp(21, "busy.sai2", 2000);
        t.follow(files("art.sai2", 5000L, 777L, "busy.sai2", 1L, 2L));
        /* two candidates exactly alike: a copy; nobody guesses */
        t.follow(files("x.sai2", 5000L, 777L, "y.sai2", 5000L, 777L, "busy.sai2", 1L, 2L));
        assertEquals(1, t.get("art.sai2").strokes);
        assertEquals(null, t.get("x.sai2"));
        /* the only match has time of its own: not a rename */
        t.follow(files("busy.sai2", 5000L, 777L));
        assertEquals(1, t.get("art.sai2").strokes);
        assertEquals(1, t.get("busy.sai2").strokes);
        /* saved since (another size): deleted, not renamed */
        t.follow(files("n.sai2", 5100L, 999L, "busy.sai2", 1L, 2L));
        assertEquals(null, t.get("n.sai2"));
    }

    @Test
    public void theFileMarksAreKeptAndOldVersionsSkipThem() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(10, "art.sai2", 1000);
        t.follow(files("art.sai2", 5000L, 777L));
        String text = t.serialize();
        assertTrue(text.contains("@file\tart.sai2\t5000\t777\n"));
        CanvasTime back = new CanvasTime();
        back.parse(text);
        back.follow(files("new.sai2", 5000L, 777L));
        assertEquals(1, back.get("new.sai2").strokes);
        for (String line : text.split("\n"))
            if (line.startsWith("@file")) assertEquals("shorter than a drawing's line", 4, line.split("\t").length);
    }
}
