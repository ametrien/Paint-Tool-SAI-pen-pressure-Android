package com.winlator.saipen;

/**
 * How solid the on-screen keys are (FloatingKeys): one choice for the keys'
 * panel and the red Exit SAI together, picked under On-screen keys. Kept as
 * a percent; 85 is how they always looked (alpha 0xD8). Words and the cross
 * stay solid; only the backgrounds follow.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class KeysOpacity {
    public static final String PREF = "saipen_keys_opacity";
    public static final int MIN = 20;
    public static final int BY_DEFAULT = 85;

    private KeysOpacity() {}

    /** A saved percent from MIN to 100; anything else reads as the default. */
    public static int of(int saved) {
        return saved >= MIN && saved <= 100 ? saved : BY_DEFAULT;
    }

    /** `rgb` (its own alpha ignored) at `percent` opacity, as an ARGB int. */
    public static int apply(int rgb, int percent) {
        int alpha = 255 * of(percent) / 100;   /* 85 -> 216 = 0xD8, as before */
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
