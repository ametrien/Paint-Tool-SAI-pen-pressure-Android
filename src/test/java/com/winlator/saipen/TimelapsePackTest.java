package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The frames that were lost on 2026-11-23 (PLAN-TIMELAPSE-0.4.31.md): a
 * 25 minute drawing kept 79 frames and lost its sketch (the 512 MB budget of
 * 4 MB frames), and a second SAI start wrote over the first one's frames
 * (the DLL numbers them from 1 again). These tests are those days, in files.
 */
public class TimelapsePackTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /* ---- frames as the DLL writes them (timelapse_win.h tl_write_frame) ---- */

    private static byte[] header(long seq, int w, int h, long canvas, String name) {
        ByteBuffer b = ByteBuffer.allocate(TimelapseFrames.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SAITLF2".getBytes(StandardCharsets.US_ASCII));
        b.position(8);
        b.putInt(w).putInt(h).putInt(w * 4).putInt(0).putLong(seq).putLong(1000 + seq).putLong(canvas);
        b.position(48);
        b.put(name.getBytes(StandardCharsets.UTF_8));
        return b.array();
    }

    private static File raw(File dir, long seq, int w, int h, long canvas, String name, byte[] px) throws IOException {
        byte[] all = new byte[TimelapseFrames.HEADER_BYTES + px.length];
        System.arraycopy(header(seq, w, h, canvas, name), 0, all, 0, TimelapseFrames.HEADER_BYTES);
        System.arraycopy(px, 0, all, TimelapseFrames.HEADER_BYTES, px.length);
        File f = new File(dir, String.format("%08d.frame", seq));
        Files.write(f.toPath(), all);
        return f;
    }

    /** White paper, BGRA, alpha 255. */
    private static byte[] paper(int w, int h) {
        byte[] px = new byte[w * h * 4];
        Arrays.fill(px, (byte) 0xff);
        return px;
    }

    /** A black stroke along row `y`, from x0 to x1. */
    private static byte[] stroke(byte[] px, int w, int y, int x0, int x1) {
        byte[] out = px.clone();
        for (int dy = -2; dy <= 2; dy++)
            for (int x = x0; x < x1; x++) {
                int p = ((y + dy) * w + x) * 4;
                out[p] = out[p + 1] = out[p + 2] = 0;
            }
        return out;
    }

    private static byte[] pixels(TimelapseFrames.Frame f) throws IOException {
        byte[] into = new byte[f.header.stride * f.header.height];
        TimelapsePack.readPixels(f, into);
        return into;
    }

    private File dir(String name) {
        File d = new File(tmp.getRoot(), name);
        d.mkdirs();
        return d;
    }

    /* ---- packing loses nothing ---- */

    @Test
    public void packedPixelsComeBackByteForByte() throws Exception {
        Random r = new Random(7);
        int w = 37, h = 23;                  /* odd sizes: no row lines up */
        byte[] px = new byte[w * h * 4];
        r.nextBytes(px);
        byte[] packed = TimelapsePack.packPixels(px, w * 4, h);
        byte[] back = new byte[px.length];
        TimelapsePack.unpackPixels(packed, 0, packed.length, back, w * 4, h);
        assertArrayEquals(px, back);
    }

    @Test
    public void aFrameOnDiskComesBackByteForByteAndTheRawFileGoes() throws Exception {
        File frames = dir("frames"), session = dir("packed/s1");
        byte[] px = stroke(paper(64, 48), 64, 20, 5, 60);
        File f = raw(frames, 1, 64, 48, 0xA, "NewCanvas1", px);
        File p = TimelapsePack.packOne(TimelapseFrames.list(frames).get(0), session);
        assertFalse("the raw frame is deleted once packed", f.exists());
        List<TimelapseFrames.Frame> l = TimelapsePack.listSession(session);
        assertEquals(1, l.size());
        assertEquals(p, l.get(0).file);
        assertEquals("NewCanvas1", l.get(0).header.name);
        assertEquals(0xA, l.get(0).header.canvasId);
        assertEquals("s1", l.get(0).session);
        assertArrayEquals(px, pixels(l.get(0)));
    }

    /** The 4 MB frame of a 1024 x 1024 canvas, as most of a drawing is:
     *  paper and a few lines. */
    @Test
    public void aDrawingPacksToAFractionOfItsSize() throws Exception {
        int w = 1024, h = 1024;
        byte[] px = paper(w, h);
        for (int i = 0; i < 40; i++) px = stroke(px, w, 20 + i * 24, 100 + i * 3, 900 - i * 5);
        byte[] packed = TimelapsePack.packPixels(px, w * 4, h);
        assertTrue("packed " + packed.length + " bytes of " + px.length, packed.length < px.length / 50);
    }

    @Test
    public void aDamagedPackedFrameIsAnErrorNotAPicture() throws Exception {
        byte[] px = stroke(paper(32, 32), 32, 10, 2, 30);
        byte[] packed = TimelapsePack.packPixels(px, 128, 32);
        byte[] cut = Arrays.copyOf(packed, packed.length / 2);
        try {
            TimelapsePack.unpackPixels(cut, 0, cut.length, new byte[px.length], 128, 32);
            fail("a cut packed frame must not read as pixels");
        }
        catch (IOException expected) {}
    }

    /* ---- the DLL's folder ---- */

    @Test
    public void onlyWholeFramesArePackedAndTheDllsTmpStays() throws Exception {
        File frames = dir("frames"), session = dir("packed/s1");
        File whole = raw(frames, 1, 16, 16, 1, "A", paper(16, 16));
        byte[] bytes = Files.readAllBytes(whole.toPath());
        Files.write(new File(frames, "00000002.frame").toPath(), Arrays.copyOf(bytes, 500));   /* half written */
        Files.write(new File(frames, "00000003.tmp").toPath(), bytes);                         /* being written */
        TimelapsePack.Result r = TimelapsePack.packAll(frames, session);
        assertEquals(1, r.frames);
        assertTrue(new File(frames, "00000002.frame").exists());
        assertTrue(new File(frames, "00000003.tmp").exists());
        assertEquals(1, TimelapsePack.count(dir("packed")));
    }

    @Test
    public void aPackedFrameIsNeverReplaced() throws Exception {
        File frames = dir("frames"), session = dir("packed/s1");
        byte[] first = stroke(paper(16, 16), 16, 5, 1, 15);
        raw(frames, 1, 16, 16, 1, "A", first);
        TimelapsePack.packAll(frames, session);
        byte[] second = stroke(paper(16, 16), 16, 10, 1, 15);
        raw(frames, 1, 16, 16, 1, "A", second);              /* the same number again */
        TimelapsePack.packAll(frames, session);
        List<TimelapseFrames.Frame> l = TimelapsePack.listSession(session);
        assertEquals(2, l.size());
        List<byte[]> got = new ArrayList<>();
        for (TimelapseFrames.Frame f : l) got.add(pixels(f));
        assertTrue(Arrays.equals(got.get(0), first) || Arrays.equals(got.get(1), first));
        assertTrue(Arrays.equals(got.get(0), second) || Arrays.equals(got.get(1), second));
    }

    /**
     * 2026-11-23, 18:55: SAI was started twice without the video button
     * between. The DLL numbered the second start's frames 1, 2, 3 again,
     * over the first start's, and SAI gave the new canvas the same address:
     * one video, frames of two drawings, the first one's gone.
     */
    @Test
    public void twoSaiStartsKeepEveryFrameAndMakeTwoVideos() throws Exception {
        File frames = dir("frames"), packed = dir("packed");
        File s1 = TimelapsePack.newSessionDir(packed, new Date(1_000_000_000_000L));
        File s2 = TimelapsePack.newSessionDir(packed, new Date(1_000_000_600_000L));
        byte[] sketch = stroke(paper(32, 32), 32, 8, 2, 30);
        byte[] colour = stroke(paper(32, 32), 32, 20, 2, 30);
        for (int seq = 1; seq <= 3; seq++) raw(frames, seq, 32, 32, 0x6c9e000L, "NewCanvas1", sketch);
        TimelapsePack.packAll(frames, s1);
        for (int seq = 1; seq <= 3; seq++) raw(frames, seq, 32, 32, 0x6c9e000L, "NewCanvas1", colour);
        TimelapsePack.packAll(frames, s2);

        List<TimelapseFrames.Frame> all = TimelapsePack.list(packed);
        assertEquals(6, all.size());
        List<TimelapseFrames.Segment> seg = TimelapseFrames.segments(all);
        assertEquals("one video per SAI start, though the canvas address is the same", 2, seg.size());
        assertEquals(s1.getName(), seg.get(0).session);
        for (TimelapseFrames.Frame f : seg.get(0).frames) assertArrayEquals(sketch, pixels(f));
        for (TimelapseFrames.Frame f : seg.get(1).frames) assertArrayEquals(colour, pixels(f));
    }

    @Test
    public void sessionFoldersSortInTimeAndNeverCollide() throws Exception {
        File packed = dir("packed");
        Date t = new Date(1_000_000_000_000L);
        File a = TimelapsePack.newSessionDir(packed, t);
        a.mkdirs();
        File b = TimelapsePack.newSessionDir(packed, t);
        assertNotEquals(a, b);
        b.mkdirs();
        File c = TimelapsePack.newSessionDir(packed, new Date(t.getTime() + 60_000));
        c.mkdirs();
        List<File> s = TimelapsePack.sessions(packed);
        assertEquals(Arrays.asList(a, b, c), s);
        assertTrue(a.getName().matches("\\d{4}-\\d\\d-\\d\\d \\d\\d-\\d\\d-\\d\\d"));
    }

    @Test
    public void emptySessionsAndCutPacksAreCleanedUp() throws Exception {
        File packed = dir("packed");
        File empty = dir("packed/a"), cut = dir("packed/b"), kept = dir("packed/c");
        Files.write(new File(cut, "00000001.ztmp").toPath(), new byte[10]);
        File frames = dir("frames");
        raw(frames, 1, 8, 8, 1, "A", paper(8, 8));
        TimelapsePack.packAll(frames, kept);
        TimelapsePack.removeEmptySessions(packed);
        assertFalse(empty.exists());
        assertFalse(cut.exists());
        assertTrue(kept.exists());
    }

    /* ---- the budget ---- */

    private File packedSession(File packed, String name, int frames) throws Exception {
        File raws = dir("raw-" + name), s = new File(packed, name);
        /* written newest first: the folder's listing order must not matter
         * (the DLL thinned in whatever order Wine listed, PLAN P5) */
        for (int seq = frames; seq >= 1; seq--) {
            byte[] px = new byte[64 * 64 * 4];
            new Random(seq * 31L + name.hashCode()).nextBytes(px);   /* packs badly: real bytes */
            raw(raws, seq, 64, 64, 1, name, px);
        }
        TimelapsePack.packAll(raws, s);
        return s;
    }

    private static List<Long> seqs(File session) {
        List<Long> out = new ArrayList<>();
        for (TimelapseFrames.Frame f : TimelapsePack.listSession(session)) out.add(f.header.seq);
        return out;
    }

    @Test
    public void underBudgetNothingGoes() throws Exception {
        File packed = dir("packed");
        File s = packedSession(packed, "s1", 9);
        assertEquals(0, TimelapsePack.enforceBudget(packed, TimelapsePack.bytes(packed)));
        assertEquals(9, seqs(s).size());
    }

    @Test
    public void overBudgetEverySecondFrameGoesInOrderKeepingTheFirstAndTheLast() throws Exception {
        File packed = dir("packed");
        File s = packedSession(packed, "s1", 9);
        long one = TimelapsePack.bytes(packed) / 9;
        TimelapsePack.enforceBudget(packed, one * 6);
        assertEquals(Arrays.asList(1L, 3L, 5L, 7L, 9L), seqs(s));
    }

    @Test
    public void theBiggestSessionIsThinnedNotTheOther() throws Exception {
        File packed = dir("packed");
        File small = packedSession(packed, "a", 4);
        File big = packedSession(packed, "b", 12);
        long one = TimelapsePack.bytes(packed) / 16;
        TimelapsePack.enforceBudget(packed, one * 11);
        assertEquals(4, seqs(small).size());
        List<Long> b = seqs(big);
        assertEquals(1L, (long) b.get(0));
        assertEquals(12L, (long) b.get(b.size() - 1));
        assertTrue(b.size() < 12);
    }

    @Test
    public void aBudgetTooSmallStopsAtTheFirstAndLastFrame() throws Exception {
        File packed = dir("packed");
        File s = packedSession(packed, "s1", 9);
        TimelapsePack.enforceBudget(packed, 1);
        assertEquals(Arrays.asList(1L, 9L), seqs(s));
    }

    /* ---- nothing drawn: no video ---- */

    private TimelapseFrames.Segment segment(byte[]... frames) throws Exception {
        File raws = dir("seg-raw"), s = dir("packed/seg");
        for (File f : s.listFiles()) f.delete();
        for (int i = 0; i < frames.length; i++) raw(raws, i + 1, 32, 32, 1, "Portrait", frames[i]);
        TimelapsePack.packAll(raws, s);
        List<TimelapseFrames.Segment> l = TimelapseFrames.segments(TimelapsePack.listSession(s));
        assertEquals(1, l.size());
        return l.get(0);
    }

    @Test
    public void aFileOnlyOpenedAndLookedAtMakesNoVideo() throws Exception {
        byte[] asOpened = stroke(paper(32, 32), 32, 10, 3, 29);
        assertTrue(TimelapseFrames.nothingDrawn(segment(asOpened), TimelapsePack::readPixels));
    }

    @Test
    public void aStrokeAndItsUndoMakeNoVideo() throws Exception {
        byte[] asOpened = stroke(paper(32, 32), 32, 10, 3, 29);
        byte[] withStroke = stroke(asOpened, 32, 20, 3, 29);
        assertTrue(TimelapseFrames.nothingDrawn(segment(asOpened, withStroke, asOpened), TimelapsePack::readPixels));
    }

    @Test
    public void realDrawingMakesAVideo() throws Exception {
        byte[] a = stroke(paper(32, 32), 32, 10, 3, 29);
        byte[] b = stroke(a, 32, 20, 3, 29);
        assertFalse(TimelapseFrames.nothingDrawn(segment(a, b), TimelapsePack::readPixels));
        /* one pixel is a change too */
        byte[] c = a.clone();
        c[4 * 32 * 5 + 8] = 0x10;
        assertFalse(TimelapseFrames.nothingDrawn(segment(a, a, c), TimelapsePack::readPixels));
    }

    /* ---- names ---- */

    @Test
    public void aSessionKnowsWhenItsSaiStartBegan() throws Exception {
        TimelapseFrames.Segment s = segment(paper(32, 32));
        TimelapseFrames.Segment named = new TimelapseFrames.Segment("2026-11-23 19-24-05 (2)", 1, "Portrait", 32, 32);
        assertEquals(new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT)
                         .parse("2026-11-23 19-24-05").getTime(), TimelapseVideo.startOf(named));
        assertNull(TimelapseFrames.parse(new byte[3]));
        assertEquals("seg", s.session);
    }

    /* ---- "Discard the frames, no video" ---- */

    @Test
    public void discardLeavesNothingForAVideo() throws IOException {
        File root = tmp.newFolder("container");
        File frames = TimelapseVideo.framesDir(root);
        assertTrue(frames.mkdirs());
        File session = TimelapsePack.newSessionDir(TimelapsePack.packedDir(root), new Date());
        assertTrue(session.mkdirs());
        raw(frames, 1, 4, 3, 7, "NewCanvas1", paper(4, 3));
        TimelapsePack.packAll(frames, session);
        raw(frames, 2, 4, 3, 7, "NewCanvas1", paper(4, 3));
        Files.write(new File(frames, "00000003.tmp").toPath(), new byte[10]);
        assertEquals(2, TimelapseVideo.countAll(root));

        assertEquals(0, TimelapseVideo.discardAll(root));
        assertEquals(0, TimelapseVideo.countAll(root));
        assertEquals(0, frames.listFiles().length);
        assertTrue(TimelapsePack.sessions(TimelapsePack.packedDir(root)).isEmpty());
    }

    @Test
    public void discardWithNoFramesIsNothingToDo() throws IOException {
        assertEquals(0, TimelapseVideo.discardAll(tmp.newFolder("empty")));
    }
}
