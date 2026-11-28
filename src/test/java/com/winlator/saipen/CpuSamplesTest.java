package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CpuSamplesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CpuSamples start(File dir, long seed) {
        return new CpuSamples(dir, new Random(seed), 0, 2, "2026-11-28", "14:33:52", "SAI sees 2 cores, fast cores: all");
    }

    @Test
    public void aSessionLineThenSamplesOneToFiveMinutesApart() throws Exception {
        File dir = tmp.newFolder();
        CpuSamples s = start(dir, 7);
        for (long t = 1000; t <= 30 * 60_000L; t += 1000) s.tick(t, 60, 100, "14:40:00");
        List<String> lines = CpuSamples.lines(dir);
        assertEquals("session 2026-11-28 14:33:52: SAI sees 2 cores, fast cores: all, all SAI can use 200%", lines.get(0));
        int samples = lines.size() - 1;
        /* 30 minutes at one line every 1 to 5 minutes */
        assertTrue("samples: " + samples, samples >= 6 && samples <= 30);
        assertTrue(lines.get(1), lines.get(1).contains("SAI 60% now, 60% on average and 60% at most"));
        assertTrue(lines.get(1), lines.get(1).contains("all of SAI Pen 100% on average"));
    }

    @Test
    public void notesAndTheEnd() throws Exception {
        File dir = tmp.newFolder();
        CpuSamples s = start(dir, 1);
        s.tick(1000, 50, 80, "14:33:53");
        s.tick(2000, 199, 220, "14:33:54");
        s.note("14:39:10", 316_000, "SAI full (199%) for 10 s: let onto every core");
        s.finish(379_000, "14:40:11", 70_000, 70_000);
        List<String> lines = CpuSamples.lines(dir);
        assertEquals("  14:39:10, 5 min in: SAI full (199%) for 10 s: let onto every core", lines.get(1));
        assertEquals("  14:40:11, ended after 6 min: SAI 124% on average, 199% at most, all it can use for 70 s (longest 70 s in one go)",
                     lines.get(2));
    }

    @Test
    public void keepsTheNewestLines() throws Exception {
        File dir = tmp.newFolder();
        for (int i = 0; i < CpuSamples.MAX_LINES + 10; i++) start(dir, i);
        List<String> lines = CpuSamples.lines(dir);
        assertEquals(CpuSamples.MAX_LINES, lines.size());
    }

    @Test
    public void nothingPrivateGetsThroughTheReport() {
        String line = "  14:39:10, 5 min in: SAI full (199%) for 10 s: let onto every core";
        assertEquals(line.trim(), BugReport.safe(line));
    }
}
