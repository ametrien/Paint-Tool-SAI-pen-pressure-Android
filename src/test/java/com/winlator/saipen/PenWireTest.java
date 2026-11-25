package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PenWireTest {

    @Test
    public void lineHasTheMacHelpersTenFields() {
        /* PressureCore.wireLine: "seq p x y w h ev tx n evok" */
        assertEquals("7 512 800 640 15360 8640 123 456 0 1",
                     PenWire.line(7, 512, 800, 640, 15360, 8640, 123, true, 456, 0));
    }

    @Test
    public void lineWithoutAnEventTimeSaysSo() {
        /* evok 0 and ev 0: the DLL then ignores the time instead of
         * measuring a gap from a made-up stamp */
        assertEquals("1 0 8 8 80 80 0 9 0 0", PenWire.line(1, 0, 8, 8, 80, 80, 999, false, 9, 0));
    }

    @Test
    public void timesAreMaskedLikeTheDll() {
        /* WTC_WIRE_MASK 0x7FFFFFFF: the DLL reads them with %u into 32 bits */
        long big = 0x1_8000_0005L;
        String line = PenWire.line(0, 0, 0, 0, 8, 8, big, true, big, 0);
        assertEquals("0 0 0 0 8 8 5 5 0 1", line);
    }

    @Test
    public void touchingNeverSendsZero() {
        /* THE TRAP: p == 0 means "in the air" to the DLL. A feather touch
         * that rounds to 0 must still be 1, or SAI ends the stroke. */
        assertEquals(1, PenWire.pressureLevel(0f, true, 4095));
        assertEquals(1, PenWire.pressureLevel(0.0001f, true, 4095));
        assertEquals(1, PenWire.pressureLevel(-0.2f, true, 4095));
        assertEquals(1, PenWire.pressureLevel(Float.NaN, true, 4095));
    }

    @Test
    public void hoverIsAlwaysZero() {
        assertEquals(0, PenWire.pressureLevel(0.7f, false, 4095));
    }

    @Test
    public void pressureScalesAndClamps() {
        assertEquals(2048, PenWire.pressureLevel(0.5f, true, 4095));
        assertEquals(4095, PenWire.pressureLevel(1.0f, true, 4095));
        /* some devices report a little above 1 */
        assertEquals(4095, PenWire.pressureLevel(1.3f, true, 4095));
        assertEquals(512, PenWire.pressureLevel(0.5f, true, 1023));
    }

    @Test
    public void yIsCountedUpFromTheBottom() {
        /* top-left of a 1920x1080 X screen is the TOP of the wire's y range */
        assertArrayEquals(new int[] {0, 8640, 15360, 8640}, PenWire.toWire(0f, 0f, 1920, 1080));
        assertArrayEquals(new int[] {15360, 0, 15360, 8640}, PenWire.toWire(1920f, 1080f, 1920, 1080));
        assertArrayEquals(new int[] {805, 8238, 15360, 8640}, PenWire.toWire(100.6f, 50.3f, 1920, 1080));
    }

    @Test
    public void letterboxPointsAreClampedToTheScreen() {
        assertArrayEquals(new int[] {0, 8640, 15360, 8640}, PenWire.toWire(-40f, -12f, 1920, 1080));
        assertArrayEquals(new int[] {15360, 0, 15360, 8640}, PenWire.toWire(2500f, 1300f, 1920, 1080));
    }

    @Test
    public void keepAliveOnlyWhileHoveringAndQuiet() {
        assertTrue(PenWire.keepAliveShouldResend(true, 0, 51));
        assertFalse("not after a recent send", PenWire.keepAliveShouldResend(true, 0, 50));
        assertFalse("not while touching", PenWire.keepAliveShouldResend(true, 300, 500));
        assertFalse("a longer interval for a speed test", PenWire.keepAliveShouldResend(true, 0, 150, 200));
        assertTrue(PenWire.keepAliveShouldResend(true, 0, 201, 200));
        assertFalse("not out of range", PenWire.keepAliveShouldResend(false, 0, 500));
    }

    /**
     * The shared fixture tests/fixtures/android-pen-wire.txt holds lines this
     * class produces, each with the Windows screen point the DLL must turn it
     * into. tests/test_android_wire.c parses the same lines with the DLL's
     * own wtc_parse_datagram() and wtc_sample_to_screen(). Together they pin
     * the format on both ends: change one side alone and one test fails.
     */
    @Test
    public void producesTheSharedFixtureLines() throws Exception {
        List<String> fixture = readFixture();
        /* the inputs behind each fixture line, in order:
         * seq, pressure, touching, sx, sy, W, H, evUs, evOk, txUs */
        Object[][] cases = {
            {0L, 0.5f, true, 100.6f, 50.3f, 1920, 1080, 1000L, true, 1500L},
            {1L, 0.0f, false, 0f, 0f, 1920, 1080, 2000L, true, 2100L},
            {2L, 1.0f, true, 1919.9f, 1079.9f, 1920, 1080, 3000L, true, 3001L},
            {3L, 0.001f, true, 2340f / 2, 1080f / 3, 2340, 1080, 0L, false, 77L},
            {4L, 0.25f, true, -30f, 2000f, 2340, 1080, 5L, true, 6L},
        };
        assertEquals(cases.length, fixture.size());
        for (int i = 0; i < cases.length; i++) {
            Object[] c = cases[i];
            int p = PenWire.pressureLevel((Float) c[1], (Boolean) c[2], PenWire.PMAX);
            int[] w = PenWire.toWire((Float) c[3], (Float) c[4], (Integer) c[5], (Integer) c[6]);
            String line = PenWire.line((Long) c[0], p, w[0], w[1], w[2], w[3],
                                       (Long) c[7], (Boolean) c[8], (Long) c[9], 0);
            String[] parts = fixture.get(i).split("\\|");
            assertEquals("fixture line " + i, parts[0].trim(), line);
            /* the screen point the DLL must land on: the X screen point,
             * clamped to the screen, in whole pixels */
            int ex = (int) Math.floor(Math.max(0f, Math.min((Float) c[3], (Integer) c[5])));
            int ey = (int) Math.floor(Math.max(0f, Math.min((Float) c[4], (Integer) c[6])));
            assertEquals("fixture point " + i, ex + " " + ey + " " + p, parts[1].trim());
        }
    }

    private List<String> readFixture() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("android-pen-wire.txt");
        if (in == null) throw new AssertionError("android-pen-wire.txt not on the test classpath");
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String s;
            while ((s = r.readLine()) != null) {
                if (s.isEmpty() || s.startsWith("#")) continue;
                lines.add(s);
            }
        }
        return lines;
    }
}
