package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ShortcutTest {
    @Test
    public void readsWhatPeopleWrite() {
        Shortcut s = Shortcut.parse("Ctrl+T");
        assertEquals(Shortcut.CTRL, s.modifiers);
        assertEquals("T", s.key);
        assertFalse(s.held);
        assertEquals(Arrays.asList("KEY_CTRL_L", "KEY_T"), s.xKeys());
        assertEquals(Shortcut.CTRL | Shortcut.SHIFT, Shortcut.parse(" shift + ctrl + z ").modifiers);
        assertEquals(Arrays.asList("KEY_ENTER"), Shortcut.parse("enter").xKeys());
    }

    @Test
    public void heldButtons() {
        Shortcut ctrl = Shortcut.parse("hold Ctrl");
        assertTrue(ctrl.held);
        assertNull(ctrl.key);
        assertEquals("Ctrl", ctrl.label());
        assertEquals("hold Ctrl", ctrl.toString());
        assertEquals(Arrays.asList("KEY_CTRL_L"), ctrl.xKeys());
        assertTrue("Space held: SAI's hand tool", Shortcut.parse("HOLD space").held);
        assertTrue("a modifier alone is always held", Shortcut.parse("Alt").held);
        assertEquals("Ctrl+Shift", new Shortcut(Shortcut.CTRL | Shortcut.SHIFT, null, false).label());
    }

    @Test
    public void writesModifiersInOneOrder() {
        assertEquals("Ctrl+Alt+Shift+F5", Shortcut.parse("Shift+Alt+Ctrl+f5").label());
        assertEquals("Ctrl+-", Shortcut.parse("Ctrl+-").label());
        assertEquals("Ctrl+PageUp", Shortcut.parse("ctrl+pageup").label());
    }

    @Test
    public void notAButton() {
        assertNull(Shortcut.parse(""));
        assertNull(Shortcut.parse("hold "));
        assertNull(Shortcut.parse(null));
        assertNull("no such key", Shortcut.parse("Ctrl+Banana"));
        assertNull("a key before the end", Shortcut.parse("T+Ctrl"));
    }

    @Test
    public void everyKeyGoesRoundTheList() {
        for (String k : Shortcut.KEYS) {
            for (boolean held : new boolean[]{false, true}) {
                Shortcut s = new Shortcut(Shortcut.CTRL, k, held);
                assertEquals(k, s, Shortcut.parse(s.toString()));
                assertTrue(k, s.xKeys().get(1).startsWith("KEY_"));
            }
        }
    }

    @Test
    public void theDefaults() {
        List<Shortcut> d = Shortcut.parseList(Shortcut.DEFAULTS);
        assertEquals("[hold Ctrl, Ctrl+Z, Ctrl+Y, Ctrl+T, Ctrl+A, Ctrl+D, Ctrl+U, Enter]", d.toString());
        assertTrue(d.get(0).held);
        assertTrue(d.size() <= Shortcut.MAX);
    }

    @Test
    public void theListSkipsJunkAndRepeatedKeysAndStopsAtMax() {
        List<Shortcut> l = Shortcut.parseList(
            "hold Ctrl\nnonsense\nctrl\nCtrl+A\nhold ctrl+a\n\nCtrl+B\nCtrl+C\nCtrl+D\nCtrl+E\nCtrl+F\nCtrl+G\nCtrl+H");
        assertEquals(Shortcut.MAX, l.size());
        assertEquals("[hold Ctrl, Ctrl+A, Ctrl+B, Ctrl+C, Ctrl+D, Ctrl+E, Ctrl+F, Ctrl+G]", l.toString());
        assertEquals(l, Shortcut.parseList(Shortcut.joinList(l)));
        assertTrue(Shortcut.parseList(null).isEmpty());
    }
}
