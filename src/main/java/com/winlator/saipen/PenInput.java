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

    /**
     * Test only, no switch in the app: ignore the pen's hover, so an S Pen
     * behaves like a pen that has none (issue #1, Ugee UT2). From the Mac,
     * before starting SAI:
     *     adb shell setprop debug.saipen.test_no_hover 1   (0 to turn off)
     */
    public static final String PROP_TEST_NO_HOVER = "debug.saipen.test_no_hover";

    /**
     * Speed test only, no switch in the app: while the pen touches, move the
     * Windows cursor at most once per this many ms (0, unset: once per event,
     * as always). Every sample still goes to the DLL, and the cursor still
     * goes under the tip at touch-down and lift. Fewer moves = less for
     * Wine and emulated SAI to handle while drawing; whether SAI draws the
     * same with them is what the test is for. Read when SAI starts:
     *     adb shell setprop debug.saipen.cursor_ms 33   (0 to turn off)
     */
    public static final String PROP_CURSOR_MS = "debug.saipen.cursor_ms";

    /** A number property for a speed test: def when unset or not a number, clamped to 0..max. */
    public static int intProperty(String name, int def, int max) {
        String v = systemProperty(name).trim();
        if (v.isEmpty()) return def;
        try {
            return Math.max(0, Math.min(max, Integer.parseInt(v)));
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

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
        /** The pen's eraser end (TOOL_TYPE_ERASER), on pens that have one. */
        default boolean eraser() { return false; }
    }

    /**
     * What a touch does with the side button held or the eraser end, and
     * the side button pressed in the air (Gestures, set on SAI Pen's
     * screen). By default: the side button is a right click, the eraser end
     * draws, the button in the air does nothing.
     */
    public interface Buttons {
        /** At touch-down: BUTTON_LEFT (draw), BUTTON_RIGHT, or NO_BUTTON
         *  (the touch draws nothing: its action ran here). */
        int down(boolean sideButton, boolean eraser);
        /** The touch that down() started is over (a held key goes up). */
        void up();
        /** The side button went down while the pen hovers at (x, y). */
        void hoverButton(int x, int y);
    }

    /** Buttons.down(): the touch holds no mouse button and draws nothing. */
    public static final int NO_BUTTON = -1;

    public static final Buttons DEFAULT_BUTTONS = new Buttons() {
        @Override public int down(boolean sideButton, boolean eraser) {
            return sideButton ? BUTTON_RIGHT : BUTTON_LEFT;
        }
        @Override public void up() {}
        @Override public void hoverButton(int x, int y) {}
    };

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
    private int heldButton = 0;          /* 0 = pen not touching, NO_BUTTON = touching, no button */
    private Buttons buttons = DEFAULT_BUTTONS;
    private boolean hoverSide = false;   /* the side button was down at the last hover */
    private float lastX, lastY;
    private boolean hoveredSinceLift = false;
    private boolean ignoreHover = false;
    private long cursorMinUs = 0;        /* PROP_CURSOR_MS; 0: a move per event */
    private long cursorAtUs = Long.MIN_VALUE / 2;

    public PenInput(Sink sink, Transform transform, int pmax, int screenW, int screenH) {
        this.sink = sink;
        this.transform = transform;
        this.pmax = pmax;
        this.screenW = screenW;
        this.screenH = screenH;
        setCursorInterval(intProperty(PROP_CURSOR_MS, 0, 1000));
    }

    /** PROP_CURSOR_MS: while touching, at most one cursor move per ms (0: one per event). */
    public void setCursorInterval(int ms) {
        cursorMinUs = ms * 1000L;
    }

    /** An Android system property, "" when unset or unreadable. Reflection,
     *  so this class still needs no Android to compile in the JVM tests. */
    public static String systemProperty(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, name);
            return v == null ? "" : v.toString();
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    public static boolean isPenTool(int toolType) {
        return toolType == TOOL_TYPE_STYLUS || toolType == TOOL_TYPE_ERASER;
    }

    /** Testing only (PROP_TEST_NO_HOVER): drop every hover event. */
    public void setIgnoreHover(boolean ignoreHover) {
        this.ignoreHover = ignoreHover;
    }

    /** What the side button and the eraser end do (null: the defaults). */
    public void setButtons(Buttons buttons) {
        this.buttons = buttons == null ? DEFAULT_BUTTONS : buttons;
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
                /* a down with the button still held: the last stroke's lift
                 * never came (Android handed the touch to a palm, or it came
                 * while the view was disabled). That stroke ends where it
                 * was first: pressed on, SAI joined the two strokes with a
                 * straight line. */
                if (heldButton != 0) lift(e.timeUs(e.historySize()));
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
        if (ignoreHover) return;
        if (heldButton != 0) return;
        switch (action) {
            case ACTION_HOVER_ENTER:
            case ACTION_HOVER_MOVE:
                hoveredSinceLift = true;
                for (int pos = 0; pos <= e.historySize(); pos++) {
                    if (pos == e.historySize()) place(e, pos);
                    else locate(e, pos);
                    sink.sample(0, lastX, lastY, e.timeUs(pos));
                }
                sink.setInRange(true);
                /* the side button pressed in the air: once per press */
                boolean side = e.sideButton();
                if (side && !hoverSide) buttons.hoverButton(clampX(), clampY());
                hoverSide = side;
                break;
            case ACTION_HOVER_EXIT:
                hoveredSinceLift = false;
                hoverSide = false;
                sink.setInRange(false);
                break;
            default:
                break;
        }
    }

    private void down(Event e) {
        int pos = e.historySize();
        place(e, pos);
        cursorAtUs = e.timeUs(pos);
        /*
         * A pen with no hover (Ugee UT2, issue #1) touches with nothing sent
         * before. SAI starts the stroke on Wine's click and, with no recent
         * packet saying a pen is here, takes that click as a mouse: a dot at
         * full brush size. Our pen-down packets cannot say it in time: the
         * DLL holds them until SAI has taken the click (the HOLD in
         * wintab_core.h). A hover packet is not held, so one goes out first,
         * exactly what an S Pen sends just before it touches. A pen that did
         * hover sends nothing extra.
         */
        if (!hoveredSinceLift) sink.sample(0, lastX, lastY, e.timeUs(pos));
        /* a button still held when the pen lifts is no new press in the air */
        hoverSide = e.sideButton();
        heldButton = buttons.down(e.sideButton(), e.eraser());
        if (heldButton != BUTTON_RIGHT && heldButton != NO_BUTTON) heldButton = BUTTON_LEFT;
        /* the sample goes out BEFORE the button: on the Mac, too, our
         * pen-down datagram reaches the DLL before Wine's click does */
        sink.sample(pressureAt(e, pos), lastX, lastY, e.timeUs(pos));
        if (heldButton != NO_BUTTON) sink.pressButton(heldButton);
    }

    /** moveCursor: only for the event's newest sample (see the class note) */
    private void touchingAt(Event e, int pos, boolean moveCursor) {
        long t = e.timeUs(pos);
        if (moveCursor && (cursorMinUs == 0 || t - cursorAtUs >= cursorMinUs)) {
            place(e, pos);
            cursorAtUs = t;
        }
        else locate(e, pos);
        sink.sample(pressureAt(e, pos), lastX, lastY, e.timeUs(pos));
    }

    private void lift(long evUs) {
        sink.sample(0, lastX, lastY, evUs);
        if (heldButton != NO_BUTTON) sink.releaseButton(heldButton);
        heldButton = 0;
        buttons.up();
        /* a pen with hover reports it again before the next touch */
        hoveredSinceLift = false;
    }

    /** With the side button held the touch is a right click, not a stroke
     *  (or nothing at all): report it as hovering so SAI does not ink. */
    private int pressureAt(Event e, int pos) {
        if (heldButton != BUTTON_LEFT) return 0;
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
        sink.movePointer(clampX(), clampY());
    }

    private int clampX() {
        return Math.max(0, Math.min((int) lastX, screenW - 1));
    }

    private int clampY() {
        return Math.max(0, Math.min((int) lastY, screenH - 1));
    }
}
