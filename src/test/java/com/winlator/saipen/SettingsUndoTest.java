package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SettingsUndoTest {
    @Test
    public void coversGesturesAndKeysButNotWhereTheyAre() {
        assertTrue(SettingsUndo.covers(Gestures.Input.TWO_TAP.pref()));
        assertTrue(SettingsUndo.covers(FloatingKeys.PREF_COLOR));
        assertTrue(SettingsUndo.covers(KeysOpacity.PREF));
        assertTrue(SettingsUndo.covers(Gestures.OLD_KEYS_HOLD_PREF));
        assertFalse(SettingsUndo.covers("saipen_keys_x"));
        assertFalse(SettingsUndo.covers("saipen_keys_exit_y"));
        assertFalse(SettingsUndo.covers("saipen_kbd_x"));
        assertFalse(SettingsUndo.covers("saipen_timelapse_on"));
        assertFalse(SettingsUndo.covers(null));
    }

    @Test
    public void putsBackWhatChangedAndRemovesWhatWasAdded() {
        Map<String, Object> was = new HashMap<>();
        was.put(FloatingKeys.PREF_COLOR, 0);
        was.put(Gestures.Input.TWO_TAP.pref(), "key:Ctrl+Z");
        was.put("saipen_keys_x", 0.5f);
        Map<String, Object> before = SettingsUndo.take(was);
        assertFalse("positions are not taken", before.containsKey("saipen_keys_x"));

        Map<String, Object> now = new HashMap<>(was);
        now.put(FloatingKeys.PREF_COLOR, 3);
        now.remove(Gestures.Input.TWO_TAP.pref());
        now.put(KeysOpacity.PREF, 40);
        now.put("saipen_keys_x", 0.9f);   /* dragged: stays */
        SettingsUndo.Back b = SettingsUndo.back(before, now);
        assertEquals(0, b.put.get(FloatingKeys.PREF_COLOR));
        assertEquals("key:Ctrl+Z", b.put.get(Gestures.Input.TWO_TAP.pref()));
        assertEquals(2, b.put.size());
        assertEquals(Collections.singletonList(KeysOpacity.PREF), b.remove);
    }

    @Test
    public void nothingChangedNothingToDo() {
        Map<String, Object> was = new HashMap<>();
        was.put(FloatingKeys.PREF_ON, true);
        assertTrue(SettingsUndo.back(SettingsUndo.take(was), was).isEmpty());
    }
}
