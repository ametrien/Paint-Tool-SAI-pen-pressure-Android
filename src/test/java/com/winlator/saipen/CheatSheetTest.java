package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CheatSheetTest {
    private static final long GIB = 1024L * 1024 * 1024;

    private static Gestures.Action[] standard() {
        return Gestures.actions(Collections.emptyMap());
    }

    private static List<String> names(List<CheatSheet.Row> rows) {
        List<String> l = new ArrayList<>();
        for (CheatSheet.Row r : rows) l.add(r.toString());
        return l;
    }

    @Test
    public void theStandardGesturesInOrderPenFirst() {
        assertEquals(java.util.Arrays.asList("PEN", "PEN_BUTTON", "PEN_HOVER_BUTTON", "ONE_FINGER", "TWO_FINGERS", "TWO_TAP",
                "THREE_TAP", "THREE_HOLD", "FOUR_TAP", "FOUR_HOLD"),
            names(CheatSheet.rows(standard(), true)));
    }

    @Test
    public void aGestureSetToNothingOrJustDrawingIsLeftOut() {
        Gestures.Action[] a = standard();
        a[Gestures.Input.TWO_TAP.ordinal()] = Gestures.Action.of(Gestures.Type.NONE);
        a[Gestures.Input.PEN_BUTTON.ordinal()] = Gestures.Action.of(Gestures.Type.DRAW);
        List<String> n = names(CheatSheet.rows(a, true));
        assertFalse(n.contains("TWO_TAP"));
        assertFalse(n.contains("PEN_BUTTON"));
        assertTrue("the menu cannot be changed", n.contains("FOUR_TAP"));
        assertTrue(n.contains("FOUR_HOLD"));
    }

    @Test
    public void aGestureSetToSomethingIsListedInItsPlace() {
        Gestures.Action[] a = standard();
        a[Gestures.Input.TWO_DOUBLE_TAP.ordinal()] = Gestures.Action.parse("key:Ctrl+Z");
        a[Gestures.Input.EDGE_RIGHT.ordinal()] = Gestures.Action.of(Gestures.Type.MENU);
        List<String> n = names(CheatSheet.rows(a, true));
        assertEquals(n.indexOf("TWO_TAP") + 1, n.indexOf("TWO_DOUBLE_TAP"));
        assertEquals(n.size() - 1, n.indexOf("EDGE_RIGHT"));
    }

    @Test
    public void theThreeFingerHoldOnlyWhileItShowsTheKeys() {
        assertTrue(names(CheatSheet.rows(standard(), true)).contains("THREE_HOLD"));
        assertFalse(names(CheatSheet.rows(standard(), false)).contains("THREE_HOLD"));
    }

    /** What Android reports is a little under the size on the box. */
    @Test
    public void memoryIsNamedAsTheDeviceIsSold() {
        assertEquals(4, CheatSheet.soldGb((long) (3.6 * GIB)));    // Galaxy Tab S6 Lite 4 GB
        assertEquals(6, CheatSheet.soldGb((long) (5.5 * GIB)));
        assertEquals(8, CheatSheet.soldGb((long) (7.3 * GIB)));
        assertEquals(12, CheatSheet.soldGb((long) (11.2 * GIB)));  // S26 Ultra
        assertEquals(16, CheatSheet.soldGb((long) (15.0 * GIB)));
        assertEquals(4, CheatSheet.soldGb(4 * GIB));
    }

    @Test
    public void theSpeedPartNamesTheMemoryOnlyUnder8Gb() {
        assertEquals(4, CheatSheet.lowRamGb((long) (3.6 * GIB)));
        assertEquals(6, CheatSheet.lowRamGb((long) (5.5 * GIB)));
        assertEquals("an 8 GB device reports about 7.3 GiB: enough", 0, CheatSheet.lowRamGb((long) (7.3 * GIB)));
        assertEquals(0, CheatSheet.lowRamGb(12 * GIB));
        assertEquals("unknown: the general text", 0, CheatSheet.lowRamGb(0));
    }

    @Test
    public void theCheatSheetIsNotSeenBeforeTheFirstOpen() {
        assertFalse(FirstRun.CHEAT_SHEET_SEEN);
    }
}
