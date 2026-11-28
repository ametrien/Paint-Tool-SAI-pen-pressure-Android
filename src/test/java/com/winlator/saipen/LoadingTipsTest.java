package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoadingTipsTest {
    private static Gestures.Action[] standard() {
        return Gestures.actions(Collections.emptyMap());
    }

    private static List<String> names(List<LoadingTips.Tip> tips) {
        List<String> l = new ArrayList<>();
        for (LoadingTips.Tip t : tips) l.add(t.toString());
        return l;
    }

    private static void set(Gestures.Action[] a, Gestures.Input in, Gestures.Type t) {
        a[in.ordinal()] = Gestures.Action.of(t);
    }

    @Test
    public void theStandardGesturesGiveTheTwoThreeFingerTips() {
        assertEquals(Arrays.asList("KEYBOARD:THREE_TAP", "KEYS_HOLD", "SETTINGS"),
            names(LoadingTips.tips(standard(), true)));
    }

    @Test
    public void theKeyboardTipFollowsTheGestureItWasMovedTo() {
        Gestures.Action[] a = standard();
        set(a, Gestures.Input.THREE_TAP, Gestures.Type.NONE);
        set(a, Gestures.Input.TWO_DOUBLE_TAP, Gestures.Type.KEYBOARD);
        assertEquals(Arrays.asList("KEYBOARD:TWO_DOUBLE_TAP", "KEYS_HOLD", "SETTINGS"),
            names(LoadingTips.tips(a, true)));
    }

    @Test
    public void aFingerGestureIsNamedBeforeAPenOne() {
        Gestures.Action[] a = standard();
        set(a, Gestures.Input.THREE_TAP, Gestures.Type.NONE);
        set(a, Gestures.Input.PEN_HOVER_BUTTON, Gestures.Type.KEYBOARD);
        set(a, Gestures.Input.EDGE_RIGHT, Gestures.Type.KEYBOARD);
        assertEquals("KEYBOARD:EDGE_RIGHT", LoadingTips.tips(a, true).get(0).toString());
    }

    @Test
    public void noKeyboardGestureNoKeyboardTip() {
        Gestures.Action[] a = standard();
        set(a, Gestures.Input.THREE_TAP, Gestures.Type.RIGHT_CLICK);
        assertEquals(Arrays.asList("KEYS_HOLD", "SETTINGS"), names(LoadingTips.tips(a, true)));
        assertNull(LoadingTips.first(a, Gestures.Type.KEYBOARD));
    }

    @Test
    public void theHoldOffNamesAnotherKeysGestureIfThereIsOne() {
        Gestures.Action[] a = standard();
        assertEquals(Arrays.asList("KEYBOARD:THREE_TAP", "SETTINGS"), names(LoadingTips.tips(a, false)));
        set(a, Gestures.Input.LONG_PRESS, Gestures.Type.ON_SCREEN_KEYS);
        assertEquals(Arrays.asList("KEYBOARD:THREE_TAP", "KEYS:LONG_PRESS", "SETTINGS"), names(LoadingTips.tips(a, false)));
    }

    @Test
    public void withNothingElseLeftTheFourFingerHold() {
        Gestures.Action[] a = standard();
        set(a, Gestures.Input.THREE_TAP, Gestures.Type.NONE);
        assertEquals(Arrays.asList("CHANGE_GESTURES", "SETTINGS"), names(LoadingTips.tips(a, false)));
    }
}
