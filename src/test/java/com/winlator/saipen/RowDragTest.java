package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RowDragTest {
    @Test
    public void landsPastHalfANeighbour() {
        assertEquals(2, RowDrag.target(2, 0, 100, 5));
        assertEquals("less than half: stays", 2, RowDrag.target(2, 49, 100, 5));
        assertEquals(3, RowDrag.target(2, 51, 100, 5));
        assertEquals(1, RowDrag.target(2, -60, 100, 5));
        assertEquals(4, RowDrag.target(2, 260, 100, 5));
    }

    @Test
    public void staysInsideTheList() {
        assertEquals(4, RowDrag.target(2, 10_000, 100, 5));
        assertEquals(0, RowDrag.target(2, -10_000, 100, 5));
        assertEquals("not laid out yet: no move", 2, RowDrag.target(2, 300, 0, 5));
    }

    @Test
    public void theOthersStepAside() {
        /* row 1 dragged down over row 3: rows 2 and 3 go up one */
        assertEquals(0, RowDrag.shift(0, 1, 3, 40));
        assertEquals(-40, RowDrag.shift(2, 1, 3, 40));
        assertEquals(-40, RowDrag.shift(3, 1, 3, 40));
        assertEquals(0, RowDrag.shift(4, 1, 3, 40));
        /* row 3 dragged up over row 1: rows 1 and 2 go down one */
        assertEquals(40, RowDrag.shift(1, 3, 1, 40));
        assertEquals(40, RowDrag.shift(2, 3, 1, 40));
        assertEquals(0, RowDrag.shift(0, 3, 1, 40));
        assertEquals(0, RowDrag.shift(2, 2, 2, 40));
    }

    @Test
    public void moves() {
        List<String> l = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
        RowDrag.move(l, 0, 2);
        assertEquals("[b, c, a, d]", l.toString());
        RowDrag.move(l, 3, 0);
        assertEquals("[d, b, c, a]", l.toString());
        RowDrag.move(l, 1, 9);
        assertEquals("out of range: unchanged", "[d, b, c, a]", l.toString());
    }
}
