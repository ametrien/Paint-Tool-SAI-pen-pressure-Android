package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaiProcessWatchTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private void process(File proc, String pid, String comm) throws Exception {
        File d = new File(proc, pid);
        d.mkdirs();
        Files.write(new File(d, "comm").toPath(), (comm + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void findsSaiAmongTheAppsProcesses() throws Exception {
        File proc = tmp.newFolder("proc");
        process(proc, "32062", "sai.penpress");
        process(proc, "32151", "services.exe");
        process(proc, "32206", "sai2.exe");
        new File(proc, "self").mkdirs();                 /* not a process number */
        assertTrue(SaiProcessWatch.isRunning(proc, "sai2.exe"));
        assertTrue("the exe's name in any case", SaiProcessWatch.isRunning(proc, "SAI2.EXE"));
        assertFalse(SaiProcessWatch.isRunning(proc, "notepad.exe"));
    }

    @Test
    public void aLongNameIsMatchedAsTheKernelCutsIt() throws Exception {
        File proc = tmp.newFolder("proc");
        process(proc, "100", "sai2-alpha-2026");             /* 15 characters kept */
        assertTrue(SaiProcessWatch.isRunning(proc, "sai2-alpha-20261019.exe"));
        assertEquals("sai2-alpha-2026", SaiProcessWatch.commOf("SAI2-Alpha-20261019.exe"));
    }

    @Test
    public void noProcessesNoSai() {
        assertFalse(SaiProcessWatch.isRunning(new File(tmp.getRoot(), "missing"), "sai2.exe"));
    }

    @Test
    public void closingSaiEndsTheSessionAfterAMoment() {
        SaiProcessWatch w = new SaiProcessWatch();
        assertFalse(w.onPoll(true, 0));
        assertFalse(w.onPoll(false, 1000));                 /* gone just now */
        assertFalse(w.onPoll(false, 1000 + SaiProcessWatch.GONE_FOR_MS - 1));
        assertTrue(w.onPoll(false, 1000 + SaiProcessWatch.GONE_FOR_MS));
    }

    @Test
    public void notBeforeSaiHasEvenStarted() {
        /* THE TRAP: the first seconds of a session have no SAI yet */
        SaiProcessWatch w = new SaiProcessWatch();
        for (long t = 0; t < 30000; t += 1000) assertFalse(w.onPoll(false, t));
    }

    @Test
    public void aMomentWithoutSaiIsNotAClose() {
        SaiProcessWatch w = new SaiProcessWatch();
        w.onPoll(true, 0);
        assertFalse(w.onPoll(false, 1000));
        assertFalse(w.onPoll(true, 2000));                  /* back again */
        assertFalse(w.onPoll(false, 3000));
        assertFalse(w.onPoll(false, 3000 + SaiProcessWatch.GONE_FOR_MS - 1));
    }

    @Test
    public void readsSaisProcessorTime() throws Exception {
        File proc = tmp.newFolder("proc");
        process(proc, "32062", "sai.penpress");
        Files.write(new File(proc, "32062/stat").toPath(),
                    "32062 (sai.penpress) S 1 1 0 0 -1 0 0 0 0 0 900 100 0 0".getBytes(StandardCharsets.UTF_8));
        process(proc, "32206", "sai2.exe");
        Files.write(new File(proc, "32206/stat").toPath(),
                    "32206 (sai2.exe) R 1 1 0 0 -1 0 0 0 0 0 4000 250 0 0".getBytes(StandardCharsets.UTF_8));
        assertEquals("SAI's own, not the app's", 4250, SaiProcessWatch.cpuTicks(proc, "sai2.exe"));
        assertEquals(-1, SaiProcessWatch.cpuTicks(proc, "notepad.exe"));
    }
}
