package com.winlator.saipen;

/**
 * What the S Pen does to the Windows cursor and to our wintab32.dll.
 *
 * Official Winlator sends a pen down the finger path: the cursor moves by
 * the finger's delta like a laptop touchpad, and a held, moving pen never
 * holds a mouse button, so SAI cannot draw a stroke. Here the pen is a pen:
 *
 * - the cursor goes exactly under the tip (absolute), while touching AND
 *   while hovering;
 * - touching holds the left button until the pen lifts; with the S Pen's
 *   side button held at touch-down it holds the right button instead;
 * - every sample, including the ones Android batched into one event
 *   (the "historical" samples), goes to the DLL with its pressure, so a
 *   quick stroke is not cut down to the screen's frame rate;
 * - the Windows cursor, though, moves once per event, to its newest sample.
 *   Each cursor move travels Winlator's X server, Wine, wineserver and SAI's
 *   message loop, all but the first emulated, while SAI takes the stroke's
 *   points from the DLL anyway. On the Galaxy Tab S6 Lite (2026-11-18) a
 *   move per sample meant 25 to 30 thousand context switches a second while
 *   drawing, with five busy threads queueing for the two fast cores.
 *
 * No Android types here, so the JVM unit tests run it on the Mac.
 * TouchpadView wraps a MotionEvent in {@link Event} and calls in.
 */
public final class PenInput {
    /* MotionEvent action values (android.view.MotionEvent), copied so this
     * class needs no Android. */
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final int ACTION_HOVER_ENTER = 9;
    public static final int ACTION_HOVER_EXIT = 10;

    /* MotionEvent tool types */
    public static final int TOOL_TYPE_FINGER = 1;
    public static final int TOOL_TYPE_STYLUS = 2;
    public static final int TOOL_TYPE_MOUSE = 3;
    public static final int TOOL_TYPE_ERASER = 4;

    /* X pointer buttons, as com.winlator.xserver.Pointer.Button numbers them */
    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_RIGHT = 3;

    /** One pen pointer of one MotionEvent. pos 0..historySize()-1 are the
     *  batched samples, oldest first; pos == historySize() is the current one. */
    public interface Event {
        int historySize();
        float x(int pos);
        float y(int pos);
        float pressure(int pos);
        /** event time in microseconds, monotonic clock */
        long timeUs(int pos);
        boolean sideButton();
    }

    public interface Sink {
        void movePointer(int x, int y);
        void pressButton(int button);
        void releaseButton(int button);
        /** p: 0 = hovering, 1..pmax touching; sx, sy: X screen point */
        void sample(int p, float sx, float sy, long evUs);
        void setInRange(boolean inRange);
    }

    /** View coordinates to X screen coordinates (XForm in Winlator). */
    public interface Transform {
        void apply(float vx, float vy, float[] out);
    }

    private final Sink sink;
    private final Transform transform;
    private final int pmax;
    private final int screenW, screenH;
    private final float[] pt = new float[2];
    private int heldButton = 0;          /* 0 = pen not touching */
    private float lastX, lastY;

    public PenInput(Sink sink, Transform transform, int pmax, int screenW, int screenH) {
        this.sink = sink;
        this.transform = transform;
        this.pmax = pmax;
        this.screenW = screenW;
        this.screenH = screenH;
    }

    public static boolean isPenTool(int toolType) {
        return toolType == TOOL_TYPE_STYLUS || toolType == TOOL_TYPE_ERASER;
    }

    public boolean isTouching() {
        return heldButton != 0;
    }

    /**
     * The action as the PEN sees it, in an event that may also carry fingers
     * (a palm or the other hand on the glass).
     *
     * THE TRAP: Android reports a finger landing while the pen draws as
     * ACTION_POINTER_DOWN of the whole event. For the pen that is just
     * another move; taking it as a pen-down would press the button a second
     * time, and its pointer-up would end the stroke under the pen.
     */
    public static int penAction(int actionMasked, int actionIndex, int penIndex) {
        switch (actionMasked) {
            case ACTION_POINTER_DOWN:
                return actionIndex == penIndex ? ACTION_DOWN : ACTION_MOVE;
            case ACTION_POINTER_UP:
                return actionIndex == penIndex ? ACTION_UP : ACTION_MOVE;
            default:
                return actionMasked;
        }
    }

    /** A touch event of the pen (penAction() already applied). */
    public void onTouch(int action, Event e) {
        switch (action) {
            case ACTION_DOWN:
                down(e);
                break;
            case ACTION_MOVE:
                /* a move without a down (the down went elsewhere, e.g. it
                 * landed while the view was disabled): start the stroke here */
                if (heldButton == 0) {
                    down(e);
                    break;
                }
                for (int pos = 0; pos < e.historySize(); pos++) touchingAt(e, pos, false);
                touchingAt(e, e.historySize(), true);
                break;
            case ACTION_UP:
                if (heldButton == 0) break;
                for (int pos = 0; pos < e.historySize(); pos++) touchingAt(e, pos, false);
                place(e, e.historySize());
                lift(e.timeUs(e.historySize()));
                break;
            case ACTION_CANCEL:
                /* the system took the touch back (palm rejection): end the
                 * stroke where it was, never leave the button held */
                if (heldButton != 0) lift(e.timeUs(e.historySize()));
                break;
            default:
                break;
        }
    }

    /** A hover event of the pen (it is near the glass, not touching). */
    public void onHover(int action, Event e) {
        if (heldButton != 0) return;
        switch (action) {
            case ACTION_HOVER_ENTER:
            case ACTION_HOVER_MOVE:
                for (int pos = 0; pos <= e.historySize(); pos++) {
                    if (pos == e.historySize()) place(e, pos);
                    else locate(e, pos);
                    sink.sample(0, lastX, lastY, e.timeUs(pos));
                }
                sink.setInRange(true);
                break;
            case ACTION_HOVER_EXIT:
                sink.setInRange(false);
                break;
            default:
                break;
        }
    }

    private void down(Event e) {
        int pos = e.historySize();
        place(e, pos);
        heldButton = e.sideButton() ? BUTTON_RIGHT : BUTTON_LEFT;
        /* the sample goes out BEFORE the button: on the Mac, too, our
         * pen-down datagram reaches the DLL before Wine's click does */
        sink.sample(pressureAt(e, pos), lastX, lastY, e.timeUs(pos));
        sink.pressButton(heldButton);
    }

    /** moveCursor: only for the event's newest sample (see the class note) */
    private void touchingAt(Event e, int pos, boolean moveCursor) {
        if (moveCursor) place(e, pos);
        else locate(e, pos);
        sink.sample(pressureAt(e, pos), lastX, lastY, e.timeUs(pos));
    }

    private void lift(long evUs) {
        sink.sample(0, lastX, lastY, evUs);
        sink.releaseButton(heldButton);
        heldButton = 0;
    }

    /** With the side button held the touch is a right click, not a stroke:
     *  report it as hovering so SAI does not ink. */
    private int pressureAt(Event e, int pos) {
        if (heldButton == BUTTON_RIGHT) return 0;
        return PenWire.pressureLevel(e.pressure(pos), true, pmax);
    }

    /** The sample's X screen point into lastX/lastY, cursor untouched. */
    private void locate(Event e, int pos) {
        transform.apply(e.x(pos), e.y(pos), pt);
        lastX = pt[0];
        lastY = pt[1];
    }

    /** locate() and put the Windows cursor there. */
    private void place(Event e, int pos) {
        locate(e, pos);
        /* THE TRAP: Winlator's XServer.injectPointerMove() does not clamp,
         * and a pen over the letterbox bars maps outside the X screen. */
        int cx = Math.max(0, Math.min((int) pt[0], screenW - 1));
        int cy = Math.max(0, Math.min((int) pt[1], screenH - 1));
        sink.movePointer(cx, cy);
    }
}
