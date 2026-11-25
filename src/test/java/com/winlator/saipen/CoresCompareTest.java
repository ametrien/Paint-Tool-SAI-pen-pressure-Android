package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CoresCompareTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A speed log as SpeedSession writes it; `mode` null: a log from before 0.4.7. */
    private static String log(String mode, int... toScreen) {
        StringBuilder s = new StringBuilder("SAI Pen 0.4.8 speed log, 2026-11-19 10-43-30\n");
        s.append("samsung SM-P613, Android 14, 8 cores\n");
        if (mode != null) s.append("cores: cpu0-5 467, cpu6-7 1024; fast cores cpu6-7; SAI's threads: ").append(mode).append('\n');
        s.append("\n   8.2s  draws 3  cpu SAI 70% app 105%\n");
        for (int i = 0; i < toScreen.length; i++) {
            s.append(String.format("  %d.0s  stroke %d  106 ms  ink 29 ms  to screen %d ms (input 6, SAI 20, frame 3)"
                                   + "  draws/s 9  frames/s 84  tail 0 ms\n", 20 + i, i + 1, toScreen[i]));
        }
        /* a stroke SAI never drew: no number to count */
        s.append("  80.0s  stroke 99  84 ms  ink none  to screen ?  draws/s 0  frames/s 71  tail 0 ms\n");
        s.append("summary\n  touch to screen, median: 29 ms   slowest 10%: 48 ms\n");
        return s.toString();
    }

    private static int[] repeat(int first10, int rest, int restCount) {
        int[] out = new int[10 + restCount];
        Arrays.fill(out, 0, 10, first10);
        Arrays.fill(out, 10, out.length, rest);
        return out;
    }

    @Test
    public void eachLogCountsForItsChoice() {
        Map<SaiCores.Mode, CoresCompare.Result> r = CoresCompare.fromTexts(Arrays.asList(
            log("threads", repeat(40, 28, 25)),
            log("all", repeat(40, 24, 25)),
            log("threads", repeat(40, 26, 25))));
        assertEquals(2, r.get(SaiCores.Mode.THREADS).sessions);
        assertEquals(50, r.get(SaiCores.Mode.THREADS).strokes());
        assertEquals(27, r.get(SaiCores.Mode.THREADS).median());
        assertEquals(1, r.get(SaiCores.Mode.ALL).sessions);
        assertEquals(24, r.get(SaiCores.Mode.ALL).median());
        assertEquals(0, r.get(SaiCores.Mode.OFF).sessions);
        assertEquals(-1, r.get(SaiCores.Mode.OFF).median());
    }

    @Test
    public void theFirstTenStrokesOfASessionDoNotCount() {
        /* THE TRAP: every session starts slow. Two short sessions of a choice
         * would show the slow start, not the choice. */
        Map<SaiCores.Mode, CoresCompare.Result> r = CoresCompare.fromTexts(Arrays.asList(
            log("all", repeat(90, 20, 20))));
        assertEquals(20, r.get(SaiCores.Mode.ALL).strokes());
        assertEquals(20, r.get(SaiCores.Mode.ALL).median());
    }

    @Test
    public void aLogFromBefore047CountsAsOff() {
        Map<SaiCores.Mode, CoresCompare.Result> r = CoresCompare.fromTexts(Arrays.asList(log(null, repeat(40, 25, 30))));
        assertEquals(1, r.get(SaiCores.Mode.OFF).sessions);
        assertEquals(25, r.get(SaiCores.Mode.OFF).median());
    }

    @Test
    public void aSessionWithOnlyFirstStrokesIsNotASession() {
        Map<SaiCores.Mode, CoresCompare.Result> r = CoresCompare.fromTexts(Arrays.asList(log("off", 30, 31, 32)));
        assertEquals(0, r.get(SaiCores.Mode.OFF).sessions);
    }

    @Test
    public void tooFewStrokesShowNoNumber() {
        Map<SaiCores.Mode, CoresCompare.Result> r = CoresCompare.fromTexts(Arrays.asList(
            log("threads", repeat(40, 22, CoresCompare.MIN_STROKES - 1))));
        assertEquals(-1, r.get(SaiCores.Mode.THREADS).median());
        r = CoresCompare.fromTexts(Arrays.asList(log("threads", repeat(40, 22, CoresCompare.MIN_STROKES))));
        assertEquals(22, r.get(SaiCores.Mode.THREADS).median());
    }

    @Test
    public void readsTheLogsFolderAndNothingElseInIt() throws Exception {
        File dir = tmp.newFolder();
        Files.write(new File(dir, "speed 2026-11-19 10-31-32 2000x1200.txt").toPath(),
                    log("threads", repeat(40, 28, 25)).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "notes.txt").toPath(), log("all", repeat(40, 1, 25)).getBytes(StandardCharsets.UTF_8));
        Map<SaiCores.Mode, CoresCompare.Result> r = CoresCompare.fromFolder(dir);
        assertEquals(1, r.get(SaiCores.Mode.THREADS).sessions);
        assertEquals(0, r.get(SaiCores.Mode.ALL).sessions);
        assertEquals(0, CoresCompare.fromFolder(new File(dir, "missing")).get(SaiCores.Mode.OFF).sessions);
    }

    @Test
    public void theChoiceIsSavedWhereSaiCoresReadsIt() throws Exception {
        File files = tmp.newFolder();
        assertEquals("no choice yet: the first one", SaiCores.CHOICES[0], SaiCores.readMode(files));
        for (SaiCores.Mode m : SaiCores.CHOICES) {
            assertEquals(true, SaiCores.writeMode(files, m));
            assertEquals(m, SaiCores.readMode(files));
        }
    }
}
