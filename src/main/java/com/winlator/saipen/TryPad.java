package com.winlator.saipen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;

/**
 * "Try it": a strip of paper on a tool's page to draw on with the pen, its
 * pressure making the stroke thinner and lighter the way the tool's
 * settings say (PressureStroke). CLOSE TO SAI, NOT SAI: the same brush as
 * the samples, not SAI's own engine.
 *
 * Only the pen (and a mouse) draws; a finger scrolls the page as before.
 * One stroke never gets denser than the tool's density; strokes on top of
 * each other do, as in SAI.
 *
 * It only works while the brush browser is open, on SAI Pen's own screen:
 * never while SAI runs.
 */
final class TryPad extends View {
    /** The pen is down on a pad: the page's sideways swipe leaves it alone. */
    static volatile boolean drawing;

    private final File settings;
    private final SaiSettingsFiles.Tool tool;
    private Bitmap paper, stroke;
    private PressureStroke.Live live;
    private float[] mask;
    private int[] row;
    private final int ink = SamplePreview.INK & 0xFFFFFF;

    TryPad(Context c, File settings, SaiSettingsFiles.Tool tool) {
        super(c);
        this.settings = settings;
        this.tool = tool;
    }

    /** Back to clean paper. */
    void clear() {
        if (paper != null) paper.eraseColor(SamplePreview.PAPER);
        if (stroke != null) stroke.eraseColor(0);
        if (live != null) { live.end(); live.field.clear(); }
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        recycle();
        paper = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        paper.eraseColor(SamplePreview.PAPER);
        stroke = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        /* the tool at its own size in pixels, as on SAI's canvas at 100% */
        double width = Math.max(1, Math.min(tool.size, h * 0.8));
        double level = Math.max(0.1, Math.min(1, tool.density / 100.0));
        live = new PressureStroke.Live(w, h, tool.pressure, width, level, tool.hardness);
        row = new int[w];
        mask = null;
        try { mask = SamplePreview.padMask(settings, tool, w, h); }
        catch (RuntimeException | OutOfMemoryError ignored) {}
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        drawing = false;
        recycle();
    }

    private void recycle() {
        if (paper != null) paper.recycle();
        if (stroke != null) stroke.recycle();
        paper = stroke = null;
        live = null;
    }

    @Override protected void onDraw(Canvas c) {
        if (paper == null) return;
        c.drawBitmap(paper, 0, 0, null);
        c.drawBitmap(stroke, 0, 0, null);
    }

    private static boolean draws(MotionEvent e) {
        int t = e.getToolType(0);
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE;
    }

    private static float pressure(MotionEvent e, int h) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return 1f;
        return h < 0 ? e.getPressure() : e.getHistoricalPressure(h);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (live == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!draws(e)) return false;
                drawing = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                live.begin(e.getX(), e.getY(), pressure(e, -1));
                show();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!live.down()) return false;
                for (int k = 0; k < e.getHistorySize(); k++) live.to(e.getHistoricalX(k), e.getHistoricalY(k), pressure(e, k));
                live.to(e.getX(), e.getY(), pressure(e, -1));
                show();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!live.down()) return false;
                if (e.getActionMasked() == MotionEvent.ACTION_UP) live.to(e.getX(), e.getY(), pressure(e, -1));
                show();
                live.end();
                /* the stroke onto the paper; the next one starts empty */
                new Canvas(paper).drawBitmap(stroke, 0, 0, null);
                stroke.eraseColor(0);
                drawing = false;
                invalidate();
                return true;
        }
        return false;
    }

    /** The changed part of the stroke into its bitmap. */
    private void show() {
        int[] r = live.field.changed();
        if (r == null) return;
        int w = stroke.getWidth(), n = r[2] - r[0] + 1;
        for (int y = r[1]; y <= r[3]; y++) {
            for (int x = r[0]; x <= r[2]; x++) {
                int i = y * w + x, a = live.field.alpha(i);
                if (mask != null) a = Math.round(a * mask[i]);
                row[x - r[0]] = a << 24 | ink;
            }
            stroke.setPixels(row, 0, n, r[0], y, n, 1);
        }
        invalidate(r[0], r[1], r[2] + 1, r[3] + 1);
    }
}
