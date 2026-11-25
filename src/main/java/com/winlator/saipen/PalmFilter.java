package com.winlator.saipen;

/**
 * Palm rejection: three checks, each on its own switch in the launcher, all
 * off by default. A finger the checks call a palm never becomes a finger:
 * no cursor move, no tap (= click), no two-finger gesture.
 *
 * Why it is needed: Winlator takes a one-finger tap for a left click, so a
 * palm brushing the glass made SAI put a mark there.
 *
 * 1. ANDROID (PREF_PALM_ANDROID): Android's own word that a touch was a palm.
 *    Android (and the tablet maker's touch firmware) can decide after the fact
 * that a touch was accidental:
 * - one touch alone is taken back with ACTION_CANCEL;
 * - one of several is lifted with ACTION_POINTER_UP carrying FLAG_CANCELED
 *   (Android 13+), e.g. a palm resting next to a finger;
 * - some firmware reports the contact as a palm outright (tool type 5,
 *   MotionEvent.TOOL_TYPE_PALM, hidden in the public API).
 *
 *    Winlator ignored all three.
 *
 * 2. PEN NEAR (PREF_PALM_PEN_NEAR): while the pen hovers or draws, and for
 *    PEN_QUIET_MS after its last sign, fingers are ignored: that is when a
 *    palm lands. Timed from the pen's last event, not from hover-exit, so a
 *    lost hover-exit cannot block the fingers for good, and pens that report
 *    no hover still count from their last touch.
 *
 *    A lone finger already down when the pen starts hovering is forgotten
 *    too (dropOnPenHover): a palm lands before the pen comes in range, and
 *    it moved the cursor under the palm while the hovering pen moved it to
 *    the pen. Two fingers are a pinch, scroll or turn under way and stay.
 *
 * 3. BIG CONTACT (PREF_PALM_BIG): a contact wider than BIG_CONTACT_MM (the
 *    touch's major axis, as Android reports it) is a palm, not a fingertip.
 *
 * No Android types: the JVM tests use plain numbers.
 */
public final class PalmFilter {
    /** The launcher's switches; all false (off) by default. */
    public static final String PREF_PALM_ANDROID = "saipen_palm_android";
    public static final String PREF_PALM_PEN_NEAR = "saipen_palm_pen_near";
    public static final String PREF_PALM_BIG = "saipen_palm_big";

    /** Fingers stay ignored this long after the pen's last event. */
    public static final long PEN_QUIET_MS = 1500;

    /** A contact wider than this is a palm: a fingertip is about 7-12 mm. */
    public static final float BIG_CONTACT_MM = 20f;

    /** lastPenMs before the pen has been seen at all. */
    public static final long NEVER = Long.MIN_VALUE;

    /* MotionEvent constants, copied so the JVM tests need no Android */
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_CANCEL = 3,
        ACTION_POINTER_DOWN = 5, ACTION_POINTER_UP = 6;
    /** MotionEvent.FLAG_CANCELED (API 33): the lifted pointer was accidental. */
    public static final int FLAG_CANCELED = 0x20;
    /** MotionEvent.TOOL_TYPE_PALM (@hide in AOSP, sent by some firmware). */
    public static final int TOOL_TYPE_PALM = 5;

    private PalmFilter() {}

    /** A finger lifted that Android says was never meant (a palm). */
    public static boolean isCanceledLift(int actionMasked, int flags) {
        return (actionMasked == ACTION_UP || actionMasked == ACTION_POINTER_UP)
            && (flags & FLAG_CANCELED) != 0;
    }

    /** The whole touch was taken back by the system. */
    public static boolean isCancel(int actionMasked) {
        return actionMasked == ACTION_CANCEL;
    }

    /** A contact reported as a palm from the start. */
    public static boolean isPalmTool(int toolType) {
        return toolType == TOOL_TYPE_PALM;
    }

    /** A contact landing that is a palm: it never becomes a finger. */
    public static boolean isPalmDown(int actionMasked, int toolType) {
        return (actionMasked == ACTION_DOWN || actionMasked == ACTION_POINTER_DOWN)
            && isPalmTool(toolType);
    }

    /** Is the pen near: touching, or its last event under PEN_QUIET_MS ago? */
    public static boolean penNear(long nowMs, long lastPenMs, boolean penTouching) {
        if (penTouching) return true;
        if (lastPenMs == NEVER) return false;
        long since = nowMs - lastPenMs;
        return since >= 0 && since < PEN_QUIET_MS;
    }

    /** The pen hovers: the fingers down are forgotten if they are one alone
     *  (a palm moving the cursor), kept if two or more (a gesture). */
    public static boolean dropOnPenHover(int fingersDown) {
        return fingersDown == 1;
    }

    /** The contact's width in mm: Android's touch major (px) at this dpi. */
    public static float contactMm(float touchMajorPx, float dpi) {
        if (dpi <= 0) return 0;
        return touchMajorPx / dpi * 25.4f;
    }

    /** How far apart two view points are in mm (for the log: how far a
     *  touch landed from the pen). */
    public static float distanceMm(float dxPx, float dyPx, float dpi) {
        if (dpi <= 0) return 0;
        return (float) Math.hypot(dxPx, dyPx) / dpi * 25.4f;
    }

    /** Wider than a fingertip can be. */
    public static boolean isBigContact(float touchMajorPx, float dpi) {
        return contactMm(touchMajorPx, dpi) > BIG_CONTACT_MM;
    }
}
