package com.winlator.saipen;

/**
 * How the red Exit SAI of its own looks (FloatingKeys): how far its red goes
 * towards black, and how see-through it is, either the keys' own opacity
 * (KeysOpacity, the default) or one of its own. Both are sliders under
 * On-screen keys. The white cross stays solid and white.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class ExitLook {
    /** 0 (SAI's red) to MAX_DARK percent of the way to black. */
    public static final String PREF_DARK = "saipen_keys_exit_dark";
    public static final int MAX_DARK = 90;
    /** Its own opacity in percent, or SAME_AS_KEYS. */
    public static final String PREF_OPACITY = "saipen_keys_exit_opacity";
    public static final int SAME_AS_KEYS = -1;
    public static final int MIN_OPACITY = 20;

    private ExitLook() {}

    public static int dark(int saved) {
        return Math.max(0, Math.min(MAX_DARK, saved));
    }

    /** The opacity to use: its own when one is saved, else the keys'. */
    public static int opacity(int savedOwn, int keysPercent) {
        if (savedOwn == SAME_AS_KEYS) return keysPercent;
        return Math.max(MIN_OPACITY, Math.min(100, savedOwn));
    }

    /** `rgb` taken `darkPercent` of the way to black, at `opacityPercent`, as ARGB. */
    public static int color(int rgb, int darkPercent, int opacityPercent) {
        int d = dark(darkPercent);
        int r = ((rgb >> 16) & 0xFF) * (100 - d) / 100;
        int g = ((rgb >> 8) & 0xFF) * (100 - d) / 100;
        int b = (rgb & 0xFF) * (100 - d) / 100;
        int a = 255 * Math.max(0, Math.min(100, opacityPercent)) / 100;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
