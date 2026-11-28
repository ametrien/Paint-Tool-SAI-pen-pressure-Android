package com.winlator.saipen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cancel in the settings window over SAI (GesturesOverlay): what the window
 * can change is taken when it opens, and Cancel puts it back. Changes are in
 * use at once while the window is open (the keys are built again, the
 * gestures read again), so putting the values back undoes them on the screen
 * too.
 *
 * What the window can change: the gestures ("saipen_gesture_…") and the
 * on-screen keys ("saipen_keys_…": buttons, colour, opacity, Exit SAI's
 * look, the old three-finger switch). Not where the keys were dragged to:
 * moving them is no setting, and Cancel must not jump them back.
 *
 * No Android types: the JVM tests check what is put back.
 */
public final class SettingsUndo {
    private SettingsUndo() {}

    static final String[] PREFIXES = {Gestures.PREF_PREFIX, "saipen_keys_"};
    static final String[] NOT = {"saipen_keys_x", "saipen_keys_y", "saipen_keys_exit_x", "saipen_keys_exit_y"};

    /** Whether the window's Cancel covers this preference. */
    static boolean covers(String key) {
        if (key == null) return false;
        for (String n : NOT) if (n.equals(key)) return false;
        for (String p : PREFIXES) if (key.startsWith(p)) return true;
        return false;
    }

    /** The covered preferences now (the preferences' getAll()). */
    public static Map<String, Object> take(Map<String, ?> all) {
        Map<String, Object> m = new HashMap<>();
        if (all == null) return m;
        for (Map.Entry<String, ?> e : all.entrySet()) if (covers(e.getKey())) m.put(e.getKey(), e.getValue());
        return m;
    }

    /** What to do to get from `now` back to `before`: put these, remove those. */
    public static final class Back {
        public final Map<String, Object> put = new HashMap<>();
        public final List<String> remove = new ArrayList<>();

        public boolean isEmpty() {
            return put.isEmpty() && remove.isEmpty();
        }
    }

    public static Back back(Map<String, Object> before, Map<String, ?> nowAll) {
        Map<String, Object> now = take(nowAll);
        Back b = new Back();
        for (Map.Entry<String, Object> e : before.entrySet()) {
            if (!Objects.equals(now.get(e.getKey()), e.getValue())) b.put.put(e.getKey(), e.getValue());
        }
        for (String k : now.keySet()) if (!before.containsKey(k)) b.remove.add(k);
        return b;
    }
}
