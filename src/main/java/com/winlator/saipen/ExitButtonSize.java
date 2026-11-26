package com.winlator.saipen;

/**
 * How big the red Exit SAI of its own is (FloatingKeys, PREF_EXIT_SIZE):
 * small, medium (the first) or large, as the artist picks under On-screen
 * keys. Kept as an index; anything else reads as medium.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class ExitButtonSize {
    public static final int SMALL = 0, MEDIUM = 1, LARGE = 2;
    public static final int BY_DEFAULT = MEDIUM;

    /** The button's size against medium (56 x 56 dp). */
    private static final float[] SCALES = {0.75f, 1f, 1.4f};

    private ExitButtonSize() {}

    /** A saved index as one of SMALL, MEDIUM, LARGE. */
    public static int of(int saved) {
        return saved >= SMALL && saved <= LARGE ? saved : BY_DEFAULT;
    }

    public static float scale(int saved) {
        return SCALES[of(saved)];
    }
}
