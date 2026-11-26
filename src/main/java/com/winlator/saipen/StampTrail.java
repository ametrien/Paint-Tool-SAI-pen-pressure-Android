package com.winlator.saipen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Where a brush shape or scatter shape lands along a pen stroke, for the
 * "Try it" pad of a picture (PicturePad): one stamp after another, their
 * size, angle, place and colour shift from the picture's starting values
 * (NAME.ini) the way SAI's own ini comments describe them. CLOSE TO SAI,
 * NOT SAI: SAI's engine is its own; what it does with each value between
 * the documented ranges is a guess:
 *
 *  - Spacing: the gap between stamps, % of the stamp's size.
 *  - Scattering: how far a stamp may land off the stroke, % of the brush
 *    size; across the stroke only, or all around (AllDirScattering), evenly
 *    or bunched near the stroke (GaussianDistribution).
 *  - Scaling (% of the brush size) or ShapeSize (px, AbsoluteSize=1).
 *  - SizeJitter, AngleJitter, Count and CountJitter, WxHRatio and
 *    WxHJitter, Hue/Saturation/BrightnessJitter: random amounts up to that.
 *  - AngleControl: 0 a fixed angle; 1 (Auto) and 2 (pen direction) both
 *    follow the stroke here: the pen's tilt is not read.
 *
 * Pictures without an ini (brushfom/bristle and blotmap) get settings that
 * look like SAI's: bristles dragged close together along the stroke, blots
 * a little further apart.
 *
 * No Android types: the JVM tests run it on the Mac.
 */
final class StampTrail {

    /** The picture's values, as fractions and degrees. */
    static final class Settings {
        double spacing = 0.25, scattering, scaling = 1, sizeJitter, angle, angleJitter;
        int angleControl = 1, count = 1;
        double countJitter, aspect = 1, aspectJitter;
        boolean allDirections, gaussian, absoluteSize;
        double shapeSize = 50;
        /** Brush shapes only: how dark a stroke gets at most, 0..1. */
        double density = 1;
        double hueJitter, saturationJitter, brightnessJitter;
        /** Colour jitter once per shape (true) or once per stroke. */
        boolean jitterEachShape;

        /** Every value from an ini, the kind's defaults for the rest.
         *  folder: SAI's folder of the picture ("scatter", "brushfom/bristle"...). */
        static Settings of(Map<String, String> ini, String folder) {
            Settings s = new Settings();
            String f = folder == null ? "" : folder.toLowerCase(Locale.ROOT);
            boolean scatter = f.startsWith("scatter");
            if (f.endsWith("bristle")) s.spacing = 0.04;
            else if (f.endsWith("blotmap")) s.spacing = 0.12;
            double maxSpacing = scatter ? 10 : 0.5, maxScatter = scatter ? 10 : 0.5;
            s.spacing = clamp(num(ini, "Spacing", s.spacing * 100) / 100, 0.01, maxSpacing);
            s.scattering = clamp(num(ini, "Scattering", 0) / 100, 0, maxScatter);
            s.scaling = clamp(num(ini, "Scaling", 100) / 100, 0.01, 2);
            s.sizeJitter = clamp(num(ini, "SizeJitter", 0) / 100, 0, 1);
            s.angle = clamp(num(ini, "Angle", 0), -180, 180);
            s.angleJitter = clamp(num(ini, "AngleJitter", 0) / 100, 0, 1);
            s.angleControl = (int) clamp(num(ini, "AngleControl", 1), 0, 2);
            s.count = (int) clamp(num(ini, "Count", 1), 1, 20);
            s.countJitter = clamp(num(ini, "CountJitter", 0) / 100, 0, 1);
            s.aspect = ratio(ini == null ? null : ini.get("WxHRatio"));
            s.aspectJitter = clamp(num(ini, "WxHJitter", 0) / 100, 0, 1);
            s.allDirections = num(ini, "AllDirScattering", 0) != 0;
            s.gaussian = num(ini, "GaussianDistribution", 0) != 0;
            s.absoluteSize = num(ini, "AbsoluteSize", 0) != 0;
            s.shapeSize = clamp(num(ini, "ShapeSize", 50), 1, 5000);
            s.density = scatter ? 1 : clamp(num(ini, "Density", 100) / 100, 0.05, 1);
            s.hueJitter = clamp(num(ini, "HueJitter", 0) / 100, 0, 1);
            s.saturationJitter = clamp(num(ini, "SaturationJitter", 0) / 100, 0, 1);
            s.brightnessJitter = clamp(num(ini, "BrightnessJitter", 0) / 100, 0, 1);
            s.jitterEachShape = num(ini, "ApplyToEachShape", 0) != 0;
            return s;
        }

        /** Any colour jitter at all: then the pad paints in colour, or it would not show. */
        boolean colourJitter() { return hueJitter > 0 || saturationJitter > 0 || brightnessJitter > 0; }
    }

    /** One stamp: its centre, its longer side in pixels, its angle in
     *  degrees, width and height as 0..1 of that side, and the colour shift
     *  (hue in degrees, saturation and brightness as -1..1). */
    static final class Stamp {
        final double x, y, size, angle, w, h, hue, saturation, brightness;
        Stamp(double x, double y, double size, double angle, double w, double h,
              double hue, double saturation, double brightness) {
            this.x = x; this.y = y; this.size = size; this.angle = angle; this.w = w; this.h = h;
            this.hue = hue; this.saturation = saturation; this.brightness = brightness;
        }
    }

    final Settings s;
    final double brush;
    final PressureStroke.Response r;
    private final Random random;
    private double lx, ly, lp, carry, dir;
    private boolean down, moved;
    private double strokeHue, strokeSat, strokeBright;

    /** brush: the brush size in pixels (the stamps' size before Scaling). */
    StampTrail(Settings s, double brush, PressureStroke.Response r, long seed) {
        this.s = s;
        this.brush = Math.max(1, brush);
        this.r = r;
        random = new Random(seed);
    }

    /** A stamp's size at this pressure, before its own jitter. */
    double size(double pressure) {
        double base = s.absoluteSize ? s.shapeSize : brush * s.scaling;
        return Math.max(1, base * r.size(PressureStroke.clamp01(pressure)));
    }

    /** The gap to the next stamp, at this pressure. */
    double gap(double pressure) { return Math.max(0.5, size(pressure) * s.spacing); }

    /** The pen touched down: the first stamps. */
    List<Stamp> begin(double x, double y, double pressure) {
        down = true;
        moved = false;
        lx = x; ly = y; lp = PressureStroke.clamp01(pressure);
        carry = 0;
        dir = 0;
        strokeHue = jitter(s.hueJitter) * 180;
        strokeSat = jitter(s.saturationJitter);
        strokeBright = jitter(s.brightnessJitter);
        List<Stamp> out = new ArrayList<>();
        place(out, x, y, lp);
        return out;
    }

    /** The pen moved here: stamps one gap apart along the way. */
    List<Stamp> to(double x, double y, double pressure) {
        if (!down) return begin(x, y, pressure);
        List<Stamp> out = new ArrayList<>();
        pressure = PressureStroke.clamp01(pressure);
        double len = Math.hypot(x - lx, y - ly);
        if (len <= 0) { lp = pressure; return out; }
        dir = Math.toDegrees(Math.atan2(y - ly, x - lx));
        moved = true;
        double since = carry, t = 0;
        while (true) {
            double p = lp + (pressure - lp) * (t / len);
            double need = Math.max(0, gap(p) - since);
            if (t + need > len) { carry = since + len - t; break; }
            t += need;
            since = 0;
            double f = t / len;
            place(out, lx + (x - lx) * f, ly + (y - ly) * f, lp + (pressure - lp) * f);
        }
        lx = x; ly = y; lp = pressure;
        return out;
    }

    void end() { down = false; }

    boolean down() { return down; }

    /** Count stamps (less by up to CountJitter) at one point of the stroke. */
    private void place(List<Stamp> out, double x, double y, double p) {
        int n = Math.max(1, (int) Math.round(s.count * (1 - s.countJitter * random.nextDouble())));
        for (int k = 0; k < n; k++) {
            double size = size(p) * (1 - s.sizeJitter * random.nextDouble());
            double a = s.angle + s.angleJitter * (random.nextDouble() * 360 - 180);
            if (s.angleControl != 0 && moved) a += dir;
            double[] off = offset();
            double rad = Math.toRadians(dir);
            double nx = -Math.sin(rad), ny = Math.cos(rad);
            double ox, oy;
            if (s.allDirections) { ox = off[0]; oy = off[1]; }
            else { ox = nx * off[0]; oy = ny * off[0]; }
            double w = 1, h = 1;
            double aspect = s.aspect;
            if (s.aspectJitter > 0) aspect *= Math.pow(2, s.aspectJitter * (random.nextDouble() * 2 - 1));
            if (aspect > 1) h = 1 / aspect; else w = aspect;
            double hue = s.jitterEachShape ? jitter(s.hueJitter) * 180 : strokeHue;
            double sat = s.jitterEachShape ? jitter(s.saturationJitter) : strokeSat;
            double bright = s.jitterEachShape ? jitter(s.brightnessJitter) : strokeBright;
            out.add(new Stamp(x + ox, y + oy, size, a, w, h, hue, sat, bright));
        }
    }

    /** How far off the stroke: {across, 0} or, all around, {dx, dy}. */
    private double[] offset() {
        double reach = s.scattering * brush;
        if (reach <= 0) return new double[] {0, 0};
        if (s.allDirections) {
            double d = s.gaussian ? Math.min(1, Math.abs(random.nextGaussian()) * 0.4) : Math.sqrt(random.nextDouble());
            double t = random.nextDouble() * Math.PI * 2;
            return new double[] {Math.cos(t) * d * reach, Math.sin(t) * d * reach};
        }
        double u = s.gaussian ? Math.max(-1, Math.min(1, random.nextGaussian() * 0.4)) : random.nextDouble() * 2 - 1;
        return new double[] {u * reach, 0};
    }

    /** -amount..amount, or 0 for none. */
    private double jitter(double amount) { return amount <= 0 ? 0 : amount * (random.nextDouble() * 2 - 1); }

    /** "100:50" -> 2 (wider than high); anything else 1. */
    static double ratio(String v) {
        if (v == null) return 1;
        String[] p = v.trim().split(":");
        if (p.length != 2) return 1;
        try {
            double w = Double.parseDouble(p[0].trim()), h = Double.parseDouble(p[1].trim());
            if (w <= 0 || h <= 0) return 1;
            return clamp(w / h, 0.01, 100);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static double num(Map<String, String> ini, String key, double fallback) {
        String v = ini == null ? null : ini.get(key);
        if (v == null) return fallback;
        try { return Double.parseDouble(v.trim().replace("+", "")); }
        catch (NumberFormatException e) { return fallback; }
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
}
