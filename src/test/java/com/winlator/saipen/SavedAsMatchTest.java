package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

public class SavedAsMatchTest {
    /** A w x h BGRA picture: a dark diagonal band on white, shifted by `shift`. */
    private static DrawingLibrary.Signature look(int w, int h, int shift, boolean noise) {
        byte[] px = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            boolean ink = Math.abs((x + shift) % w - y * w / h) < w / 8;
            int v = ink ? 30 : 245;
            if (noise) v = Math.max(0, Math.min(255, v + ((x * 7 + y * 13) % 9) - 4));
            px[i] = px[i + 1] = px[i + 2] = (byte) v;
            px[i + 3] = (byte) 255;
        }
        return DrawingLibrary.Signature.of(px, w, h, w * 4);
    }

    @Test
    public void theFileThatLooksLikeTheEndWins() {
        DrawingLibrary.Signature closing = look(512, 512, 0, false);
        String name = SavedAsMatch.pick(closing, Arrays.asList(
            new SavedAsMatch.Candidate("other.sai2", look(256, 256, 128, false)),
            new SavedAsMatch.Candidate("pb.sai2", look(256, 256, 0, true))));   // SAI's small picture, a bit noisy
        assertEquals("pb.sai2", name);
    }

    @Test
    public void nothingAlikeNoName() {
        assertNull(SavedAsMatch.pick(look(512, 512, 0, false),
            Arrays.asList(new SavedAsMatch.Candidate("other.sai2", look(256, 256, 256 / 2, false)))));
        assertNull(SavedAsMatch.pick(look(512, 512, 0, false), Arrays.asList()));
        assertNull(SavedAsMatch.pick(null, Arrays.asList(new SavedAsMatch.Candidate("x", look(64, 64, 0, false)))));
    }
}
