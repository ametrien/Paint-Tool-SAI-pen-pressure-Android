package com.winlator.saipen;

import java.util.List;

/**
 * Dragging a row of a list up or down by its handle (the on-screen keys'
 * buttons in FloatingKeysSettings, the owner 2026-11-28): where the row
 * lands and how far each other row steps aside to make room. Rows are all
 * one height, `step`.
 *
 * No Android types: the JVM tests check it.
 */
final class RowDrag {
    private RowDrag() {}

    /** The place the row dragged from `from` lands on after the finger moved
     *  `dy` pixels (down is positive), in a list of `n` rows: past half of a
     *  neighbour is past it. */
    static int target(int from, float dy, int step, int n) {
        if (n <= 0 || step <= 0) return from;
        int to = from + Math.round(dy / step);
        return Math.max(0, Math.min(n - 1, to));
    }

    /** How far row `i` moves out of the way while the row from `from` is
     *  over `to`: one step up, one step down or none. */
    static int shift(int i, int from, int to, int step) {
        if (from < to && i > from && i <= to) return -step;
        if (to < from && i >= to && i < from) return step;
        return 0;
    }

    /** The item at `from` moved to `to`, the others in between closing up. */
    static <T> void move(List<T> l, int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= l.size() || to >= l.size()) return;
        l.add(to, l.remove(from));
    }
}
