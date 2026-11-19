package com.winlator.saipen;

/**
 * The pen sample datagram our wintab32.dll reads on UDP 127.0.0.1:47800.
 *
 * One datagram per sample, plain text:
 *
 *     seq p x y w h ev tx n evok
 *
 * It is the same line the Mac helper sends (PressureCore.wireLine in
 * wacom-helper/PressureCore.swift) and the DLL parses with
 * wtc_parse_datagram() in wintab-src/wintab_core.h.
 *
 * - p: pressure, 0 = not touching (hover), 1..pmax = touching.
 * - x/w, y/h: the pen's place as a fraction of the whole Windows desktop,
 *   with y counted UPWARD from the bottom edge. All four share one
 *   fixed-point scale of 8 (the DLL's wtc_place() assumes nothing else, but
 *   the Mac helper uses 8 and so do we).
 * - ev, tx: microsecond times (the pen event's own time and the moment we
 *   sent it), n: samples handled but not sent since the previous datagram,
 *   evok: 1 when ev is a real event time. They only feed the DLL's
 *   stroke-start trace in its debug log; the masks match WTC_WIRE_MASK.
 *
 * Pure Java on purpose: the JVM unit tests run it on the Mac.
 */
public final class PenWire {
    private PenWire() {}

    public static final int FIXED = 8;
    public static final int WIRE_MASK = 0x7FFFFFFF;

    /**
     * Full-scale pressure. The app writes it to C:\wt_pmax.txt before SAI
     * starts, so the DLL advertises the same maximum to SAI that we scale to.
     * The S Pen reports 4096 levels.
     */
    public static final int PMAX = 4095;

    public static String line(long seq, int p, int x, int y, int w, int h,
                              long evUs, boolean evOk, long txUs, int n) {
        return seq + " " + p + " " + x + " " + y + " " + w + " " + h + " "
                + ((evOk ? evUs : 0) & WIRE_MASK) + " " + (txUs & WIRE_MASK) + " "
                + (n & WIRE_MASK) + " " + (evOk ? 1 : 0);
    }

    /**
     * Android pressure (nominally 0..1, some devices report a little above 1)
     * to the DLL's 0..pmax.
     *
     * THE TRAP: a touching pen must never send 0. To the DLL, p == 0 means the
     * pen is in the air, so a feather-light touch that rounds to 0 would end
     * the stroke in SAI while the button is still held. Touching is at least 1.
     */
    public static int pressureLevel(float pressure, boolean touching, int pmax) {
        if (!touching) return 0;
        if (Float.isNaN(pressure) || pressure < 0f) pressure = 0f;
        int p = Math.round(pressure * pmax);
        if (p < 1) p = 1;
        if (p > pmax) p = pmax;
        return p;
    }

    /**
     * A point on Winlator's X screen (pixels, y DOWN from the top, as
     * XForm.transformPoint gives it) to the wire's {x, y, w, h}.
     *
     * Winlator runs Wine as "explorer /desktop=...,WxH" with the X screen's
     * size, so the X screen and the Windows desktop are the same rectangle.
     *
     * THE TRAP: y is flipped (wire y counts up from the bottom). Points in
     * the letterbox bars land outside the screen and are clamped to its edge,
     * like the cursor itself.
     */
    public static int[] toWire(float sx, float sy, int screenW, int screenH) {
        if (Float.isNaN(sx)) sx = 0f;
        if (Float.isNaN(sy)) sy = 0f;
        float cx = Math.max(0f, Math.min(sx, screenW));
        float cy = Math.max(0f, Math.min(sy, screenH));
        int w = screenW * FIXED;
        int h = screenH * FIXED;
        int x = Math.round(cx * FIXED);
        int y = Math.round((screenH - cy) * FIXED);
        return new int[] { x, y, w, h };
    }

    /**
     * Keep-alive while hovering: resend the last hover sample when nothing was
     * sent for more than 50 ms. Same rule as the Mac helper
     * (PressureCore.keepAliveShouldResend): without it SAI lost its brush
     * cursor while the pen hovered still (#20). There is no separate mouse on
     * the phone, so the Mac's mouse-idle grace does not apply.
     */
    public static boolean keepAliveShouldResend(boolean penInRange, int lastPressure,
                                                long msSinceLastSend) {
        return penInRange && lastPressure == 0 && msSinceLastSend > 50;
    }

    /** The most zoom steps the DLL takes in one line (WTC_ZOOM_MAX_STEPS). */
    public static final int ZOOM_MAX_STEPS = 64;

    /**
     * The control line that zooms SAI: "Z <steps>", + in, - out. Same as the
     * Mac helper's PressureCore.zoomLine. Null for 0 steps, which the DLL
     * refuses (wtc_parse_control); more than 64 either way is clamped, as
     * the DLL would refuse that too.
     */
    public static String zoomLine(int steps) {
        if (steps == 0) return null;
        int s = Math.max(-ZOOM_MAX_STEPS, Math.min(ZOOM_MAX_STEPS, steps));
        return "Z " + s;
    }
}
