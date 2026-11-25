package com.winlator.saipen;

/**
 * The held buttons of the floating keys (FloatingKeys, Shortcut.held), for
 * what SAI wants a key held for while the pen works: a perspective ruler's
 * handles move with Ctrl and turn with Alt, Space is the hand tool. Without
 * a Bluetooth keyboard there was no way to hold any of them (the tablet's
 * keyboard has no Ctrl or Alt that stays down).
 *
 * Each button works two ways, told apart by how long the finger stays:
 * - a TAP (shorter than HOLD_MS) latches the key: it stays down, the button
 *   lit, while the pen draws as many strokes as it likes, until the next tap
 *   on the same button lets it go;
 * - a HOLD (HOLD_MS or longer) is an ordinary key press: down while the
 *   finger is on the button, up when it lifts. One hand holds Ctrl, the
 *   other draws.
 *
 * The key goes down at once on touch in both cases, so a hold is never late.
 *
 * No Android types: the JVM tests drive it with plain times.
 */
public final class ModifierKeys {
    /** A touch on a button shorter than this is a tap (latch), longer a hold. */
    public static final long HOLD_MS = 350;

    /** What the X server is told. */
    public interface Sink {
        void press(int key);
        void release(int key);
    }

    private enum State { UP, PRESSING, LATCHED, UNLATCHING }

    private final Sink sink;
    private final State[] states;
    private final long[] downAt;

    /** `count` buttons, numbered from 0. */
    public ModifierKeys(int count, Sink sink) {
        this.sink = sink;
        states = new State[count];
        downAt = new long[count];
        for (int i = 0; i < count; i++) states[i] = State.UP;
    }

    /** A finger lands on the key's button. */
    public void down(int key, long nowMs) {
        switch (states[key]) {
            case UP:
                sink.press(key);
                states[key] = State.PRESSING;
                downAt[key] = nowMs;
                break;
            case LATCHED:
                /* the tap that lets it go; the key stays down until the lift */
                states[key] = State.UNLATCHING;
                break;
            default:
                break;
        }
    }

    /** The finger lifts from the key's button. */
    public void up(int key, long nowMs) {
        switch (states[key]) {
            case PRESSING:
                if (nowMs - downAt[key] < HOLD_MS) {
                    states[key] = State.LATCHED;
                } else {
                    sink.release(key);
                    states[key] = State.UP;
                }
                break;
            case UNLATCHING:
                sink.release(key);
                states[key] = State.UP;
                break;
            default:
                break;
        }
    }

    /** The touch was taken away (the buttons closed, the session ended):
     *  every key that is down goes up, so none stays stuck in SAI. */
    public void releaseAll() {
        for (int i = 0; i < states.length; i++) {
            if (states[i] != State.UP) {
                sink.release(i);
                states[i] = State.UP;
            }
        }
    }

    /** The key is down in SAI (its button is drawn lit). */
    public boolean isDown(int key) {
        return states[key] != State.UP;
    }

    /** The key stays down after the finger lifted. */
    public boolean isLatched(int key) {
        return states[key] == State.LATCHED || states[key] == State.UNLATCHING;
    }

    /** Some key is down: the closed dot shows it, so a latched Ctrl is not forgotten. */
    public boolean anyDown() {
        for (int i = 0; i < states.length; i++) if (states[i] != State.UP) return true;
        return false;
    }
}
