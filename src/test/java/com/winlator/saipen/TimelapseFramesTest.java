package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TimelapseFramesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A frame file as the DLL writes it (timelapse_win.h tl_write_frame). */
    private File frame(File dir, long seq, int w, int h, long canvas, String name, int fill) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(TimelapseFrames.HEADER_BYTES + w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SAITLF2".getBytes(StandardCharsets.US_ASCII));
        b.position(8);
        b.putInt(w).putInt(h).putInt(w * 4).putInt(0).putLong(seq).putLong(1000 + seq).putLong(canvas);
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        b.position(48);
        b.put(nb);
        for (int i = TimelapseFrames.HEADER_BYTES; i < b.capacity(); i++) b.put(i, (byte) fill);
        File f = new File(dir, String.format("%08d.frame", seq));
        Files.write(f.toPath(), b.array());
        return f;
    }

    @Test
    public void readsTheHeaderTheTabletWrote() throws Exception {
        /* the first frame from the Galaxy Tab S6 Lite, 2026-11-19 */
        File f = frame(tmp.getRoot(), 1, 512, 512, 0x6c9e000L, "NewCanvas1", 0xff);
        TimelapseFrames.Header h = TimelapseFrames.readHeader(f);
        assertEquals(512, h.width);
        assertEquals(2048, h.stride);
        assertEquals(1, h.seq);
        assertEquals(0x6c9e000L, h.canvasId);
        assertEquals("NewCanvas1", h.name);
    }

    @Test
    public void halfWrittenAndForeignFilesAreSkipped() throws Exception {
        File dir = tmp.getRoot();
        File f = frame(dir, 1, 64, 64, 1, "A", 0);
        byte[] whole = Files.readAllBytes(f.toPath());
        Files.write(new File(dir, "00000002.frame").toPath(), java.util.Arrays.copyOf(whole, 500));
        Files.write(new File(dir, "00000003.frame").toPath(), new byte[200]);          /* no magic */
        frame(dir, 4, 64, 64, 1, "A", 0);
        Files.write(new File(dir, "00000005.tmp").toPath(), whole);                    /* being written */
        assertEquals(2, TimelapseFrames.list(dir).size());
        assertNull(TimelapseFrames.parse(new byte[10]));
    }

    @Test
    public void oneVideoPerCanvasInFrameOrder() throws Exception {
        File dir = tmp.getRoot();
        frame(dir, 3, 64, 64, 0xA, "Cat", 0);
        frame(dir, 1, 64, 64, 0xA, "Cat", 0);
        frame(dir, 2, 32, 32, 0xB, "Dog", 0);
        frame(dir, 4, 32, 32, 0xB, "Dog", 0);
        List<TimelapseFrames.Segment> s = TimelapseFrames.segments(TimelapseFrames.list(dir));
        assertEquals(2, s.size());
        assertEquals("Cat", s.get(0).name);
        assertEquals(1, s.get(0).frames.get(0).header.seq);
        assertEquals(3, s.get(0).frames.get(1).header.seq);
        assertEquals("Dog", s.get(1).name);
    }

    @Test
    public void aRenameDoesNotSplitAndTheLastNameWins() throws Exception {
        File dir = tmp.getRoot();
        frame(dir, 1, 64, 64, 0xA, "NewCanvas1", 0);
        frame(dir, 2, 64, 64, 0xA, "Portrait", 0);
        List<TimelapseFrames.Segment> s = TimelapseFrames.segments(TimelapseFrames.list(dir));
        assertEquals(1, s.size());
        assertEquals("Portrait", s.get(0).name);
    }

    @Test
    public void aResizedCanvasStartsANewFile() throws Exception {
        File dir = tmp.getRoot();
        frame(dir, 1, 64, 64, 0xA, "A", 0);
        frame(dir, 2, 64, 64, 0xA, "A", 0);
        frame(dir, 3, 101, 77, 0xA, "A", 0);
        List<TimelapseFrames.Segment> s = TimelapseFrames.segments(TimelapseFrames.list(dir));
        assertEquals(2, s.size());
        assertEquals(100, s.get(1).width);      /* even, rounded down */
        assertEquals(76, s.get(1).height);
    }

    @Test
    public void fileNamesAreSafe() {
        assertEquals("NewCanvas1 timelapse 2026-11-19 00-40.mp4",
                     TimelapseFrames.fileName("NewCanvas1", "2026-11-19 00-40", 1));
        assertEquals("ab timelapse x (2).mp4", TimelapseFrames.fileName("a/b?", "x", 2));
        assertEquals("SAI timelapse x.mp4", TimelapseFrames.fileName("  ", "x", 1));
    }

    @Test
    public void coloursBecomeVideoColours() {
        /* BT.601 limited range: white 235/128/128, black 16/128/128,
         * pure red 82/90/240, in BGRA order */
        int[][] cases = {{255, 255, 255, 235, 128, 128}, {0, 0, 0, 16, 128, 128}, {0, 0, 255, 82, 90, 240}};
        for (int[] c : cases) {
            byte[] px = new byte[2 * 2 * 4];
            for (int i = 0; i < 4; i++) {
                px[i * 4] = (byte) c[0];
                px[i * 4 + 1] = (byte) c[1];
                px[i * 4 + 2] = (byte) c[2];
                px[i * 4 + 3] = (byte) 255;
            }
            byte[] y = new byte[4], u = new byte[1], v = new byte[1];
            TimelapseFrames.bgraToYuv420(px, 0, 8, 2, 2, y, u, v);
            assertEquals(c[3], y[0] & 0xff);
            assertEquals(c[4], u[0] & 0xff);
            assertEquals(c[5], v[0] & 0xff);
        }
    }
}
