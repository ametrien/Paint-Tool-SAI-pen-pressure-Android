package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The video made while drawing (TimelapseLive), with a stand-in encoder. */
public class TimelapseLiveTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File raw(File dir, long seq, int w, int h, long canvas, String name) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(TimelapseFrames.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SAITLF2".getBytes(StandardCharsets.US_ASCII));
        b.position(8);
        b.putInt(w).putInt(h).putInt(w * 4).putInt(0).putLong(seq).putLong(1000 + seq).putLong(canvas);
        b.position(48);
        b.put(name.getBytes(StandardCharsets.UTF_8));
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i++) px[i] = (byte) (seq + i);
        byte[] all = new byte[TimelapseFrames.HEADER_BYTES + px.length];
        System.arraycopy(b.array(), 0, all, 0, TimelapseFrames.HEADER_BYTES);
        System.arraycopy(px, 0, all, TimelapseFrames.HEADER_BYTES, px.length);
        File f = new File(dir, String.format("%08d.frame", seq));
        Files.write(f.toPath(), all);
        return f;
    }

    /** `n` frames of one canvas, packed into a SAI start's folder. */
    private File session(int n, long canvas) throws IOException {
        File frames = tmp.newFolder("frames"), session = tmp.newFolder("2026-11-27 00-10-00");
        for (int i = 1; i <= n; i++) raw(frames, i, 8, 6, canvas, i < n ? "NewCanvas1" : "hand.sai2");
        TimelapsePack.packAll(frames, session);
        return session;
    }

    /** Records what it was asked to encode; writes a non-empty file. */
    private static final class Fake implements TimelapseLive.Encoder {
        final List<List<Long>> chunks = new ArrayList<>();
        boolean fail;

        @Override public void encode(List<TimelapseFrames.Frame> frames, int w, int h, File out) throws IOException {
            if (fail) throw new IOException("encoder busy");
            List<Long> seqs = new ArrayList<>();
            for (TimelapseFrames.Frame f : frames) seqs.add(f.header.seq);
            chunks.add(seqs);
            Files.write(out.toPath(), new byte[] {1, 2, 3});
        }
    }

    @Test
    public void fullChunksAreEncodedAndTheirFramesGo() throws Exception {
        File s = session(250, 0xABCL);
        Fake enc = new Fake();
        assertEquals(240, TimelapseLive.encodeReady(s, enc));
        assertEquals(2, enc.chunks.size());
        assertEquals(1L, (long) enc.chunks.get(0).get(0));
        assertEquals(240L, (long) enc.chunks.get(1).get(119));
        assertEquals(10, TimelapsePack.listSession(s).size());               // the rest waits
        File live = TimelapseLive.dirs(s).get(0);
        assertEquals(2, TimelapseLive.chunks(live).size());
        assertEquals(240, TimelapseLive.encodedFrames(live));
        assertEquals(240, TimelapseLive.encodedUpTo(live));
        assertTrue(TimelapseLive.first(live).isFile());
        assertEquals(1, TimelapseLive.frame(TimelapseLive.first(live), s.getName()).header.seq);
        assertEquals(240, TimelapseLive.frame(TimelapseLive.last(live), s.getName()).header.seq);
        assertEquals("NewCanvas1", TimelapseLive.name(live));
        assertEquals(240, TimelapseLive.pendingFrames(s.getParentFile()));
        /* a second pass with nothing new does nothing */
        assertEquals(0, TimelapseLive.encodeReady(s, enc));
    }

    @Test
    public void atLeastOneFrameStaysAfterTheChunks() throws Exception {
        File s = session(TimelapseLive.CHUNK, 7);
        assertEquals(0, TimelapseLive.encodeReady(s, new Fake()));          // exactly a chunk: wait for one more
        assertEquals(TimelapseLive.CHUNK, TimelapsePack.listSession(s).size());
    }

    @Test
    public void aBusyEncoderLosesNothing() throws Exception {
        File s = session(130, 7);
        Fake enc = new Fake();
        enc.fail = true;
        try {
            TimelapseLive.encodeReady(s, enc);
            fail();
        }
        catch (IOException expected) {
        }
        assertEquals(130, TimelapsePack.listSession(s).size());
        for (File live : TimelapseLive.dirs(s)) assertTrue(TimelapseLive.chunks(live).isEmpty());
    }

    @Test
    public void framesAChunkHoldsAreNotEncodedTwice() throws Exception {
        File s = session(130, 7);
        Fake enc = new Fake();
        TimelapseLive.encodeReady(s, enc);
        File live = TimelapseLive.dirs(s).get(0);
        /* the app died after the chunk, before deleting its frames: put two back */
        File frames = tmp.newFolder("again");
        raw(frames, 5, 8, 6, 7, "NewCanvas1");
        raw(frames, 120, 8, 6, 7, "NewCanvas1");
        TimelapsePack.packAll(frames, s);
        List<TimelapseFrames.Frame> rest = TimelapseLive.dropEncoded(TimelapsePack.listSession(s), live);
        assertEquals(10, rest.size());
        assertEquals(121, rest.get(0).header.seq);
        assertEquals(10, TimelapsePack.listSession(s).size());              // the two duplicates deleted
    }

    @Test
    public void removeTakesTheFolderAway() throws Exception {
        File s = session(130, 7);
        TimelapseLive.encodeReady(s, new Fake());
        File live = TimelapseLive.dirs(s).get(0);
        TimelapseLive.remove(live);
        assertFalse(live.exists());
        assertFalse(new File(s, TimelapseLive.DIR).exists());
    }
}
