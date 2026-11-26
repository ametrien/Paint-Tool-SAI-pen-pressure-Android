package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class StampTrailTest {
    private static final double E = 1e-9;
    /** Size follows pressure from 30%, as on the pad. */
    private static final PressureStroke.Response PEN = new PressureStroke.Response(true, 30, 100, false, 100, 100);
    private static final PressureStroke.Response EVEN = new PressureStroke.Response(false, 100, 100, false, 100, 100);

    private static Map<String, String> ini(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Stars.ini as SAI ships it (scatter), the values that matter here. */
    private static Map<String, String> stars() {
        return ini("AngleControl", "0", "Angle", "+0", "AngleJitter", "12", "Scaling", "50", "ShapeSize", "50",
                   "SizeJitter", "50", "Spacing", "50", "Scattering", "100", "WxHRatio", "100:100", "Count", "1",
                   "AllDirScattering", "1", "GaussianDistribution", "0", "AbsoluteSize", "0", "HueJitter", "100",
                   "ApplyToEachShape", "1");
    }

    @Test
    public void settingsFromAnIni() {
        StampTrail.Settings s = StampTrail.Settings.of(stars(), "scatter");
        assertEquals(0.5, s.spacing, E);
        assertEquals(1.0, s.scattering, E);
        assertEquals(0.5, s.scaling, E);
        assertEquals(0.5, s.sizeJitter, E);
        assertEquals(0.12, s.angleJitter, E);
        assertEquals(0, s.angleControl);
        assertTrue(s.allDirections);
        assertFalse(s.gaussian);
        assertEquals(1.0, s.density, E);
        assertTrue(s.colourJitter());
        assertTrue(s.jitterEachShape);

        /* Water Blur.ini (brushfom/brshape): density counts; out of range is kept in range */
        StampTrail.Settings w = StampTrail.Settings.of(ini("Density", "50", "Spacing", "4", "Scattering", "900"), "brushfom/brshape");
        assertEquals(0.5, w.density, E);
        assertEquals(0.04, w.spacing, E);
        assertEquals(0.5, w.scattering, E);
        assertFalse(w.colourJitter());
    }

    @Test
    public void picturesWithoutAnIniGetTheirKindsLook() {
        assertEquals(0.04, StampTrail.Settings.of(new HashMap<>(), "brushfom/bristle").spacing, E);
        assertEquals(0.12, StampTrail.Settings.of(new HashMap<>(), "brushfom/blotmap").spacing, E);
        assertEquals(0.25, StampTrail.Settings.of(null, "scatter").spacing, E);
        assertEquals(1, StampTrail.Settings.of(null, "brushfom/bristle").angleControl);
    }

    @Test
    public void stampsOneGapApart() {
        StampTrail.Settings s = StampTrail.Settings.of(ini("Spacing", "25"), "brushfom/brshape");
        StampTrail t = new StampTrail(s, 40, EVEN, 1);
        assertEquals(10, t.gap(1), E);
        List<StampTrail.Stamp> all = new ArrayList<>(t.begin(0, 0, 1));
        assertEquals(1, all.size());
        /* 100 px in two moves: a stamp every 10 px, the gap carried over */
        all.addAll(t.to(55, 0, 1));
        all.addAll(t.to(100, 0, 1));
        assertEquals(11, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i * 10, all.get(i).x, 1e-6);
            assertEquals(0, all.get(i).y, 1e-6);
            assertEquals(40, all.get(i).size, 1e-6);
        }
        t.end();
        assertFalse(t.down());
    }

    @Test
    public void pressureMakesThemSmallerAndCloser() {
        StampTrail t = new StampTrail(StampTrail.Settings.of(null, "brushfom/brshape"), 40, PEN, 1);
        assertEquals(40, t.size(1), E);
        assertEquals(12, t.size(0), E);
        assertTrue(t.gap(0) < t.gap(1));
        /* ShapeSize instead of the brush, when the ini says so */
        StampTrail abs = new StampTrail(StampTrail.Settings.of(ini("AbsoluteSize", "1", "ShapeSize", "80"), "scatter"), 40, EVEN, 1);
        assertEquals(80, abs.size(1), E);
    }

    @Test
    public void anglesFollowTheStrokeUnlessFixed() {
        StampTrail follow = new StampTrail(StampTrail.Settings.of(ini("AngleControl", "1", "Spacing", "50"), "scatter"), 20, EVEN, 1);
        follow.begin(0, 0, 1);
        for (StampTrail.Stamp st : follow.to(0, 100, 1)) assertEquals(90, st.angle, 1e-6);
        StampTrail fixed = new StampTrail(StampTrail.Settings.of(ini("AngleControl", "0", "Angle", "+30", "Spacing", "50"), "scatter"), 20, EVEN, 1);
        fixed.begin(0, 0, 1);
        for (StampTrail.Stamp st : fixed.to(0, 100, 1)) assertEquals(30, st.angle, 1e-6);
    }

    @Test
    public void scatteringStaysWithinReach() {
        /* across the stroke only: along a line to the right, only y moves */
        StampTrail across = new StampTrail(StampTrail.Settings.of(ini("Scattering", "100", "Spacing", "10"), "scatter"), 30, EVEN, 3);
        across.begin(0, 0, 1);
        boolean off = false;
        for (StampTrail.Stamp st : across.to(300, 0, 1)) {
            assertTrue(Math.abs(st.y) <= 30 + 1e-6);
            off |= Math.abs(st.y) > 1;
        }
        assertTrue(off);
        /* all around: within a circle of the brush size times scattering */
        StampTrail all = new StampTrail(StampTrail.Settings.of(ini("Scattering", "200", "Count", "20",
                                                                   "AllDirScattering", "1"), "scatter"), 30, EVEN, 3);
        List<StampTrail.Stamp> at = all.begin(100, 100, 1);
        assertEquals(20, at.size());
        boolean above = false, below = false;
        for (StampTrail.Stamp st : at) {
            assertTrue(Math.hypot(st.x - 100, st.y - 100) <= 60 + 1e-6);
            above |= st.y < 99; below |= st.y > 101;
        }
        assertTrue(above && below);
    }

    @Test
    public void countJitterAndAspect() {
        StampTrail.Settings s = StampTrail.Settings.of(ini("Count", "4", "Spacing", "100", "WxHRatio", "100:50",
                                                           "SizeJitter", "50"), "scatter");
        StampTrail t = new StampTrail(s, 20, EVEN, 5);
        List<StampTrail.Stamp> first = t.begin(0, 0, 1);
        assertEquals(4, first.size());
        for (StampTrail.Stamp st : first) {
            assertEquals(1, st.w, E);
            assertEquals(0.5, st.h, E);
            assertTrue(st.size <= 20 + E && st.size >= 10 - E);
        }
        assertEquals(2.0, StampTrail.ratio("100:50"), E);
        assertEquals(0.5, StampTrail.ratio(" 50 : 100 "), E);
        assertEquals(1.0, StampTrail.ratio("odd"), E);
        assertEquals(1.0, StampTrail.ratio(null), E);
    }

    @Test
    public void colourJitterPerStrokeOrPerShape() {
        StampTrail each = new StampTrail(StampTrail.Settings.of(stars(), "scatter"), 20, EVEN, 9);
        each.begin(0, 0, 1);
        List<StampTrail.Stamp> a = each.to(200, 0, 1);
        boolean differ = false;
        for (StampTrail.Stamp st : a) {
            assertTrue(Math.abs(st.hue) <= 180);
            differ |= Math.abs(st.hue - a.get(0).hue) > 1;
        }
        assertTrue(differ);
        Map<String, String> once = stars();
        once.put("ApplyToEachShape", "0");
        StampTrail stroke = new StampTrail(StampTrail.Settings.of(once, "scatter"), 20, EVEN, 9);
        stroke.begin(0, 0, 1);
        List<StampTrail.Stamp> b = stroke.to(200, 0, 1);
        for (StampTrail.Stamp st : b) assertEquals(b.get(0).hue, st.hue, E);
    }

    @Test
    public void theSameSeedTheSameStroke() {
        StampTrail x = new StampTrail(StampTrail.Settings.of(stars(), "scatter"), 20, PEN, 42);
        StampTrail y = new StampTrail(StampTrail.Settings.of(stars(), "scatter"), 20, PEN, 42);
        x.begin(5, 5, 0.5); y.begin(5, 5, 0.5);
        List<StampTrail.Stamp> a = x.to(150, 80, 0.9), b = y.to(150, 80, 0.9);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).x, b.get(i).x, E);
            assertEquals(a.get(i).angle, b.get(i).angle, E);
        }
    }

    @Test
    public void picturesFolderForTheirPad() {
        assertEquals("scatter", SettingsContents.folderOf(SettingsContents.SCATTER, new File("s/scatter/Stars.bmp")));
        assertEquals("brushfom/bristle", SettingsContents.folderOf(SettingsContents.BRUSH_SHAPES, new File("s/brushfom/bristle/Flat.bmp")));
        assertEquals("brushfom/blotmap", SettingsContents.folderOf(SettingsContents.BRUSH_SHAPES,
            new File("SAIv2/hidden-from-sai/brushfom/BlotMap/Blots.bmp")));
        assertEquals("brushfom/brshape", SettingsContents.folderOf(SettingsContents.BRUSH_SHAPES, new File("s/brushfom/brshape/Water Blur.bmp")));
        assertEquals("papertex", SettingsContents.folderOf(SettingsContents.PAPER_TEXTURES, new File("x.bmp")));
    }
}
