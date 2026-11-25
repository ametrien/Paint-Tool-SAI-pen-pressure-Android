package com.winlator.saipen;

import android.os.Handler;
import android.os.Looper;

import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Key presses that SAI Pen makes for SAI: the on-screen keys (FloatingKeys)
 * and the gestures (GestureActions). Each X key counts its presses, so one
 * of them letting go of Ctrl does not let go of a Ctrl the other holds
 * (a held Ctrl button, and a gesture's Ctrl+Z).
 *
 * Main thread only, like the touches that make them.
 */
public final class KeyPresses {
    private static final Map<XKeycode, Integer> DOWN = new HashMap<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private KeyPresses() {}

    public static void press(XServer xServer, XKeycode k) {
        Integer n = DOWN.get(k);
        int was = n == null ? 0 : n;
        DOWN.put(k, was + 1);
        if (was == 0) xServer.injectKeyPress(k);
    }

    public static void release(XServer xServer, XKeycode k) {
        Integer n = DOWN.get(k);
        int was = n == null ? 0 : n;
        if (was <= 0) return;
        DOWN.put(k, was - 1);
        if (was == 1) xServer.injectKeyRelease(k);
    }

    /** The X keys of a Shortcut, modifiers first. */
    public static List<XKeycode> keys(Shortcut s) {
        List<XKeycode> l = new ArrayList<>();
        for (String name : s.xKeys()) l.add(XKeycode.valueOf(name));
        return l;
    }

    /** Down in order, modifiers first. */
    public static void pressAll(XServer xServer, List<XKeycode> keys) {
        for (XKeycode k : keys) press(xServer, k);
    }

    /** Up in the other order. */
    public static void releaseAll(XServer xServer, List<XKeycode> keys) {
        for (int i = keys.size() - 1; i >= 0; i--) release(xServer, keys.get(i));
    }

    /**
     * One press of a combination: modifiers and key down, a moment apart so
     * Wine sees each, then up in the other order. `done` (may be null) runs
     * when all are up.
     */
    public static void pressOnce(XServer xServer, List<XKeycode> keys, Runnable done) {
        long t = 0;
        for (XKeycode k : keys) {
            UI.postDelayed(() -> press(xServer, k), t);
            t += 20;
        }
        t += 30;
        for (int i = keys.size() - 1; i >= 0; i--) {
            final XKeycode k = keys.get(i);
            UI.postDelayed(() -> release(xServer, k), t);
            t += 20;
        }
        if (done != null) UI.postDelayed(done, t);
    }
}
