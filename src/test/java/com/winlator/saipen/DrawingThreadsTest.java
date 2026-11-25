package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class DrawingThreadsTest {
    /** Threads' ticks so far, as tid, ticks, tid, ticks... */
    private static Map<Integer, Long> t(long... pairs) {
        Map<Integer, Long> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((int) pairs[i], pairs[i + 1]);
        return m;
    }

    /** `seconds` one-second looks with the pen down; each thread adds
     *  perSecond[i] ticks a second (100 = a whole core). */
    private static void draw(DrawingThreads d, Map<Integer, Long> state, int seconds, int[] tids, long[] perSecond) {
        for (int s = 0; s < seconds; s++) {
            for (int i = 0; i < tids.length; i++) state.merge(tids[i], perSecond[i], Long::sum);
            d.sample(new HashMap<>(state), 1000, true);
        }
    }

    @Test
    public void nothingIsPickedBeforeThreeSecondsOfDrawing() {
        DrawingThreads d = new DrawingThreads(2);
        Map<Integer, Long> s = t(1, 0, 2, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 2, new int[] {1, 2}, new long[] {80, 1});
        assertTrue(d.pick().isEmpty());
        draw(d, s, 1, new int[] {1, 2}, new long[] {80, 1});
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void onlySecondsWithThePenDownCount() {
        DrawingThreads d = new DrawingThreads(2);
        Map<Integer, Long> s = t(1, 0, 2, 0);
        d.sample(new HashMap<>(s), 0, false);
        /* thread 2 is busy only with the pen up (saving, say) */
        for (int i = 0; i < 10; i++) {
            s.merge(2, 90L, Long::sum);
            d.sample(new HashMap<>(s), 1000, false);
        }
        draw(d, s, 3, new int[] {1, 2}, new long[] {60, 0});
        assertEquals(3000, d.drawMs());
        assertEquals(0.6, d.load(1), 1e-9);
        assertEquals(0.0, d.load(2), 1e-9);
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void tabS6LitePoolFillsTheTwoFastCoresAndNoMore() {
        /* THE TRAP, as measured 2026-11-19: eight pool threads at about a
         * third of a core each and the main thread at a quarter, about three
         * cores in all. Two fast cores hold 1.8 cores' worth (ROOM): the
         * busiest go there, the rest keep every core. Pinning all nine would
         * crowd 3 cores of work onto 2. */
        DrawingThreads d = new DrawingThreads(2);
        int[] tids = {100, 101, 102, 103, 104, 105, 106, 107, 108};
        long[] per = {25, 33, 33, 33, 32, 32, 32, 31, 31};
        Map<Integer, Long> s = new HashMap<>();
        for (int tid : tids) s.put(tid, 0L);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 4, tids, per);
        List<Integer> pick = d.pick();
        assertEquals(Arrays.asList(101, 102, 103, 104, 105), pick);
        double sum = 0;
        for (int tid : pick) sum += d.load(tid);
        assertTrue("fits in the fast cores: " + sum, sum <= 2 * DrawingThreads.ROOM);
    }

    @Test
    public void theBusiestIsPickedEvenIfItAloneIsTooMuch() {
        /* one fast core, one thread at a whole core: still better there */
        DrawingThreads d = new DrawingThreads(1);
        Map<Integer, Long> s = t(1, 0, 2, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 3, new int[] {1, 2}, new long[] {100, 50});
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void idleThreadsAreNotPicked() {
        DrawingThreads d = new DrawingThreads(2);
        Map<Integer, Long> s = t(1, 0, 2, 0, 3, 0);
        d.sample(new HashMap<>(s), 0, false);
        /* 0.02 and 0.01 of a core: hardly working */
        draw(d, s, 5, new int[] {1, 2, 3}, new long[] {40, 2, 1});
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void smallPoolThreadsGetInWhileThereIsRoom() {
        /* the 10:31 session, 2026-11-19: main 0.19, one helper 0.10, a pool
         * of seven at 0.05 each. With a 0.10 limit the pool (half the work)
         * stayed on the slow cores with the fast ones nearly empty. */
        DrawingThreads d = new DrawingThreads(2);
        int[] tids = {28556, 28558, 28559, 28560, 28561, 28562, 28563, 28564, 28588};
        long[] per = {19, 10, 5, 5, 5, 5, 5, 5, 5};
        Map<Integer, Long> s = new HashMap<>();
        for (int tid : tids) s.put(tid, 0L);
        s.put(28570, 0L);                                   /* nearly idle */
        d.sample(new HashMap<>(s), 0, false);
        int[] all = Arrays.copyOf(tids, tids.length + 1);
        all[tids.length] = 28570;
        long[] perAll = Arrays.copyOf(per, per.length + 1);
        perAll[per.length] = 1;
        draw(d, s, 10, all, perAll);
        List<Integer> pick = d.pick();
        assertEquals(9, pick.size());
        assertEquals("busiest first", Integer.valueOf(28556), pick.get(0));
        assertTrue(!pick.contains(28570));
    }

    @Test
    public void aThreadAtTheLimitDoesNotGoInAndOut() {
        /* THE TRAP, second one: thread 28558 sat at the old 0.10 limit and
         * was pinned and let go four times in one session */
        DrawingThreads d = new DrawingThreads(2);
        Map<Integer, Long> s = t(1, 0, 2, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 10, new int[] {1, 2}, new long[] {50, 4});
        assertEquals(Arrays.asList(1, 2), d.pick());
        /* thread 2 slows to just under MIN_LOAD, then wobbles around it */
        for (int i = 0; i < 40; i++) {
            draw(d, s, 1, new int[] {1, 2}, new long[] {50, i % 2 == 0 ? 1 : 3});
            assertEquals("second " + i, Arrays.asList(1, 2), d.pick());
        }
        assertTrue(d.load(2) < DrawingThreads.MIN_LOAD);
        /* it goes only when it really stops working */
        draw(d, s, 200, new int[] {1, 2}, new long[] {50, 0});
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void aPickedThreadKeepsItsPlaceOverANewBusierOne() {
        /* one fast core, room 0.9: thread 1 (0.5) is in; thread 2 starts
         * later and reaches 0.6. Swapping would move both for little. */
        DrawingThreads d = new DrawingThreads(1);
        Map<Integer, Long> s = t(1, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 3, new int[] {1}, new long[] {50});
        assertEquals(Arrays.asList(1), d.pick());
        s.put(2, 0L);
        draw(d, s, 30, new int[] {1, 2}, new long[] {50, 66});
        assertTrue(d.load(2) > d.load(1));
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void aThreadThatGetsBusyLaterGetsIn() {
        DrawingThreads d = new DrawingThreads(2);
        Map<Integer, Long> s = t(1, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 3, new int[] {1}, new long[] {50});
        assertEquals(Arrays.asList(1), d.pick());
        /* SAI starts thread 2 after a while: its first second counts whole */
        s.put(2, 0L);
        draw(d, s, 6, new int[] {1, 2}, new long[] {50, 60});
        assertEquals(Arrays.asList(1, 2), d.pick());
    }

    @Test
    public void anEndedThreadIsForgotten() {
        /* its id may come back as another thread that did none of that work */
        DrawingThreads d = new DrawingThreads(2);
        Map<Integer, Long> s = t(1, 0, 2, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 3, new int[] {1, 2}, new long[] {50, 50});
        s.remove(2);
        d.sample(new HashMap<>(s), 1000, true);
        assertEquals(Arrays.asList(1), d.pick());
    }

    @Test
    public void theFirstLookOnlySetsTheStart() {
        /* ticks from before the session are not drawing time */
        DrawingThreads d = new DrawingThreads(2);
        d.sample(t(1, 5000), 1000, true);
        assertEquals(0, d.drawMs());
        assertEquals(0.0, d.load(1), 1e-9);
    }

    @Test
    public void noFastCoresNoPick() {
        DrawingThreads d = new DrawingThreads(0);
        Map<Integer, Long> s = t(1, 0);
        d.sample(new HashMap<>(s), 0, false);
        draw(d, s, 5, new int[] {1}, new long[] {90});
        assertTrue(d.pick().isEmpty());
    }

    @Test
    public void diffSaysWhatToPinAndWhatToLetGo() {
        Set<Integer> pinned = new HashSet<>(Arrays.asList(1, 2));
        List<Set<Integer>> c = DrawingThreads.diff(pinned, Arrays.asList(2, 3));
        assertEquals(new HashSet<>(Arrays.asList(3)), c.get(0));
        assertEquals(new HashSet<>(Arrays.asList(1)), c.get(1));
    }
}
