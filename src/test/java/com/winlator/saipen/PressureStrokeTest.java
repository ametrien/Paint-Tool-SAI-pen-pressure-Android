package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PressureStrokeTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final double E = 1e-9;

    /* SAI 2's own tools, as their .saitdat files have them */
    private static final PressureStroke.Response PENCIL = new PressureStroke.Response(true, 0, 100, false, 0, 100);
    private static final PressureStroke.Response BRUSH = new PressureStroke.Response(true, 50, 100, true, 0, 150);
    private static final PressureStroke.Response ERASER = new PressureStroke.Response(true, 100, 100, false, 0, 100);
    private static final PressureStroke.Response NONE = new PressureStroke.Response(false, 0, 100, false, 0, 100);

    @Test
    public void pressureRisesFromNothingToFullAndBack() {
        assertEquals(0, PressureStroke.pressure(0), E);
        assertEquals(0, PressureStroke.pressure(1), E);
        assertEquals(1, PressureStroke.pressure(0.5), E);
        assertEquals("full by a third of the way", 1, PressureStroke.pressure(0.34), E);
        double last = 0;
        for (int i = 1; i <= 50; i++) {
            double p = PressureStroke.pressure(i / 100.0);
            assertTrue("never goes down on the way in", p >= last);
            assertEquals("the same on the way out", p, PressureStroke.pressure(1 - i / 100.0), E);
            last = p;
        }
    }

    @Test
    public void sizeGoesFromMinSizeToFull() {
        assertEquals(0, PENCIL.size(0), E);
        assertEquals(1, PENCIL.size(1), E);
        assertEquals(0.5, BRUSH.size(0), E);
        assertEquals(0.75, BRUSH.size(0.5), E);
        assertEquals("min size 100%: always full", 1, ERASER.size(0), E);
        assertEquals("size not following pressure", 1, NONE.size(0), E);
    }

    @Test
    public void densityOnlyWhenTheToolSaysSo() {
        assertEquals("pencil: density does not follow", 1, PENCIL.density(0.1), E);
        assertEquals(0, BRUSH.density(0), E);
        assertEquals(1, BRUSH.density(1), E);
    }

    @Test
    public void sensitivityAboveHundredReachesFullSooner() {
        assertEquals(0.5, PressureStroke.felt(0.5, 100), E);
        assertTrue(PressureStroke.felt(0.5, 150) > 0.5);
        assertTrue(PressureStroke.felt(0.5, 50) < 0.5);
        assertEquals(0, PressureStroke.felt(0, 200), E);
        assertEquals(1, PressureStroke.felt(1, 200), E);
        assertEquals("out of range pressure is kept in", 1, PressureStroke.felt(3, 100), E);
    }

    @Test
    public void evenOnlyWhenNothingFollowsPressure() {
        assertTrue(NONE.even());
        assertTrue("min size 100% is even too", ERASER.even());
        assertFalse(PENCIL.even());
        assertFalse(BRUSH.even());
    }

    @Test
    public void keyChangesWithWhatChangesThePicture() {
        assertNotEquals(PENCIL.key(), BRUSH.key());
        assertNotEquals(BRUSH.key(), new PressureStroke.Response(true, 50, 100, true, 0, 100).key());
        assertEquals(PENCIL.key(), new PressureStroke.Response(true, 0, 100, false, 0, 100).key());
    }

    /* ---- painted ---- */

    private static final int W = 200, H = 60;

    /** A straight stroke across the middle. */
    private static int[] straight(PressureStroke.Response r, double width, double level, int hardness) {
        int[] a = new int[W * H];
        PressureStroke.paint(a, W, H, new double[] {10, 30, 190, 30}, r, width, level, hardness);
        return a;
    }

    /** How many pixels of column x have any ink. */
    private static int thickness(int[] a, int x) {
        int n = 0;
        for (int y = 0; y < H; y++) if (a[y * W + x] > 20) n++;
        return n;
    }

    @Test
    public void aTaperedStrokeIsThinAtTheEndsAndFullInTheMiddle() {
        int[] a = straight(PENCIL, 30, 1, 50);
        assertTrue("full width in the middle", Math.abs(thickness(a, 100) - 30) <= 2);
        assertTrue("thin near the start", thickness(a, 14) < 10);
        assertTrue("thin near the end", thickness(a, 186) < 10);
        assertEquals("solid in the middle", 255, a[30 * W + 100]);
    }

    @Test
    public void minSizeKeepsTheEndsAtLeastThatWide() {
        /* density kept full here, or the ends fade out of sight */
        int[] a = straight(new PressureStroke.Response(true, 50, 100, false, 0, 100), 30, 1, 50);
        assertTrue(thickness(a, 12) >= 13);
    }

    @Test
    public void densityFadesTowardTheEndsWithoutGoingOverTheToolsDensity() {
        int[] a = straight(BRUSH, 30, 0.6, 50);
        int max = 0;
        for (int v : a) max = Math.max(max, v);
        assertTrue("never denser than the tool: overlapping dabs do not add up", max <= Math.round(0.6 * 255));
        assertTrue("faint near the start", a[30 * W + 14] < a[30 * W + 100] / 2);
    }

    @Test
    public void anEvenToolDrawsTheSameAllAlong() {
        int[] a = straight(NONE, 20, 1, 50);
        assertEquals(thickness(a, 40), thickness(a, 100));
        assertEquals(thickness(a, 160), thickness(a, 100));
    }

    @Test
    public void aSoftToolHasAWiderFadingEdge() {
        int[] hard = straight(NONE, 20, 1, 50), soft = straight(NONE, 20, 1, 0);
        int fadeHard = 0, fadeSoft = 0;
        for (int y = 0; y < H; y++) {
            int h = hard[y * W + 100], s = soft[y * W + 100];
            if (h > 0 && h < 255) fadeHard++;
            if (s > 0 && s < 255) fadeSoft++;
        }
        assertTrue(fadeSoft > fadeHard * 3);
    }

    @Test
    public void aStrokeOffThePaperDoesNotFail() {
        int[] a = new int[W * H];
        PressureStroke.paint(a, W, H, new double[] {-50, -50, 300, 100}, PENCIL, 80, 1, 0);
        PressureStroke.paint(a, W, H, new double[] {5, 5}, PENCIL, 80, 1, 0);
        PressureStroke.paint(a, W, H, new double[] {5, 5, 5, 5}, PENCIL, 80, 1, 0);
    }

    @Test
    public void everyShapeStaysOnTheSample() {
        for (int shape = 0; shape < PressureStroke.SHAPES.length; shape++) {
            for (int[] wh : new int[][] {{291, 291}, {1100, 480}, {40, 40}}) {
                int w = wh[0], h = wh[1];
                double width = PressureStroke.width(shape, 200, h);
                double[] c = PressureStroke.curve(shape, w, h, width);
                /* the line and the S end in a point; the heart is full width
                 * round its sides and must fit whole, point and all */
                double pad = shape == PressureStroke.HEART ? width / 2 : 0;
                for (int i = 0; i < c.length; i += 2) {
                    assertTrue(PressureStroke.SHAPES[shape], c[i] - pad >= -1 && c[i] + pad <= w + 1);
                    assertTrue(PressureStroke.SHAPES[shape], c[i + 1] - pad >= -1 && c[i + 1] + pad <= h + 1);
                }
            }
        }
    }

    @Test
    public void anUnknownShapeIsTheSCurve() {
        assertEquals(PressureStroke.S_CURVE, PressureStroke.shape(7));
        assertEquals(PressureStroke.S_CURVE, PressureStroke.shape(-1));
        assertEquals(PressureStroke.HEART, PressureStroke.shape(PressureStroke.HEART));
    }

    @Test
    public void theHeartPressesFullyOnTheLeftLightensAndLiftsOffAtTheEnd() {
        assertEquals(0, PressureStroke.heartPressure(0), E);
        assertEquals(0, PressureStroke.heartPressure(1), E);
        assertTrue("lands lightly", PressureStroke.heartPressure(0.005) < 0.3);
        assertEquals("full round the top of the left side", 1, PressureStroke.heartPressure(0.1418), E);
        double point = PressureStroke.heartPressure(0.5);
        assertTrue("lighter at the point", point < 0.7 && point > 0.5);
        assertTrue("lifts off at the end", PressureStroke.heartPressure(0.98) < 0.1);
        for (int i = 0; i <= 1000; i++) {
            double p = PressureStroke.heartPressure(i / 1000.0);
            assertTrue(p >= 0 && p <= 1);
        }
        double[][] k = PressureStroke.HEART_PRESSURE;
        for (int i = 1; i < k.length; i++) assertTrue("points in order along the stroke", k[i][0] > k[i - 1][0]);
        assertEquals(1, k[k.length - 1][0], E);
    }

    @Test
    public void theHeartsStrokeIsNarrowerSoItDoesNotFillIn() {
        assertTrue(PressureStroke.width(PressureStroke.HEART, 200, 300) < PressureStroke.width(PressureStroke.S_CURVE, 200, 300));
        assertEquals("thin tools the same in every shape", PressureStroke.width(PressureStroke.HEART, 1, 300),
                     PressureStroke.width(PressureStroke.LINE, 1, 300), E);
    }

    /* ---- read from a tool file ---- */

    private File tool(String body) throws Exception {
        File f = new File(tmp.getRoot(), "custool/nrm/23.saitdat");
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void aToolsPressureSettingsAreReadFromItsFile() throws Exception {
        /* SAI 2's Brush, the lines that matter, with SAI's \r\n */
        File f = tool("tidstr=S:brush\r\nname=U:Brush\r\n.\r\ncursize=I:500\r\nminsize=I:50\r\ncurdens=I:100\r\n"
            + "mindens=I:0\r\nhardness=I:50\r\nszprsf=I:1\r\ndnprsf=I:1\r\nszsens=I:100\r\ndnsens=I:150\r\n.\r\n.\r\n");
        SaiSettingsFiles.Tool t = SaiSettingsFiles.toolFrom(f, "nrm", 23, null);
        assertEquals(BRUSH.key(), t.pressure.key());
        assertEquals(0.5, t.pressure.size(0), E);
    }

    @Test
    public void aToolWithoutPressureSettingsDrawsEven() throws Exception {
        /* a line tool: no szprsf or dnprsf at all */
        File f = tool("tidstr=S:inkline\r\nname=U:Line\r\n.\r\ncursize=I:10\r\nhardness=I:50\r\n.\r\n");
        assertTrue(SaiSettingsFiles.toolFrom(f, "ink", 10, null).pressure.even());
    }

    /* ---- drawn live on the "Try it" pad ---- */

    private static int liveThickness(PressureStroke.Live l, int x) {
        int n = 0;
        for (int y = 0; y < H; y++) if (l.field.alpha(y * W + x) > 20) n++;
        return n;
    }

    @Test
    public void aLiveStrokeIsUnbrokenAndFollowsThePen() {
        PressureStroke.Live l = new PressureStroke.Live(W, H, PENCIL, 20, 1, 50);
        l.begin(10, 30, 1);
        /* pen points far apart, as when it moves fast */
        for (int x = 40; x <= 190; x += 30) l.to(x, 30, 1);
        for (int x = 12; x <= 188; x++) assertTrue("no gaps at " + x, l.field.alpha(30 * W + x) == 255);
        assertTrue(Math.abs(liveThickness(l, 100) - 20) <= 2);
    }

    @Test
    public void lightPressureDrawsThinnerLive() {
        PressureStroke.Live l = new PressureStroke.Live(W, H, PENCIL, 30, 1, 50);
        l.begin(10, 30, 0.2);
        l.to(100, 30, 0.2);
        l.to(190, 30, 1);
        assertTrue(liveThickness(l, 40) < liveThickness(l, 185) / 2);
    }

    @Test
    public void pressureEasesBetweenTwoPenPoints() {
        PressureStroke.Live l = new PressureStroke.Live(W, H, PENCIL, 40, 1, 50);
        l.begin(10, 30, 0);
        l.to(190, 30, 1);
        assertTrue(liveThickness(l, 50) < liveThickness(l, 100));
        assertTrue(liveThickness(l, 100) < liveThickness(l, 180));
    }

    @Test
    public void oneLiveStrokeNeverGoesOverTheToolsDensity() {
        PressureStroke.Live l = new PressureStroke.Live(W, H, NONE, 20, 0.5, 50);
        l.begin(20, 30, 1);
        l.to(180, 30, 1);
        l.to(20, 32, 1);
        l.to(180, 28, 1);
        for (int i = 0; i < W * H; i++) assertTrue(l.field.alpha(i) <= Math.round(0.5 * 255));
    }

    @Test
    public void onlyWhatChangedIsRedrawnAndANewStrokeStartsEmpty() {
        PressureStroke.Live l = new PressureStroke.Live(W, H, NONE, 10, 1, 50);
        l.begin(20, 30, 1);
        l.to(40, 30, 1);
        int[] r = l.field.changed();
        assertTrue(r[0] <= 15 && r[2] >= 45 && r[2] < 60);
        assertEquals("nothing new since", null, l.field.changed());
        l.to(60, 30, 1);
        r = l.field.changed();
        assertTrue("only the new part", r[0] > 30 && r[2] >= 60);
        l.end();
        l.begin(150, 30, 1);
        assertEquals("the last stroke is gone", 0, l.field.alpha(30 * W + 30));
        assertTrue(l.field.alpha(30 * W + 150) > 0);
    }

    @Test
    public void thePenStandingStillDoesNotHang() {
        PressureStroke.Live l = new PressureStroke.Live(W, H, PENCIL, 10, 1, 50);
        l.begin(50, 30, 0.5);
        l.to(50, 30, 0.9);
        l.to(50, 30, 0.1);
        l.to(-500, -500, 1);
        l.to(5000, 30, 1);
    }
}
