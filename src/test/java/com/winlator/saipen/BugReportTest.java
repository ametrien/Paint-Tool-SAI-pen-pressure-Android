package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BugReportTest {
    @Test
    public void aReportReadsLikeOne() {
        String t = new BugReport("0.5.1", 58)
            .add("Device", "samsung SM-P613, Android 14 (API 34)")
            .add("Left out", null)
            .heading("SAI Pen's settings")
            .item("Screen drawing", "Standard")
            .text();
        assertEquals("SAI Pen 0.5.1 (58)\nDevice: samsung SM-P613, Android 14 (API 34)\n\nSAI Pen's settings:\n"
            + "  Screen drawing: Standard\n", t);
    }

    @Test
    public void neverALicenceNumberOrAPath() {
        String t = new BugReport("0.5.1", 58)
            .add("Licence", "sai-000123456789.slc")
            .add("Also", "SAI_000123456789")
            .add("Bare", "000123456789")
            .add("Folder", "/storage/emulated/0/Download/sai2")
            .add("In SAI", "C:\\users\\xuser\\file.sai2")
            .text();
        assertFalse(t, t.contains("123456789"));
        assertFalse(t, t.contains("/storage"));
        assertFalse(t, t.contains("xuser"));
        assertTrue(t, t.contains("Licence: (hidden).slc"));
    }

    @Test
    public void ordinaryNumbersStay() {
        assertEquals("1200 (display 2000x1200, 60 Hz)", BugReport.safe("1200 (display 2000x1200, 60 Hz)"));
        assertEquals("Alpha.2026.10.19", BugReport.safe("Alpha.2026.10.19"));
        assertEquals("30 ms", BugReport.safe("30 ms"));
    }

    @Test
    public void saiBuildFromTheFolder() {
        assertEquals("Alpha.2026.10.19", BugReport.saiBuild("sai2-Alpha-20261019-64bit-en"));
        assertEquals("Alpha.2026.11.20", BugReport.saiBuild("sai2-Alpha-20261120-64bit-en (2)"));
        assertEquals("Preview.2026.07.02b", BugReport.saiBuild("sai2-Preview-20260702b-64bit-en"));
        assertEquals("Preview.2026.07.02b", BugReport.saiBuild("sai2-TechnicalPreview-20260702b-64bit-en"));
        assertNull(BugReport.saiBuild("My SAI"));
        assertNull(BugReport.saiBuild(null));
    }

    @Test
    public void cores() {
        assertEquals("8: 2 fast (100%, 100%), 6 slower (41%)",
            BugReport.cores(new int[]{41, 41, 41, 41, 41, 41, 100, 100}, 2, "%"));
        assertEquals("8: 1 fast (100%), 7 slower (30% to 80%)",
            BugReport.cores(new int[]{30, 30, 30, 30, 80, 80, 80, 100}, 1, "%"));
        assertEquals("4, all alike", BugReport.cores(new int[]{100, 100, 100, 100}, 0, "%"));
        assertNull(BugReport.cores(new int[0], 0, "%"));
        assertEquals("8: 2 fast (2304 MHz, 2304 MHz), 6 slower (1804 MHz)",
            BugReport.cores(new int[]{1804, 1804, 1804, 1804, 1804, 1804, 2304, 2304}, 2, " MHz"));
    }

    @Test
    public void words() {
        assertEquals("moderate (slowing down)", BugReport.thermal(2));
        assertEquals("not known", BugReport.thermal(-1));
        assertEquals("1.5", BugReport.gb(1536));
    }
}
