package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Piece;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Linking and unlinking by hand, and what has lost its drawing file. */
public class DrawingLinksTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aLinkByHandReservesBoth() {
        DrawingLinks l = new DrawingLinks();
        l.link("portrait.sai2", "d1");
        assertEquals("d1", l.linkOf("portrait.sai2"));
        assertEquals("portrait.sai2", l.fileOf("d1"));
        assertTrue(l.mayPair("portrait.sai2", "d1"));
        assertFalse("the timelapse is that file's", l.mayPair("other.sai2", "d1"));
        assertFalse("the file has its timelapse", l.mayPair("portrait.sai2", "d2"));
        assertTrue(l.mayPair("other.sai2", "d2"));
        l.link("other.sai2", "d1");
        assertNull("one file a timelapse", l.linkOf("portrait.sai2"));
        assertEquals("d1", l.linkOf("other.sai2"));
    }

    @Test
    public void anUnlinkHoldsUntilLinkedByHandAgain() {
        DrawingLinks l = new DrawingLinks();
        l.unlink("a.sai2", "d1");
        assertFalse(l.mayPair("a.sai2", "d1"));
        assertTrue(l.mayPair("b.sai2", "d1"));
        l.link("a.sai2", "d1");
        assertTrue(l.mayPair("a.sai2", "d1"));
        l.unlink("a.sai2", "d1");
        assertNull(l.linkOf("a.sai2"));
        assertFalse(l.mayPair("a.sai2", "d1"));
    }

    @Test
    public void forgetAndMoveFile() {
        DrawingLinks l = new DrawingLinks();
        l.link("a.sai2", "d1");
        l.unlink("a.sai2", "d2");
        l.moveFile("a.sai2", "comics/b.sai2");
        assertEquals("d1", l.linkOf("comics/b.sai2"));
        assertNull(l.linkOf("a.sai2"));
        assertFalse(l.mayPair("comics/b.sai2", "d2"));
        assertTrue(l.mayPair("a.sai2", "d2"));
        l.forget("d1");
        assertTrue(l.isForgotten("d1"));
        assertNull(l.linkOf("comics/b.sai2"));
        l.link("x.sai2", "d1");
        assertFalse("linked again: not forgotten", l.isForgotten("d1"));
    }

    @Test
    public void keptInAFile() throws IOException {
        DrawingLinks l = new DrawingLinks();
        l.link("рисунки/кот.sai2", "d1");
        l.unlink("a.sai2", "d2");
        l.forget("d3");
        File f = new File(tmp.getRoot(), DrawingLinks.FILE);
        assertTrue(l.save(f));
        DrawingLinks back = DrawingLinks.load(f);
        assertEquals("d1", back.linkOf("рисунки/кот.sai2"));
        assertTrue(back.isUnlinked("a.sai2", "d2"));
        assertTrue(back.isForgotten("d3"));
        DrawingLinks none = DrawingLinks.load(new File(tmp.getRoot(), "missing.txt"));
        assertNull(none.linkOf("a.sai2"));
        DrawingLinks junk = new DrawingLinks();
        junk.parse("link\t\td1\nunlink\ta.sai2\nforget\t\nnonsense\n");
        assertNull(junk.fileOf("d1"));
        assertTrue(junk.mayPair("a.sai2", "d1"));
    }

    /* ---- DrawingIndex.lost ---- */

    static CanvasTime.Canvas time(int strokes, long last) {
        CanvasTime.Canvas c = new CanvasTime.Canvas();
        c.strokes = strokes;
        c.seconds = strokes * 10;
        c.last = last;
        return c;
    }

    static Drawing video(DrawingLibrary lib, String title, long at) {
        return lib.addDrawing(title, new Piece(title + ".mp4", at, 10, 100, 100, null, null));
    }

    @Test
    public void whatLostItsFile() {
        Map<String, CanvasTime.Canvas> spent = new HashMap<>();
        spent.put("here.sai2", time(5, 100));
        spent.put("comics/gone.sai2", time(7, 300));
        spent.put(":NewCanvas1", time(3, 400));            /* never saved: no file to lose */
        spent.put("empty.sai2", time(0, 500));
        DrawingLibrary lib = new DrawingLibrary();
        Drawing gone = video(lib, "gone", 200);
        Drawing alone = video(lib, "IMG_1", 600);
        List<DrawingIndex.Lost> out = DrawingIndex.lost(new HashSet<>(Collections.singletonList("here.sai2")),
                                                        spent, Arrays.asList(gone, alone));
        assertEquals(2, out.size());
        assertEquals("IMG_1", out.get(0).name);          /* newest first */
        assertSame(alone, out.get(0).video);
        assertNull(out.get(0).time);
        assertEquals("gone.sai2", out.get(1).name);
        assertEquals("comics/gone.sai2", out.get(1).timeKey);
        assertSame("its timelapse goes with its time, by name", gone, out.get(1).video);
    }

    @Test
    public void nothingLostWhenEveryFileIsThere() {
        Map<String, CanvasTime.Canvas> spent = new HashMap<>();
        spent.put("a.sai2", time(5, 100));
        assertTrue(DrawingIndex.lost(new HashSet<>(Collections.singletonList("a.sai2")), spent,
                                     Collections.emptyList()).isEmpty());
    }

    @Test
    public void moveAndRemoveTime() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(10, "old.sai2", 1000);
        t.penDown(20); t.penUp(21, "new.sai2", 2000);
        assertTrue(t.move("old.sai2", "new.sai2"));
        assertNull(t.get("old.sai2"));
        assertEquals("added to the time it had", 2, t.get("new.sai2").strokes);
        assertFalse(t.move("old.sai2", "x.sai2"));
        assertTrue(t.remove("new.sai2"));
        assertNull(t.get("new.sai2"));
        assertFalse(t.remove("new.sai2"));
    }
}
