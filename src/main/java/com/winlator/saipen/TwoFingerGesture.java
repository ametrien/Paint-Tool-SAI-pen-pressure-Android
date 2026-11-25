package com.winlator.saipen;

/**
 * Two fingers on the canvas: drag them to scroll the canvas in any
 * direction, spread or pinch them to zoom at the point between them.
 *
 * Winlator's own two-finger handling only scrolled up and down (wheel
 * buttons 4 and 5), and read a pinch as "hold one finger, drag with the
 * other" = a left-button drag. Here a gesture is one of two kinds, decided
 * by what happens first:
 *
 * - SCROLL (the fingers moved together by SCROLL_START_PX first): X wheel
 *   buttons, both axes: 4/5 vertical, 6/7 horizontal (Wine makes 6/7 into
 *   WM_MOUSEHWHEEL). Our wintab32.dll turns every wheel inside SAI into
 *   SAI's own Scroll View arrow keys (issue #24 on the Mac).
 * - ZOOM (the spread changed by ZOOM_START_RATIO first): SAI's own zoom,
 *   through the DLL's "Z <steps>" line at the point where the pinch began.
 *   SAI zooms only its canvas, in fixed steps, and redraws after each.
 * - ROTATE (the fingers turned around each other first, measured as the arc
 *   a fingertip travelled): SAI's own free rotation, Alt+Space + left drag.
 *   The cursor is dragged around the point where the gesture began, as far
 *   as the fingers have turned, so SAI turns the view along with them.
 *   THE TRAP of the first try: SAI2's Rotate View CW/CCW have NO default
 *   keys, and Delete (taken for Rotate Left) is Erase Layer.
 *
 * How the zoom is paced, and why:
 * - one SAI step per 18 % of finger spread (SPREAD_PER_STEP), the first at
 *   10 %: quick, like the Mac's trackpad pinch. 0.3.1 waited for 30 % and
 *   then sent at most every 200 ms ("very laggy"); 0.3.2's 25 % was
 *   "faster but not fast enough";
 * - a step needs 0.6 of a step's spread change past the last one, so
 *   fingers resting near a half step do not zoom in and out by turns;
 * - how fast the steps then reach SAI is ZoomPacer's job: each as soon as
 *   SAI has drawn the one before.
 *
 * THE TRAP of 0.3.0, which scaled the picture on the tablet while the
 * fingers moved and told SAI at the end: the tablet only has the picture of
 * the WHOLE window, so the panels and menus grew and shrank with the canvas.
 * That looked broken. Only SAI knows where its canvas is.
 *
 * Directions follow the fingers, like any touch screen: fingers moving down
 * pull the canvas down (the view scrolls up).
 *
 * No Android types: the JVM tests drive it with plain coordinates and times.
 */
public final class TwoFingerGesture {
    /* X pointer buttons (com.winlator.xserver.Pointer.Button codes) */
    public static final int WHEEL_UP = 4, WHEEL_DOWN = 5, WHEEL_LEFT = 6, WHEEL_RIGHT = 7;

    /** X screen pixels of finger travel per wheel click. Winlator's own
     *  vertical two-finger scroll used 100. */
    public static final float SCROLL_PX_PER_CLICK = 100f;

    /**
     * A gesture is decided once the fingers have travelled together, or
     * their spread has changed, by this many X screen pixels: whichever of
     * the two is bigger then. A pinch moves the fingers apart while the
     * point between them stays; a scroll moves that point while the spread
     * stays.
     *
     * THE TRAP: deciding by "spread +10 % before 40 px of travel" (0.3.3)
     * took scrolls for zooms: fingers drift apart a little while they move,
     * and +10 % of a 200 px spread is only 20 px.
     */
    public static final float DECIDE_PX = 30f;

    /** One SAI zoom step per this change of the finger spread. */
    public static final double SPREAD_PER_STEP = 1.18;

    /** How far past the last step the spread must go for the next one, in
     *  steps (while moving; at the end half a step is enough). */
    public static final double ZOOM_HYSTERESIS = 0.6;

    /** No more steps than this in one pinch, either way. */
    public static final int MAX_ZOOM_STEPS = 16;

    /** The rotate drag's radius around the gesture's start point, at least
     *  (X screen pixels): a small circle makes SAI's angle jumpy. */
    public static final float MIN_ROTATE_RADIUS = 150f;

    /** Fingers closer than this (X screen pixels) give no usable spread. */
    private static final float MIN_SPREAD = 8f;

    public interface Sink {
        /** Put the cursor at this X screen point. */
        void moveCursor(int x, int y);
        /** One wheel click of this X button. */
        void wheel(int button);
        /** Zoom SAI by `steps` (+ in, - out) at the cursor. */
        void zoom(int steps);
        /** Start SAI's rotate drag (Alt+Space, button down) at this point. */
        void rotateBegin(int x, int y);
        /** Move the rotate drag to this point. */
        void rotateDrag(int x, int y);
        /** End the rotate drag (button up, Space and Alt up). */
        void rotateEnd();
    }

    private enum Mode { NONE, SCROLL, ZOOM, ROTATE }

    private final Sink sink;
    private boolean active = false;
    private Mode mode = Mode.NONE;
    private float startMidX, startMidY, lastMidX, lastMidY;
    private double startLogSpread, logScale;
    private float startSpread;
    private float accumX, accumY;
    private boolean cursorPlaced = false;
    private int zoomSent;
    private boolean rotating;
    private int lastRotX, lastRotY;
    private double startAngle, lastAngle, turned;

    public TwoFingerGesture(Sink sink) {
        this.sink = sink;
    }

    public boolean isActive() {
        return active;
    }

    /** Both fingers' current X screen points. */
    public void move(float x1, float y1, float x2, float y2) {
        float midX = (x1 + x2) / 2f, midY = (y1 + y2) / 2f;
        double logSpread = Math.log(Math.max(MIN_SPREAD, (float) Math.hypot(x2 - x1, y2 - y1)));
        if (!active) {
            active = true;
            mode = Mode.NONE;
            startMidX = lastMidX = midX;
            startMidY = lastMidY = midY;
            startLogSpread = logSpread;
            startSpread = (float) Math.hypot(x2 - x1, y2 - y1);
            logScale = 0;
            accumX = accumY = 0;
            cursorPlaced = false;
            zoomSent = 0;
            rotating = false;
            lastRotX = lastRotY = Integer.MIN_VALUE;   /* a new drag repeats no point */
            startAngle = lastAngle = Math.atan2(y2 - y1, x2 - x1);
            turned = 0;
            return;
        }

        logScale = logSpread - startLogSpread;
        accumX += midX - lastMidX;
        accumY += midY - lastMidY;
        lastMidX = midX;
        lastMidY = midY;
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double dA = angle - lastAngle;
        /* the line between the fingers has no direction: Android may hand the
         * two fingers over in swapped order, which flips the angle by 180 deg
         * (THE TRAP of the first try: +12/-12 steps per event, and every
         * scroll and pinch was taken for a turn). Wrap into +-90 deg. */
        while (dA > Math.PI / 2) dA -= Math.PI;
        while (dA < -Math.PI / 2) dA += Math.PI;
        turned += dA;
        lastAngle = angle;

        if (mode == Mode.NONE) {
            float spreadChange = Math.abs((float) Math.hypot(x2 - x1, y2 - y1) - startSpread);
            float travel = (float) Math.hypot(midX - startMidX, midY - startMidY);
            /* how far a fingertip moved along the circle around the midpoint */
            float arc = (float) Math.abs(turned) * Math.max(startSpread, MIN_SPREAD) / 2f;
            float most = Math.max(arc, Math.max(spreadChange, travel));
            if (most >= DECIDE_PX) {
                mode = most == arc ? Mode.ROTATE : spreadChange > travel ? Mode.ZOOM : Mode.SCROLL;
            }
        }

        if (mode == Mode.ZOOM) {
            double diff = stepsShown() - zoomSent;
            if (Math.abs(diff) >= ZOOM_HYSTERESIS) sendZoom((int) Math.round(diff));
        }
        else if (mode == Mode.ROTATE) {
            rotateDrag();
        }
        else if (mode == Mode.SCROLL) {
            scroll(midX, midY);
        }
    }

    /** The gesture is over (a finger went up, or a third came down): the
     *  steps the fingers still owe SAI go out now. */
    public void end() {
        if (active && mode == Mode.ZOOM) {
            int diff = (int) Math.round(stepsShown() - zoomSent);
            if (diff != 0) sendZoom(diff);
        }
        if (rotating) {
            sink.rotateEnd();
            rotating = false;
        }
        active = false;
        mode = Mode.NONE;
    }

    /** The gesture is taken back (Android says a palm, PalmFilter): a
     *  rotate drag lets go, but no zoom steps it still owed go out. */
    public void cancel() {
        if (rotating) {
            sink.rotateEnd();
            rotating = false;
        }
        active = false;
        mode = Mode.NONE;
    }

    /** The spread so far in SAI steps, capped. */
    private double stepsShown() {
        double steps = logScale / Math.log(SPREAD_PER_STEP);
        return Math.max(-MAX_ZOOM_STEPS, Math.min(MAX_ZOOM_STEPS, steps));
    }

    /** Where the rotate drag's cursor is for the turn so far: on a circle
     *  around the start point, starting at the first finger's side. */
    private void rotateDrag() {
        double r = Math.max(MIN_ROTATE_RADIUS, startSpread / 2f);
        double a = startAngle + Math.PI + turned;
        int x = (int) Math.round(startMidX + r * Math.cos(a));
        int y = (int) Math.round(startMidY + r * Math.sin(a));
        if (!rotating) {
            double a0 = startAngle + Math.PI;
            sink.rotateBegin((int) Math.round(startMidX + r * Math.cos(a0)),
                             (int) Math.round(startMidY + r * Math.sin(a0)));
            rotating = true;
        }
        if (x != lastRotX || y != lastRotY) {
            sink.rotateDrag(x, y);
            lastRotX = x;
            lastRotY = y;
        }
    }

    private void sendZoom(int steps) {
        if (steps == 0) return;
        /* the DLL zooms at the cursor: where the pinch began */
        if (!cursorPlaced) {
            sink.moveCursor(Math.round(startMidX), Math.round(startMidY));
            cursorPlaced = true;
        }
        sink.zoom(steps);
        zoomSent += steps;
    }

    private void scroll(float midX, float midY) {
        int clicksY = (int) (accumY / SCROLL_PX_PER_CLICK);
        int clicksX = (int) (accumX / SCROLL_PX_PER_CLICK);
        if (clicksX == 0 && clicksY == 0) return;
        /* the wheel goes to the window under the cursor: between the fingers */
        if (!cursorPlaced) {
            sink.moveCursor(Math.round(midX), Math.round(midY));
            cursorPlaced = true;
        }
        accumY -= clicksY * SCROLL_PX_PER_CLICK;
        accumX -= clicksX * SCROLL_PX_PER_CLICK;
        /* fingers down (+y) pull the canvas down: the view scrolls up */
        for (int i = 0; i < Math.abs(clicksY); i++) sink.wheel(clicksY > 0 ? WHEEL_UP : WHEEL_DOWN);
        /* fingers right (+x) pull the canvas right: the view scrolls left */
        for (int i = 0; i < Math.abs(clicksX); i++) sink.wheel(clicksX > 0 ? WHEEL_LEFT : WHEEL_RIGHT);
    }
}
