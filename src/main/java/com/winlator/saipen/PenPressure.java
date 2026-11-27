package com.winlator.saipen;

/**
 * "Pen pressure" on SAI Pen's screen: how hard the pen has to press for a
 * full line. A slider from 0 (Normal: the pen's pressure as it is) to
 * MAX_SOFTNESS (Very soft: a light press already gives a strong line).
 *
 * The pressure is bent by a curve, p^g with g from 1 down to MIN_EXPONENT:
 * nothing changes at no press (0) and at a full press (1), only the middle
 * rises. So a stroke still fades in and out smoothly, only with less force.
 *
 * Read once when SAI starts (XServerDisplayActivity), like palm rejection.
 */
public final class PenPressure {
    private PenPressure() {}

    public static final String PREF_SOFTNESS = "saipen_pen_softness";
    /** Slider steps: 0 Normal .. 10 Very soft. */
    public static final int MAX_SOFTNESS = 10;
    /** The exponent at MAX_SOFTNESS: a quarter press gives about 57 %. */
    static final float MIN_EXPONENT = 0.4f;

    private static volatile float exponent = 1f;

    /** The curve's exponent for a slider step (out-of-range steps clamped). */
    static float exponentFor(int softness) {
        int s = clamp(softness);
        return 1f - (1f - MIN_EXPONENT) * s / MAX_SOFTNESS;
    }

    /** The step SAI's session uses from now on. */
    public static void setSoftness(int softness) {
        exponent = exponentFor(softness);
    }

    /** Android's pressure (0..1, a little above 1 on some pens) through the curve. */
    public static float apply(float pressure) {
        return apply(pressure, exponent);
    }

    static float apply(float pressure, float exp) {
        if (Float.isNaN(pressure) || pressure <= 0f) return 0f;
        if (exp == 1f) return pressure;
        return (float) Math.pow(pressure, exp);
    }

    /** What a press of `pressure` gives at slider step `softness`, in whole percent. */
    public static int percentFor(float pressure, int softness) {
        return Math.round(100f * Math.min(1f, apply(pressure, exponentFor(softness))));
    }

    public static int clamp(int softness) {
        return Math.max(0, Math.min(MAX_SOFTNESS, softness));
    }
}
