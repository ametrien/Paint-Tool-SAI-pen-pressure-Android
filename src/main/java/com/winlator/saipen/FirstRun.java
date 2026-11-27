package com.winlator.saipen;

/**
 * What SAI Pen's switches are before the person touches them: one place, so
 * the launcher's screen and the SAI session cannot disagree.
 *
 * THE TRAP: the timelapse switch was read in two places, each with its own
 * default. Turning the default on in one only would show the switch on while
 * the session did not record. FirstRunTest fails if a getBoolean of one of
 * these preferences anywhere in our code gives a literal true or false
 * instead of a constant from here.
 */
public final class FirstRun {
    private FirstRun() {}

    /** Start SAI right away when SAI Pen opens: off, the screen is shown first. */
    public static final boolean AUTOSTART = false;
    /** Record a timelapse: on (the owner, 2026-11-21). */
    public static final boolean TIMELAPSE = true;
    /** Speed logs: on, they fill the fast cores table. */
    public static final boolean SPEED_LOG = true;
    /** Palm rejection: each check off until tried on that tablet. */
    public static final boolean PALM_ANDROID = false;
    public static final boolean PALM_PEN_NEAR = false;
    public static final boolean PALM_BIG = false;
    /** Pen pressure (PenPressure): 0, Normal, the pen as it is. */
    public static final int PEN_SOFTNESS = 0;
    /** Screen drawing (FrontBuffer.Mode's word): "off", Standard (the
     *  owner, 0.4.30): the front buffer is a choice to try on SAI Pen's
     *  screen, not the default. 0.4.29 had "on". */
    public static final String SCREEN_DRAWING = "off";
    /** The cheat sheet (CheatSheetDialog): not seen yet, so it opens by
     *  itself the first time SAI Pen's screen opens. */
    public static final boolean CHEAT_SHEET_SEEN = false;
}
