package com.winlator.saipen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * How a tool, texture or shape looks, drawn with Android's canvas: a sample
 * stroke along the same curve each time. CLOSE TO SAI, NOT SAI: SAI's brush
 * engine is its own; this takes the tool's size, density, hardness, texture,
 * shape and what pressure does to it (PressureStroke), and the picture's
 * starting values (.ini), and nothing else.
 *
 * Which part of a picture paints: SAI's pictures are grey on white or on
 * black. A picture that is mostly light paints with its dark parts, one
 * that is mostly dark with its light parts.
 */
final class SamplePreview {
    private SamplePreview() {}

    static final int PAPER = 0xFFF4F2EC, INK = 0xFF26272A;

    private static Path curve(int w, int h) {
        Path p = new Path();
        p.moveTo(w * 0.12f, h * 0.70f);
        p.cubicTo(w * 0.34f, h * 0.06f, w * 0.62f, h * 0.98f, w * 0.88f, h * 0.30f);
        return p;
    }

    private static Bitmap paper(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(PAPER);
        return b;
    }

    /** A tool's stroke in the chosen shape (PressureStroke.LINE, S_CURVE,
     *  HEART), light to heavy to light, its size and density following
     *  pressure the way the tool's settings say; plain paper for a tool
     *  that does not draw. */
    static Bitmap tool(File settings, SaiSettingsFiles.Tool t, int shape, int w, int h) {
        Bitmap out = paper(w, h);
        if (!t.draws()) return out;
        double width = PressureStroke.width(shape, t.size, h);
        double level = Math.max(0.1, Math.min(1, t.density / 100.0));
        int[] px = new int[w * h];
        PressureStroke.paint(px, w, h, PressureStroke.curve(shape, w, h, width), shape, t.pressure, width, level, t.hardness);
        int ink = INK & 0xFFFFFF;
        for (int i = 0; i < px.length; i++) px[i] = px[i] << 24 | ink;
        /* mutable: the texture and shape are painted into it */
        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        layer.setPixels(px, 0, w, 0, 0, w, h);
        File tex = picture(settings, new String[] {"brushtex"}, t.texture);
        if (tex != null) mask(layer, tex, Math.max(0.3f, t.textureStrength / 100f));
        File form = picture(settings, new String[] {"brushfom/blotmap", "brushfom/bristle", "brushfom/brshape"}, t.shape);
        if (form != null) mask(layer, form, 0.6f);
        new Canvas(out).drawBitmap(layer, 0, 0, null);
        layer.recycle();
        return out;
    }

    /** A brush texture: a wide stroke painted through it. */
    static Bitmap brushTexture(File tex, int w, int h) {
        Bitmap out = paper(w, h);
        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(h * 0.38f);
        p.setColor(INK);
        new Canvas(layer).drawPath(curve(w, h), p);
        mask(layer, tex, 1f);
        new Canvas(out).drawBitmap(layer, 0, 0, null);
        layer.recycle();
        return out;
    }

    /** A paper texture: the paper itself, its grain tiled over the whole sample. */
    static Bitmap paperTexture(File tex, int w, int h) {
        Bitmap out = paper(w, h);
        Bitmap m = inkMask(decode(tex, 256), 1f);
        if (m == null) return out;
        Paint p = new Paint();
        p.setShader(new BitmapShader(m, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        p.setColorFilter(new PorterDuffColorFilter(Color.argb(150, 0x55, 0x50, 0x48), PorterDuff.Mode.SRC_IN));
        new Canvas(out).drawRect(0, 0, w, h, p);
        m.recycle();
        return out;
    }

    /** A brush or scatter shape, stamped along the curve with its starting values. */
    static Bitmap stamps(File shape, Map<String, String> ini, boolean scatter, int w, int h) {
        Bitmap out = paper(w, h);
        Bitmap m = inkMask(decode(shape, 160), 1f);
        if (m == null) return out;
        float scaling = num(ini, "Scaling", 100) / 100f;
        float size = h * (scatter ? 0.26f : 0.34f) * scaling;
        float spacing = Math.max(1.5f, size * num(ini, "Spacing", scatter ? 25 : 10) / 100f);
        float scatterBy = num(ini, "Scattering", 0) / 100f;
        float sizeJitter = num(ini, "SizeJitter", 0) / 100f;
        float angle = num(ini, "Angle", 0), angleJitter = num(ini, "AngleJitter", 0) / 100f;
        int control = (int) num(ini, "AngleControl", 1);
        int alpha = (int) Math.max(25, Math.min(255, num(ini, "Density", 100) * 255 / 100));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN));
        p.setAlpha(alpha);
        Canvas c = new Canvas(out);
        PathMeasure pm = new PathMeasure(curve(w, h), false);
        float[] pos = new float[2], tan = new float[2];
        Random r = new Random(7);
        Matrix mx = new Matrix();
        for (float d = 0; d <= pm.getLength(); d += spacing) {
            pm.getPosTan(d, pos, tan);
            float s = size * (1 - sizeJitter * r.nextFloat());
            float a = angle + angleJitter * (r.nextFloat() * 360 - 180);
            if (control != 0) a += (float) Math.toDegrees(Math.atan2(tan[1], tan[0]));
            float off = scatterBy * size * 2 * (r.nextFloat() - 0.5f);
            mx.reset();
            mx.postTranslate(-m.getWidth() / 2f, -m.getHeight() / 2f);
            mx.postScale(s / Math.max(m.getWidth(), m.getHeight()), s / Math.max(m.getWidth(), m.getHeight()));
            mx.postRotate(a);
            mx.postTranslate(pos[0] - tan[1] * off, pos[1] + tan[0] * off);
            c.drawBitmap(m, mx, p);
        }
        m.recycle();
        return out;
    }

    /** The tool's texture and brush shape as 0..1 per pixel of a w×h pad,
     *  tiled from its top left the way the samples paint through them;
     *  null when it has neither. */
    static float[] padMask(File settings, SaiSettingsFiles.Tool t, int w, int h) {
        float[] out = null;
        File tex = picture(settings, new String[] {"brushtex"}, t.texture);
        if (tex != null) out = tile(out, inkMask(decode(tex, 256), Math.max(0.3f, t.textureStrength / 100f)), w, h);
        File form = picture(settings, new String[] {"brushfom/blotmap", "brushfom/bristle", "brushfom/brshape"}, t.shape);
        if (form != null) out = tile(out, inkMask(decode(form, 256), 0.6f), w, h);
        return out;
    }

    /** m's alpha (0..1) tiled over w×h from the top left, times what is in
     *  `into` already (null: a fresh one); m is recycled. */
    static float[] tile(float[] into, Bitmap m, int w, int h) {
        if (m == null) return into;
        int mw = m.getWidth(), mh = m.getHeight();
        int[] px = new int[mw * mh];
        m.getPixels(px, 0, mw, 0, 0, mw, mh);
        m.recycle();
        if (into == null) { into = new float[w * h]; java.util.Arrays.fill(into, 1f); }
        for (int y = 0; y < h; y++) {
            int row = (y % mh) * mw;
            for (int x = 0; x < w; x++) into[y * w + x] *= (px[row + x % mw] >>> 24) / 255f;
        }
        return into;
    }

    /* ---- pieces ---- */

    /** settings/<one of folders>/<name>.bmp, any letter case; null when not there. */
    static File picture(File settings, String[] folders, String name) {
        if (name == null || name.isEmpty()) return null;
        String want = name.toLowerCase(Locale.ROOT) + ".bmp";
        for (String f : folders) {
            File[] files = new File(settings, f).listFiles();
            if (files == null) continue;
            for (File x : files) if (x.getName().toLowerCase(Locale.ROOT).equals(want)) return x;
        }
        return null;
    }

    /** Keeps the layer only where the picture paints; strength 0..1. */
    private static void mask(Bitmap layer, File picture, float strength) {
        Bitmap m = inkMask(decode(picture, 256), strength);
        if (m == null) return;
        Paint p = new Paint();
        p.setShader(new BitmapShader(m, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        new Canvas(layer).drawRect(0, 0, layer.getWidth(), layer.getHeight(), p);
        m.recycle();
    }

    /** Black with the picture's painting parts as alpha. */
    static Bitmap inkMask(Bitmap b, float strength) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        long sum = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int lum = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
            px[i] = lum;
            sum += lum;
        }
        boolean light = sum / Math.max(1, px.length) > 127;
        for (int i = 0; i < px.length; i++) {
            int ink = light ? 255 - px[i] : px[i];
            int a = Math.round(255 - strength * (255 - ink));
            px[i] = a << 24;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** The picture scaled down by a power of two to about `size`. */
    static Bitmap decode(File f, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    private static float num(Map<String, String> ini, String key, float fallback) {
        String v = ini.get(key);
        if (v == null) return fallback;
        try { return Float.parseFloat(v.replace("+", "")); }
        catch (NumberFormatException e) { return fallback; }
    }
}
