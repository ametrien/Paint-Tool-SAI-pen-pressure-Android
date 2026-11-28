package com.winlator.saipen;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.List;

/**
 * Runs what each gesture is set to do (Gestures) over SAI. Made once per
 * SAI session from the preferences; TouchpadView asks it what a tap or a
 * pen touch does and calls run().
 *
 * Main thread only, like the touches.
 */
public final class GestureActions {
    private static final String TAG = "SaiPen";

    private final XServer xServer;
    private Gestures.Action[] actions;
    /* kept: SharedPreferences holds its listeners weakly */
    private final SharedPreferences.OnSharedPreferenceChangeListener reload;
    private Runnable onChanged;
    private final Runnable keyboard, menu;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** The one of this SAI session, for gestures not seen by TouchpadView:
     *  the three-finger hold (GesturesOverlay). */
    private static GestureActions current;

    static GestureActions current() {
        return current;
    }

    /** keyboard: shows the tablet's keyboard; menu: opens Winlator's menu. */
    public GestureActions(XServer xServer, SharedPreferences prefs, Runnable keyboard, Runnable menu) {
        this.xServer = xServer;
        this.actions = Gestures.actions(prefs.getAll());
        this.keyboard = keyboard;
        this.menu = menu;
        /* changed over SAI (GesturesOverlay): in use at once, no restart */
        reload = (p, key) -> {
            if (key != null && !key.startsWith(Gestures.PREF_PREFIX)) return;
            actions = Gestures.actions(p.getAll());
            if (onChanged != null) onChanged.run();
        };
        prefs.registerOnSharedPreferenceChangeListener(reload);
        current = this;
    }

    /** Runs after the gestures changed (TouchpadView: the edges' share). */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public Gestures.Action action(Gestures.Input in) {
        return actions[in.ordinal()];
    }

    /** Whether the input does anything: a recognizer is only needed then. */
    public boolean isSet(Gestures.Input in) {
        return action(in).type != Gestures.Type.NONE;
    }

    /**
     * Runs the input's action, a click at (x, y) on the X screen.
     * leftOk, rightOk: whether that mouse button may be clicked now
     * (TouchpadView's own checks).
     */
    public void run(Gestures.Input in, int x, int y, boolean leftOk, boolean rightOk) {
        Gestures.Action a = action(in);
        Log.i(TAG, "gesture " + in.id + ": " + a);
        switch (a.type) {
            case LEFT_CLICK:
            case DRAW:
                if (leftOk) click(Pointer.Button.BUTTON_LEFT, x, y);
                break;
            case RIGHT_CLICK:
                if (rightOk) click(Pointer.Button.BUTTON_RIGHT, x, y);
                break;
            case KEYBOARD:
                if (keyboard != null) keyboard.run();
                break;
            case PAD_KEYBOARD:
                PadKeyboard.toggle();
                break;
            case ON_SCREEN_KEYS:
                FloatingKeys.toggle();
                break;
            case MENU:
                if (menu != null) menu.run();
                break;
            case KEY:
                KeyPresses.pressOnce(xServer, KeyPresses.keys(a.keys), null);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void click(Pointer.Button b, int x, int y) {
        if (xServer.pointer.isButtonPressed(b)) return;
        xServer.injectPointerMove(x, y);
        xServer.injectPointerButtonPress(b);
        ui.postDelayed(() -> xServer.injectPointerButtonRelease(b), 30);
    }

    /** The pen's side button, eraser end and button in the air, as set. */
    public PenInput.Buttons penButtons() {
        return new PenInput.Buttons() {
            /* a key held while the pen touches, let go when it lifts */
            private List<XKeycode> held;

            @Override
            public int down(boolean sideButton, boolean eraser) {
                Gestures.Input in = sideButton ? Gestures.Input.PEN_BUTTON
                    : eraser ? Gestures.Input.PEN_ERASER : null;
                if (in == null) return PenInput.BUTTON_LEFT;
                Gestures.Action a = action(in);
                Log.i(TAG, "pen touch with " + (sideButton ? "the side button" : "the eraser end") + ": " + a);
                int touch = Gestures.penTouch(a);
                if (a.type == Gestures.Type.KEY && a.keys.held) {
                    held = KeyPresses.keys(a.keys);
                    KeyPresses.pressAll(xServer, held);
                }
                else if (touch == Gestures.PEN_NOTHING) {
                    /* the action runs now, the touch draws nothing */
                    run(in, 0, 0, false, false);
                }
                return touch == Gestures.PEN_RIGHT ? PenInput.BUTTON_RIGHT
                    : touch == Gestures.PEN_NOTHING ? PenInput.NO_BUTTON : PenInput.BUTTON_LEFT;
            }

            @Override
            public void up() {
                if (held == null) return;
                KeyPresses.releaseAll(xServer, held);
                held = null;
            }

            @Override
            public void hoverButton(int x, int y) {
                run(Gestures.Input.PEN_HOVER_BUTTON, x, y, true, true);
            }
        };
    }
}
