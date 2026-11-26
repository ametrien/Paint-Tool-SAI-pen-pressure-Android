package com.winlator.saipen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * "Try it" for a picture (brush texture, paper texture, brush shape,
 * scatter shape): paper to draw on with the pen, the way SAI would use that
 * picture. CLOSE TO SAI, NOT SAI, as TryPad for tools:
 *
 *  - Brush texture: a round brush painted through the texture, the texture
 *    fixed to the paper at its own size (SAI's 100% scale). Strokes over
 *    each other fill it in slowly, as in SAI.
 *  - Paper texture: paint on a layer that has this paper texture. As in SAI
 *    the grain shows only in the paint, and painting over it again does not
 *    fill the grain in (it is applied to the whole layer). A patch is
 *    painted to start with.
 *  - Brush and scatter shapes: the picture stamped along the stroke with its
 *    starting values (StampTrail).
 *
 * Pen pressure changes the size, from a third to all of it. Only the pen
 * (and a mouse) draws; a finger scrolls the page.
 */
final class PicturePad extends View {
    static final int SMALL = 0, MEDIUM = 1, LARGE = 2;
    static final int[] SIZES = {12, 32, 72};

    private final int kind;
    private final File picture;
    private final Map<String, String> ini;
    private final String folder;
    private final PressureStroke.Response pressure = new PressureStroke.Response(true, 30, 100, false, 100, 100);
    private int brush = SIZES[MEDIUM];
    private final int ink;

    private Bitmap paper, stroke, stamp;
    private PressureStroke.Live live;
    private StampTrail trail;
    private float[] mask;
    /** Paper texture: the layer's paint before its grain, 0..255. */
    private int[] layer;
    private int[] row;
    private final Paint stampPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix mx = new Matrix();
    private long seed = 1;

    /** kind: SettingsContents' picture kinds; folder: SAI's folder of the
     *  picture, relative to settings ("brushfom/bristle"). */
    PicturePad(Context c, int kind, File picture, Map<String, String> ini, String folder) {
        super(c);
        this.kind = kind;
        this.picture = picture;
        this.ini = ini;
        this.folder = folder;
        StampTrail.Settings s = StampTrail.Settings.of(ini, folder);
        /* colour jitter does not show on near-black ink */
        ink = kind == SettingsContents.PAPER_TEXTURES ? 0xFF3F6496
            : kind == SettingsContents.SCATTER && s.colourJitter() ? 0xFFC0392B : SamplePreview.INK;
        if (stamps()) strokePaint.setAlpha((int) Math.round(255 * s.density));
    }

    private boolean stamps() { return kind == SettingsContents.BRUSH_SHAPES || kind == SettingsContents.SCATTER; }

    /** The brush size in pixels (SMALL, MEDIUM, LARGE): from the next stroke on. */
    void size(int which) {
        brush = SIZES[Math.max(0, Math.min(SIZES.length - 1, which))];
        make(getWidth(), getHeight(), false);
    }

    /** Back to clean paper (the paper texture's patch painted again). */
    void clear() {
        if (paper == null) return;
        if (live != null) live.end();
        if (trail != null) trail.end();
        paper.eraseColor(SamplePreview.PAPER);
        stroke.eraseColor(0);
        if (layer != null) { java.util.Arrays.fill(layer, 0); patch(); }
        if (live != null) live.field.clear();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        recycle();
        paper = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        paper.eraseColor(SamplePreview.PAPER);
        stroke = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        row = new int[w];
        try {
            if (stamps()) stamp = stampPicture();
            else {
                /* at the picture's own size: one of its pixels on one of the pad's */
                Bitmap m = SamplePreview.inkMask(SamplePreview.decode(picture, 1024), 1f);
                mask = SamplePreview.tile(null, m, w, h);
            }
        } catch (RuntimeException | OutOfMemoryError ignored) {}
        if (kind == SettingsContents.PAPER_TEXTURES) { layer = new int[w * h]; patch(); }
        make(w, h, true);
    }

    /** The brush for the next stroke, at the chosen size. */
    private void make(int w, int h, boolean fresh) {
        if (w <= 0 || h <= 0) return;
        if (stamps()) trail = new StampTrail(StampTrail.Settings.of(ini, folder), brush, pressure, seed);
        else if (fresh || live == null || !live.down()) live = new PressureStroke.Live(w, h, pressure, brush, 1, 100);
    }

    /** The shape as black with alpha; a blot map inside a round tip. */
    private Bitmap stampPicture() {
        Bitmap m = SamplePreview.inkMask(SamplePreview.decode(picture, 256), 1f);
        if (m == null || folder == null || !folder.toLowerCase(java.util.Locale.ROOT).endsWith("blotmap")) return m;
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        m.getPixels(px, 0, w, 0, 0, w, h);
        m.recycle();
        double cx = w / 2.0, cy = h / 2.0, rad = Math.min(w, h) / 2.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy) / rad;
                double k = d >= 1 ? 0 : d < 0.8 ? 1 : (1 - d) / 0.2;
                int i = y * w + x;
                px[i] = (int) Math.round((px[i] >>> 24) * k) << 24;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Paper texture: a patch painted at the left, so the grain shows at once. */
    private void patch() {
        int w = paper.getWidth(), h = paper.getHeight();
        int x0 = Math.round(w * 0.05f), x1 = Math.round(w * 0.32f), y0 = Math.round(h * 0.12f), y1 = Math.round(h * 0.88f);
        for (int y = y0; y < y1; y++) java.util.Arrays.fill(layer, y * w + x0, y * w + x1, 255);
        paint(0, 0, w - 1, h - 1);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        TryPad.drawing = false;
        recycle();
    }

    private void recycle() {
        if (paper != null) paper.recycle();
        if (stroke != null) stroke.recycle();
        if (stamp != null) stamp.recycle();
        paper = stroke = stamp = null;
        live = null;
        trail = null;
    }

    @Override protected void onDraw(Canvas c) {
        if (paper == null) return;
        c.drawBitmap(paper, 0, 0, null);
        c.drawBitmap(stroke, 0, 0, stamps() ? strokePaint : null);
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
        if (paper == null || (stamps() ? trail == null || stamp == null : live == null)) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!draws(e)) return false;
                TryPad.drawing = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (stamps()) stampAll(trail.begin(e.getX(), e.getY(), pressure(e, -1)));
                else { live.begin(e.getX(), e.getY(), pressure(e, -1)); show(); }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!(stamps() ? trail.down() : live.down())) return false;
                for (int k = 0; k < e.getHistorySize(); k++) to(e.getHistoricalX(k), e.getHistoricalY(k), pressure(e, k));
                to(e.getX(), e.getY(), pressure(e, -1));
                if (!stamps()) show();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!(stamps() ? trail.down() : live.down())) return false;
                if (e.getActionMasked() == MotionEvent.ACTION_UP) to(e.getX(), e.getY(), pressure(e, -1));
                if (stamps()) {
                    trail.end();
                    new Canvas(paper).drawBitmap(stroke, 0, 0, strokePaint);
                    stroke.eraseColor(0);
                    /* the next stroke's jitter differs, as in SAI */
                    seed++;
                    make(getWidth(), getHeight(), false);
                } else {
                    show();
                    live.end();
                    if (layer != null) commitLayer();
                    else {
                        new Canvas(paper).drawBitmap(stroke, 0, 0, null);
                        stroke.eraseColor(0);
                    }
                    live.field.clear();
                }
                TryPad.drawing = false;
                invalidate();
                return true;
        }
        return false;
    }

    private void to(float x, float y, float p) {
        if (stamps()) stampAll(trail.to(x, y, p));
        else live.to(x, y, p);
    }

    /** Stamps into the stroke's bitmap, each in its jittered colour. */
    private void stampAll(List<StampTrail.Stamp> list) {
        if (list.isEmpty()) return;
        Canvas c = new Canvas(stroke);
        int sw = stamp.getWidth(), sh = stamp.getHeight();
        float big = Math.max(sw, sh);
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (StampTrail.Stamp s : list) {
            stampPaint.setColorFilter(new PorterDuffColorFilter(colour(s), PorterDuff.Mode.SRC_IN));
            float k = (float) (s.size / big);
            mx.reset();
            mx.postTranslate(-sw / 2f, -sh / 2f);
            mx.postScale(k * (float) s.w, k * (float) s.h);
            mx.postRotate((float) s.angle);
            mx.postTranslate((float) s.x, (float) s.y);
            c.drawBitmap(stamp, mx, stampPaint);
            float reach = (float) s.size;
            x0 = Math.min(x0, (float) s.x - reach); y0 = Math.min(y0, (float) s.y - reach);
            x1 = Math.max(x1, (float) s.x + reach); y1 = Math.max(y1, (float) s.y + reach);
        }
        invalidate((int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1) + 1, (int) Math.ceil(y1) + 1);
    }

    private int colour(StampTrail.Stamp s) {
        if (s.hue == 0 && s.saturation == 0 && s.brightness == 0) return ink;
        float[] hsv = new float[3];
        Color.colorToHSV(ink, hsv);
        hsv[0] = (float) (((hsv[0] + s.hue) % 360 + 360) % 360);
        hsv[1] = (float) StampTrail.clamp(hsv[1] + s.saturation, 0, 1);
        hsv[2] = (float) StampTrail.clamp(hsv[2] + s.brightness, 0, 1);
        return Color.HSVToColor(hsv);
    }

    /** The changed part of the stroke into its bitmap: through the brush
     *  texture, or (paper texture) the layer's paint and this stroke through
     *  the grain. */
    private void show() {
        int[] r = live.field.changed();
        if (r == null) return;
        if (layer != null) { paint(r[0], r[1], r[2], r[3]); return; }
        int w = stroke.getWidth(), n = r[2] - r[0] + 1, rgb = ink & 0xFFFFFF;
        for (int y = r[1]; y <= r[3]; y++) {
            for (int x = r[0]; x <= r[2]; x++) {
                int i = y * w + x, a = live.field.alpha(i);
                if (mask != null) a = Math.round(a * mask[i]);
                row[x - r[0]] = a << 24 | rgb;
            }
            stroke.setPixels(row, 0, n, r[0], y, n, 1);
        }
        invalidate(r[0], r[1], r[2] + 1, r[3] + 1);
    }

    /** Paper texture: (the layer, the stroke over it) × the grain, onto
     *  the stroke bitmap (which then holds the whole layer as shown). */
    private void paint(int x0, int y0, int x1, int y1) {
        int w = stroke.getWidth(), n = x1 - x0 + 1, rgb = ink & 0xFFFFFF;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = y * w + x;
                int a = over(layer[i], live != null && live.down() ? live.field.alpha(i) : 0);
                if (mask != null) a = Math.round(a * mask[i]);
                row[x - x0] = a << 24 | rgb;
            }
            stroke.setPixels(row, 0, n, x0, y, n, 1);
        }
        invalidate(x0, y0, x1 + 1, y1 + 1);
    }

    /** The stroke into the layer; the stroke bitmap goes on showing the
     *  whole layer (the paper under it stays plain). */
    private void commitLayer() {
        PressureStroke.Field f = live.field;
        if (f.sx0 > f.sx1) return;
        int w = stroke.getWidth();
        for (int y = f.sy0; y <= f.sy1; y++) {
            for (int x = f.sx0; x <= f.sx1; x++) {
                int i = y * w + x;
                layer[i] = over(layer[i], f.alpha(i));
            }
        }
        paint(f.sx0, f.sy0, f.sx1, f.sy1);
    }

    /** b painted over a, both 0..255. */
    static int over(int a, int b) { return a + (b * (255 - a) + 127) / 255; }
}
