package com.winlator.saipen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks SAI's drawing threads: the threads that work while the pen is down.
 * SaiCores puts them on the fast cores (FastCores).
 *
 * Once a second it is given every SAI thread's processor time so far (Linux
 * thread id to clock ticks, 1/100 s) and whether the pen touched in that
 * second. Only the seconds with the pen down count. After WARM_UP_MS of
 * them, the busiest threads are picked, busiest first, while
 *   - a thread does at least MIN_LOAD of a core while drawing, and
 *   - all picked threads together fit in ROOM of the fast cores.
 * The busiest one is always picked, even if it alone is too much.
 * The pick is made again every second, over all drawing time so far, so
 * a thread that only gets busy later still gets in.
 *
 * Small threads count too: in the 10:31 session (2026-11-19) half of SAI's
 * drawing work was a pool of seven threads at about 0.05 of a core each,
 * and a limit of 0.10 left them all on the slow cores while the fast ones
 * had room. MIN_LOAD only keeps out threads that hardly work at all.
 *
 * THE TRAP, second one: a thread right at a limit went in and out every
 * few seconds (thread 28558 at 0.10, four times in that session), each
 * time a taskset and a move between cores. So a picked thread stays picked
 * until it falls well under the limit (KEEP_LOAD), and picked threads get
 * their room on the fast cores before new ones are looked at.
 *
 * THE TRAP: SAI's work while drawing is spread over a pool of threads (on
 * the Tab S6 Lite, 2026-11-19: eight threads each at about a third of a
 * core, about three cores in all). Putting ALL of them on two fast cores
 * would crowd them, and could make drawing slower than leaving them where
 * they were. So the pick stops when the fast cores are full; the threads
 * left out keep every core.
 *
 * No Android types: the JVM tests feed it numbers.
 */
public final class DrawingThreads {
    /** Pen-down time to watch before the first pick. */
    public static final long WARM_UP_MS = 3000;
    /** A thread must do this share of one core while drawing to be picked. */
    static final double MIN_LOAD = 0.03;
    /** A picked thread stays picked down to this share of one core. */
    static final double KEEP_LOAD = 0.015;
    /** The picked threads may fill this share of the fast cores. */
    static final double ROOM = 0.9;
    /** Linux counts processor time in 1/100 s. */
    private static final int MS_PER_TICK = 10;

    private final int fastCores;
    private final Map<Integer, Long> last = new HashMap<>();
    private final Map<Integer, Long> drawTicks = new HashMap<>();
    private long drawMs = 0;
    private final Set<Integer> picked = new HashSet<>();

    public DrawingThreads(int fastCores) {
        this.fastCores = fastCores;
    }

    /**
     * One second's look. ticks: each SAI thread's processor time so far.
     * elapsedMs: since the last look. penDown: the pen touched in between.
     */
    public void sample(Map<Integer, Long> ticks, long elapsedMs, boolean penDown) {
        if (penDown && !last.isEmpty() && elapsedMs > 0) {
            drawMs += elapsedMs;
            for (Map.Entry<Integer, Long> e : ticks.entrySet()) {
                Long before = last.get(e.getKey());
                /* a thread new since the last look: all its time is in this second */
                long d = e.getValue() - (before == null ? 0 : before);
                if (d > 0) drawTicks.merge(e.getKey(), d, Long::sum);
            }
        }
        last.clear();
        last.putAll(ticks);
        /* a thread that ended is gone for good: its id may come back as another */
        drawTicks.keySet().retainAll(ticks.keySet());
    }

    public long drawMs() {
        return drawMs;
    }

    /** Share of one core `tid` did while drawing (0.33: a third of a core). */
    public double load(int tid) {
        if (drawMs <= 0) return 0;
        Long t = drawTicks.get(tid);
        return t == null ? 0 : t * (double) MS_PER_TICK / drawMs;
    }

    /** The drawing threads, busiest first; empty before WARM_UP_MS. */
    public List<Integer> pick() {
        List<Integer> out = new ArrayList<>();
        if (drawMs < WARM_UP_MS || fastCores <= 0) return out;
        List<Integer> all = new ArrayList<>(drawTicks.keySet());
        /* busiest first; equal ones by thread id, so a pick does not flip */
        all.sort((a, b) -> {
            int c = Long.compare(drawTicks.get(b), drawTicks.get(a));
            return c != 0 ? c : Integer.compare(a, b);
        });
        double used = 0, room = fastCores * ROOM;
        /* first the threads picked before, then new ones, busiest first */
        for (int pass = 0; pass < 2; pass++) {
            for (int tid : all) {
                boolean was = picked.contains(tid);
                if (was != (pass == 0)) continue;
                double l = load(tid);
                if (l < (was ? KEEP_LOAD : MIN_LOAD)) continue;
                if (!out.isEmpty() && used + l > room) continue;
                out.add(tid);
                used += l;
            }
        }
        /* busiest first, as the log and the tests expect */
        out.sort((a, b) -> {
            int c = Long.compare(drawTicks.get(b), drawTicks.get(a));
            return c != 0 ? c : Integer.compare(a, b);
        });
        picked.clear();
        picked.addAll(out);
        return out;
    }

    /** What changes from `pinned` to `pick`: {to pin, to let go}. */
    public static List<Set<Integer>> diff(Set<Integer> pinned, List<Integer> pick) {
        Set<Integer> add = new HashSet<>(pick);
        add.removeAll(pinned);
        Set<Integer> drop = new HashSet<>(pinned);
        drop.removeAll(pick);
        List<Set<Integer>> out = new ArrayList<>();
        out.add(add);
        out.add(drop);
        return out;
    }
}
