package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class GlassTimesTest {
    /** Android's side, played by the test: times per frame id, in ns. */
    private static final class FakeEgl implements GlassTimes.Egl {
        boolean has = true;
        long next = 100;
        final Map<Long, long[]> times = new HashMap<>();
        final List<Long> forgotten = new ArrayList<>();

        @Override public boolean enable() { return has; }
        @Override public long nextFrameId() { return next++; }

        @Override
        public boolean times(long id, long[] out) {
            if (forgotten.contains(id)) return false;
            long[] t = times.get(id);
            if (t == null) return true;          /* out stays pending */
            out[0] = t[0];
            out[1] = t[1];
            return true;
        }

        void known(long id, long gpuMs, long shownMs) {
            times.put(id, new long[] {gpuMs < 0 ? gpuMs : gpuMs * 1_000_000L + 999_999,
                                      shownMs < 0 ? shownMs : shownMs * 1_000_000L + 999_999});
        }
    }

    private final List<String> got = new ArrayList<>();
    private final GlassTimes.Out out = (start, end, drawing, gpu, shown) ->
        got.add(start + "-" + end + (drawing ? " drawing" : "") + " gpu " + gpu + " shown " + shown);

    @Test
    public void aFrameIsReportedOnceAndroidKnowsItInMs() {
        FakeEgl egl = new FakeEgl();
        GlassTimes g = new GlassTimes(egl);
        assertTrue(g.surfaceReady());
        g.onFrame(1000, 1004, true, out);        /* id 100 */
        assertTrue(got.isEmpty());
        g.onFrame(1008, 1011, true, out);        /* id 101: 100 still pending */
        assertTrue(got.isEmpty());
        egl.known(100, 1009, 1030);
        g.onFrame(1016, 1019, false, out);
        assertEquals("[1000-1004 drawing gpu 1009 shown 1030]", got.toString());
    }

    @Test
    public void framesComeOutInOrderAndANewerKnownOneWaitsForAnOlderPending() {
        FakeEgl egl = new FakeEgl();
        GlassTimes g = new GlassTimes(egl);
        g.surfaceReady();
        g.onFrame(1000, 1004, true, out);        /* 100 */
        g.onFrame(1008, 1012, true, out);        /* 101 */
        egl.known(101, 1013, 1040);
        g.onFrame(1016, 1020, true, out);
        assertTrue(got.toString(), got.isEmpty());
        egl.known(100, 1005, -1);                /* replaced before the display took it */
        g.onFrame(1024, 1028, true, out);
        assertEquals("[1000-1004 drawing gpu 1005 shown -1, 1008-1012 drawing gpu 1013 shown 1040]", got.toString());
    }

    @Test
    public void aFrameAndroidForgotIsGivenUpAndDoesNotBlockTheNext() {
        FakeEgl egl = new FakeEgl();
        GlassTimes g = new GlassTimes(egl);
        g.surfaceReady();
        g.onFrame(1000, 1004, true, out);        /* 100 */
        g.onFrame(1008, 1012, true, out);        /* 101 */
        egl.forgotten.add(100L);
        egl.known(101, -1, 1040);
        g.onFrame(1016, 1020, true, out);
        assertEquals("[1008-1012 drawing gpu -1 shown 1040]", got.toString());
        assertEquals(1, g.givenUp());
    }

    @Test
    public void tooManyWaitingGivesUpTheOldest() {
        FakeEgl egl = new FakeEgl();
        GlassTimes g = new GlassTimes(egl);
        g.surfaceReady();
        for (int i = 0; i < GlassTimes.MAX_WAITING + 3; i++) g.onFrame(i * 10, i * 10 + 4, false, out);
        assertEquals(3, g.givenUp());
        egl.known(103, 40, 60);                  /* the oldest still waiting */
        g.onFrame(5000, 5004, false, out);
        assertEquals("[30-34 gpu 40 shown 60]", got.toString());
    }

    @Test
    public void withoutTimestampsNothingHappens() {
        FakeEgl egl = new FakeEgl();
        egl.has = false;
        GlassTimes g = new GlassTimes(egl);
        assertFalse(g.surfaceReady());
        g.onFrame(1000, 1004, true, out);
        assertEquals(100, egl.next);             /* not even asked for an id */
        assertFalse(g.isOn());
    }

    @Test
    public void aNewSurfaceGivesUpTheOldOnesFrames() {
        FakeEgl egl = new FakeEgl();
        GlassTimes g = new GlassTimes(egl);
        g.surfaceReady();
        g.onFrame(1000, 1004, true, out);
        g.onFrame(1008, 1012, true, out);
        g.surfaceReady();
        assertEquals(2, g.givenUp());
        egl.known(100, 1005, 1030);
        g.onFrame(1016, 1020, true, out);
        assertTrue(got.isEmpty());
    }
}
