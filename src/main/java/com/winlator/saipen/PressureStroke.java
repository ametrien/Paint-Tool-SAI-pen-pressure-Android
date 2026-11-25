package com.winlator.saipen;

/**
 * The sample stroke of a tool, the way a pen draws it: pressure rises from
 * nothing, is full in the middle and goes back to nothing, and the tool's
 * own pressure settings decide what that does to its size and density.
 * No Android in here (it is tested on the JVM); SamplePreview puts the
 * result on paper and paints textures and shapes through it.
 *
 * WHAT A .saitdat SAYS ABOUT PRESSURE (read from SAI 2's own files):
 *   szprsf=1   size follows pressure     minsize  size at no pressure, %
 *   dnprsf=1   density follows pressure  mindens  density at no pressure, %
 *   szsens / dnsens  how quickly it gets there, % (100 = straight)
 * SAI's own pressure curve (its Options) is the user's, not the tool's,
 * and is left out: the sample shows the tool, not one person's pen.
 *
 * HOW IT IS PAINTED: round dabs close together along the curve, each one
 * the stroke's size and density at that point. Where dabs overlap the
 * darker one wins (not darker and darker), the way one SAI stroke never
 * gets denser than its density.
 */
final class PressureStroke {
    private PressureStroke() {}

    /** A tool's pressure settings. */
    static final class Response {
        final boolean size, density;
        final double minSize, minDensity, sizeSens, densitySens;

        Response(boolean size, int minSizePct, int sizeSensPct, boolean density, int minDensityPct, int densitySensPct) {
            this.size = size;
            this.density = density;
            this.minSize = clamp01(minSizePct / 100.0);
            this.minDensity = clamp01(minDensityPct / 100.0);
            this.sizeSens = sizeSensPct > 0 ? sizeSensPct : 100;
            this.densitySens = densitySensPct > 0 ? densitySensPct : 100;
        }

        /** Size at this pressure, 0..1 of the tool's size. */
        double size(double pressure) { return size ? minSize + (1 - minSize) * felt(pressure, sizeSens) : 1; }

        /** Density at this pressure, 0..1 of the tool's density. */
        double density(double pressure) { return density ? minDensity + (1 - minDensity) * felt(pressure, densitySens) : 1; }

        /** For a preview's cache key: what changes the picture. */
        String key() { return (size ? "s" + minSize + "/" + sizeSens : "-") + (density ? "d" + minDensity + "/" + densitySens : "-"); }

        /** Neither follows pressure: the old even stroke. */
        boolean even() { return !(size && minSize < 1) && !(density && minDensity < 1); }
    }

    /** The sample's shape, the person's choice: a straight line, the S, a heart. */
    static final int LINE = 0, S_CURVE = 1, HEART = 2;
    static final String[] SHAPES = {"Line", "S-curve", "Heart"};

    static int shape(int n) { return n >= LINE && n <= HEART ? n : S_CURVE; }

    /** The pen's pressure along a stroke, at 0..1 of its length: from
     *  nothing up to full by a third of the way, full, and back down. */
    static double pressure(double t) {
        if (t <= 0 || t >= 1) return 0;
        double edge = Math.min(t, 1 - t) / 0.34;
        if (edge >= 1) return 1;
        return edge * edge * (3 - 2 * edge);
    }

    /** The heart's pressure, drawn in one go, as the owner set it in the
     *  heart editor (2026-11-25): the pen lands lightly at the dip, presses
     *  fully round the top of the left side, lets up gently down to the
     *  point, keeps a middle pressure up the right side and lifts off to
     *  nothing at the end. Points are (along the stroke, pressure), eased
     *  between. */
    static final double[][] HEART_PRESSURE = {
        {0, 0.1734}, {0.0249, 0.4961}, {0.0724, 0.9445}, {0.1418, 1}, {0.2092, 0.9338}, {0.2904, 0.7529},
        {0.3804, 0.6377}, {0.4282, 0.595}, {0.545, 0.5994}, {0.6547, 0.6422}, {0.7544, 0.71}, {0.8779, 0.45},
        {0.9742, 0.0952}, {1, 0},
    };

    static double heartPressure(double t) {
        if (t <= 0 || t >= 1) return 0;
        for (int i = 1; i < HEART_PRESSURE.length; i++) {
            double x0 = HEART_PRESSURE[i - 1][0], y0 = HEART_PRESSURE[i - 1][1];
            double x1 = HEART_PRESSURE[i][0], y1 = HEART_PRESSURE[i][1];
            if (t <= x1) return y0 + (y1 - y0) * ease((t - x0) / Math.max(1e-6, x1 - x0));
        }
        return 0;
    }

    static double pressure(int shape, double t) { return shape == HEART ? heartPressure(t) : pressure(t); }

    private static double ease(double e) {
        e = clamp01(e);
        return e * e * (3 - 2 * e);
    }

    /** Pressure as the tool feels it: above 100% sensitivity it reaches
     *  full sooner, below it later. */
    static double felt(double pressure, double sensPct) {
        double p = clamp01(pressure);
        if (sensPct == 100 || p == 0 || p == 1) return p;
        return Math.pow(p, 100.0 / sensPct);
    }

    /* ---- the curves ---- */

    /** The tool's full width in the sample: SAI's sizes go from 1 to
     *  hundreds of pixels, so small ones stay thin and big ones as wide as
     *  fits. The heart's stroke goes round on itself, so narrower there. */
    static double width(int shape, double size, int h) {
        return Math.max(1.5, Math.min(size * h / 150.0, h * (shape == HEART ? 0.16 : 0.40)));
    }

    /** The sample's curve as points x0,y0,x1,y1…, the same each time. */
    static double[] curve(int shape, int w, int h, double width) {
        switch (shape) {
            case LINE: return new double[] {w * 0.10, h * 0.5, w * 0.90, h * 0.5};
            case HEART: return heart(w, h, width);
            default: return sCurve(w, h);
        }
    }

    static double[] sCurve(int w, int h) {
        return cubic(w * 0.12, h * 0.70, w * 0.34, h * 0.06, w * 0.62, h * 0.98, w * 0.88, h * 0.30, 96);
    }

    /**
     * A heart in one stroke, the way a hand draws one: from just past the
     * dip at the top, round the left side, down to the point, up the right
     * side and a little past the dip again so the ends cross, tilted a few
     * degrees so it is not stiff. The textbook heart curve with its sides a
     * little fuller (sin^2.6 for sin^3); it spans 34 units across and 31
     * down with the crossing.
     */
    static double[] heart(int w, int h, double width) {
        double s = Math.max(1, Math.min((w * 0.8 - width) * 17 / 34, (h * 0.84 - width) * 17 / 31));
        double cx = w / 2.0, cy = h / 2.0;
        double tilt = Math.toRadians(-7), cos = Math.cos(tilt), sin = Math.sin(tilt);
        double t0 = -0.10, t1 = 2 * Math.PI + 0.22;
        int n = 240;
        double[] p = new double[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double t = t0 + (t1 - t0) * i / n, st = Math.sin(t);
            double x = -16.5 * Math.signum(st) * Math.pow(Math.abs(st), 2.6);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            double ux = x * s / 17, uy = -(y + 2.5) * s / 17;
            p[i * 2] = cx + ux * cos - uy * sin;
            p[i * 2 + 1] = cy + ux * sin + uy * cos;
        }
        return p;
    }

    static double[] cubic(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3, int n) {
        double[] p = new double[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n, u = 1 - t;
            p[i * 2] = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3;
            p[i * 2 + 1] = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3;
        }
        return p;
    }

    /* ---- painting ---- */

    /**
     * Paints one stroke into `alpha` (w*h, 0..255, kept where higher).
     * width: the tool's full width in pixels; level: its density 0..1;
     * hardness: SAI's 0..100 (50 and up is a crisp edge).
     */
    static void paint(int[] alpha, int w, int h, double[] curve, Response r,
                      double width, double level, int hardness) {
        paint(alpha, w, h, curve, S_CURVE, r, width, level, hardness);
    }

    /** The same, with the pressure of that shape's stroke. */
    static void paint(int[] alpha, int w, int h, double[] curve, int shape, Response r,
                      double width, double level, int hardness) {
        int n = curve.length / 2;
        if (n < 2) return;
        double[] at = new double[n];
        for (int i = 1; i < n; i++) {
            at[i] = at[i - 1] + Math.hypot(curve[i * 2] - curve[i * 2 - 2], curve[i * 2 + 1] - curve[i * 2 - 1]);
        }
        double length = at[n - 1];
        if (length <= 0) return;
        double soft = Math.max(0, 50 - hardness) / 60.0;
        Field f = new Field(w, h);
        int seg = 0;
        double d = 0;
        while (d <= length) {
            while (seg < n - 2 && at[seg + 1] < d) seg++;
            double span = at[seg + 1] - at[seg], k = span > 0 ? (d - at[seg]) / span : 0;
            double x = curve[seg * 2] + (curve[seg * 2 + 2] - curve[seg * 2]) * k;
            double y = curve[seg * 2 + 1] + (curve[seg * 2 + 3] - curve[seg * 2 + 1]) * k;
            double p = pressure(shape, d / length);
            double radius = width / 2 * r.size(p);
            f.dab(x, y, radius, (float) (level * r.density(p)), soft);
            /* a tenth of the dab apart: edges smooth to well under a pixel */
            d += Math.max(0.35, radius * 0.1);
        }
        f.into(alpha);
    }

    /**
     * Where the stroke covers (the most any dab covers) and how dense it is
     * there: the dabs around it averaged, the nearer the more. Taking the
     * densest dab instead draws each dab's rim as a faint ring where the
     * density fades; the nearest one alone, fine streaks fanning out where
     * the stroke bends.
     */
    static final class Field {
        final int w, h;
        final float[] cover, sum, weight;
        /** What dabs touched: since the last changed() (to redraw) and since
         *  the last clear() (to clear); empty when x0 > x1. */
        int cx0, cy0, cx1 = -1, cy1 = -1, sx0, sy0, sx1 = -1, sy1 = -1;

        Field(int w, int h) {
            this.w = w; this.h = h;
            cover = new float[w * h];
            sum = new float[w * h];
            weight = new float[w * h];
        }

        /** One round dab: full inside, an edge `soft` × its width wide (at
         *  least a pixel), thinner than a pixel painted fainter. */
        void dab(double x, double y, double radius, float density, double soft) {
            if (radius <= 0 || density <= 0) return;
            double edge = Math.max(1, soft * radius * 2);
            double reach = radius + edge / 2;
            double thin = Math.min(1, radius * 2);
            int x0 = Math.max(0, (int) Math.floor(x - reach)), x1 = Math.min(w - 1, (int) Math.ceil(x + reach));
            int y0 = Math.max(0, (int) Math.floor(y - reach)), y1 = Math.min(h - 1, (int) Math.ceil(y + reach));
            if (x0 > x1 || y0 > y1) return;
            if (cx0 > cx1) { cx0 = x0; cy0 = y0; cx1 = x1; cy1 = y1; }
            else { cx0 = Math.min(cx0, x0); cy0 = Math.min(cy0, y0); cx1 = Math.max(cx1, x1); cy1 = Math.max(cy1, y1); }
            if (sx0 > sx1) { sx0 = x0; sy0 = y0; sx1 = x1; sy1 = y1; }
            else { sx0 = Math.min(sx0, x0); sy0 = Math.min(sy0, y0); sx1 = Math.max(sx1, x1); sy1 = Math.max(sy1, y1); }
            for (int py = y0; py <= y1; py++) {
                double dy = py + 0.5 - y;
                int row = py * w;
                for (int px = x0; px <= x1; px++) {
                    double dx = px + 0.5 - x;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    double v = (reach - dist) / edge;
                    if (v <= 0) continue;
                    v = v < 1 ? v * v * (3 - 2 * v) : 1;
                    int i = row + px;
                    float c = (float) (v * thin);
                    if (c > cover[i]) cover[i] = c;
                    double k = 1 - dist / reach;
                    float wt = (float) (k * k);
                    sum[i] += wt * density;
                    weight[i] += wt;
                }
            }
        }

        /** 0..255 at one pixel. */
        int alpha(int i) { return weight[i] > 0 ? (int) (cover[i] * sum[i] / weight[i] * 255 + 0.5) : 0; }

        /** What changed since the last call, as {x0, y0, x1, y1} (inclusive),
         *  or null when nothing did. */
        int[] changed() {
            if (cx0 > cx1) return null;
            int[] r = {cx0, cy0, cx1, cy1};
            cx1 = -1; cx0 = 0;
            return r;
        }

        /** Empty again, for the next stroke: only where the last one was. */
        void clear() {
            if (sx0 <= sx1) {
                for (int y = sy0; y <= sy1; y++) {
                    int from = y * w + sx0, to = y * w + sx1 + 1;
                    java.util.Arrays.fill(cover, from, to, 0f);
                    java.util.Arrays.fill(sum, from, to, 0f);
                    java.util.Arrays.fill(weight, from, to, 0f);
                }
            }
            sx1 = -1; sx0 = 0; cx1 = -1; cx0 = 0;
        }

        /** Into 0..255 alpha, kept where `alpha` is already higher. */
        void into(int[] alpha) {
            for (int i = 0; i < alpha.length; i++) {
                if (weight[i] <= 0) continue;
                int a = (int) (cover[i] * sum[i] / weight[i] * 255 + 0.5);
                if (a > alpha[i]) alpha[i] = a;
            }
        }
    }

    /**
     * A stroke drawn live, point by point as the pen moves (the "Try it" pad):
     * dabs the same way as the samples, the pressure between two pen points
     * eased along, and only what changed to redraw.
     */
    static final class Live {
        final Field field;
        final Response r;
        final double width, level, soft;
        private double lx, ly, lp, carry;
        private boolean down;

        /** width: the tool's full width in pixels; level: its density 0..1. */
        Live(int w, int h, Response r, double width, double level, int hardness) {
            field = new Field(w, h);
            this.r = r;
            this.width = width;
            this.level = level;
            soft = Math.max(0, 50 - hardness) / 60.0;
        }

        void begin(double x, double y, double pressure) {
            field.clear();
            down = true;
            lx = x; ly = y; lp = clamp01(pressure);
            carry = 0;
            dab(x, y, lp);
        }

        /** The pen moved here: dabs along the way, a tenth of a dab apart. */
        void to(double x, double y, double pressure) {
            if (!down) { begin(x, y, pressure); return; }
            pressure = clamp01(pressure);
            double len = Math.hypot(x - lx, y - ly);
            if (len <= 0) { lp = pressure; return; }
            /* carry: how far the last dab is behind (lx, ly); t: how far along */
            double since = carry, t = 0;
            while (true) {
                double p = lp + (pressure - lp) * (len > 0 ? t / len : 1);
                double need = Math.max(0, Math.max(0.35, width / 2 * r.size(p) * 0.1) - since);
                if (t + need > len) { carry = since + len - t; break; }
                t += need;
                since = 0;
                double f = t / len;
                dab(lx + (x - lx) * f, ly + (y - ly) * f, lp + (pressure - lp) * f);
            }
            lx = x; ly = y; lp = pressure;
        }

        void end() { down = false; }

        boolean down() { return down; }

        private void dab(double x, double y, double p) {
            field.dab(x, y, width / 2 * r.size(p), (float) (level * r.density(p)), soft);
        }
    }

    static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
