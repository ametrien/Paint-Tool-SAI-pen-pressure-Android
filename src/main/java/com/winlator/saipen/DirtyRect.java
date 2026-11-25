package com.winlator.saipen;

/**
 * The part of a window's picture that changed since it last went to the GPU.
 *
 * Winlator copied the whole window into its texture after every change: for SAI on a
 * 2000 x 1200 screen that is 9.6 MB, about 3 ms a frame, while a brush stroke changes only a
 * small piece. The X server thread adds what each drawing request changed; the GL thread takes
 * the box around all of it and uploads only that (Texture.updateFromDrawable).
 *
 * One box around everything, not a list: two far apart changes upload what lies between,
 * which is never more than the whole window was.
 */
public final class DirtyRect {
    private final int width, height;
    private int x0, y0, x1, y1;     // x1, y1 exclusive; empty when x0 >= x1

    public DirtyRect(int width, int height) {
        this.width = width;
        this.height = height;
        clear();
    }

    private void clear() {
        x0 = width; y0 = height; x1 = 0; y1 = 0;
    }

    /** A change at x, y, width w, height h; clipped to the window. */
    public synchronized void add(int x, int y, int w, int h) {
        int ax = Math.max(0, x), ay = Math.max(0, y);
        int bx = Math.min(width, x + Math.max(0, w)), by = Math.min(height, y + Math.max(0, h));
        if (ax >= bx || ay >= by) return;
        if (ax < x0) x0 = ax;
        if (ay < y0) y0 = ay;
        if (bx > x1) x1 = bx;
        if (by > y1) y1 = by;
    }

    /** The whole window changed (or it is not known what did). */
    public synchronized void addAll() {
        x0 = 0; y0 = 0; x1 = width; y1 = height;
    }

    /**
     * Takes the changed box into out as {x, y, width, height} and starts a new one.
     * False, and out untouched, when nothing changed.
     */
    public synchronized boolean take(int[] out) {
        if (x0 >= x1 || y0 >= y1) return false;
        out[0] = x0; out[1] = y0; out[2] = x1 - x0; out[3] = y1 - y0;
        clear();
        return true;
    }
}
