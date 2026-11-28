package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaiCoresTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File proc, cpu;
    private final Map<Integer, Long> moved = new HashMap<>();   /* tid -> last mask set */
    private final List<String> log = new ArrayList<>();
    private final Map<Integer, Long> ticks = new HashMap<>();
    private static final int PID = 25560;

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Before
    public void tablet() throws Exception {
        proc = tmp.newFolder("proc");
        cpu = tmp.newFolder("cpu");
        for (int i = 0; i < 8; i++) write(new File(cpu, "cpu" + i + "/cpu_capacity"), i >= 6 ? "1024" : "467");
        write(new File(proc, "100/comm"), "sai.penpress\n");
        write(new File(proc, PID + "/comm"), "sai2.exe\n");
    }

    private File task(int tid) {
        return new File(new File(new File(proc, "" + PID), "task"), "" + tid);
    }

    /** A thread of SAI's with `t` ticks so far, allowed on every core. */
    private void thread(int tid, long t) throws Exception {
        ticks.put(tid, t);
        write(new File(task(tid), "stat"), tid + " (sai2.exe) S 1 0 0 0 0 0 0 0 0 0 " + t + " 0 0 0 20 0 1 0\n");
        if (!new File(task(tid), "status").exists()) status(tid, 0xff);
    }

    private void status(int tid, long mask) throws Exception {
        write(new File(task(tid), "status"),
              "Name:\tsai2.exe\nCpus_allowed:\t" + Long.toHexString(mask) + "\nCpus_allowed_list:\t0-7\n");
    }

    /** What taskset does: the thread's allowed cores change. */
    private boolean fakeTaskset(int tid, long mask) {
        moved.put(tid, mask);
        try {
            status(tid, mask);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    private SaiCores cores(SaiCores.Mode mode) {
        return new SaiCores(proc, "sai2.exe", FastCores.find(cpu), mode, this::fakeTaskset, log::add);
    }

    /** One second: each thread adds its ticks, then a look. */
    private void second(SaiCores c, long now, boolean pen, int[] tids, long[] per) throws Exception {
        for (int i = 0; i < tids.length; i++) thread(tids[i], ticks.getOrDefault(tids[i], 0L) + per[i]);
        c.tick(now, pen);
    }

    @Test
    public void drawingThreadsGoToCores6And7AfterThreeSeconds() throws Exception {
        SaiCores c = cores(SaiCores.Mode.THREADS);
        assertTrue(c.describe(), c.describe().contains("fast cores cpu6-7"));
        int[] tids = {25560, 25566, 25567, 25579};
        long[] per = {25, 33, 33, 1};
        for (int tid : tids) thread(tid, 1000);
        c.tick(0, false);
        second(c, 1000, true, tids, per);
        second(c, 2000, true, tids, per);
        assertTrue("not before three seconds of drawing", moved.isEmpty());
        second(c, 3000, true, tids, per);
        assertEquals(Long.valueOf(0xc0), moved.get(25566));
        assertEquals(Long.valueOf(0xc0), moved.get(25567));
        assertEquals(Long.valueOf(0xc0), moved.get(25560));
        assertEquals("an idle thread stays where it is", null, moved.get(25579));
        assertTrue(String.join("\n", log), log.get(log.size() - 1).startsWith("cores: 3 drawing thread(s) on cpu6-7"));
    }

    @Test
    public void winePuttingThemBackOnAllCoresIsUndone() throws Exception {
        /* THE TRAP: Winlator gives SAI all cores again whenever a SAI window
         * comes up (a file dialog, say), silently */
        SaiCores c = cores(SaiCores.Mode.THREADS);
        int[] tids = {25566};
        long[] per = {50};
        thread(25566, 0);
        c.tick(0, false);
        for (int s = 1; s <= 3; s++) second(c, s * 1000, true, tids, per);
        assertEquals(Long.valueOf(0xc0), moved.get(25566));
        moved.clear();
        status(25566, 0xff);                                   /* Wine's doing */
        second(c, 4000, false, tids, new long[] {0});
        assertEquals("pinned again", Long.valueOf(0xc0), moved.get(25566));
        assertTrue(String.join("\n", log), log.stream().anyMatch(l -> l.contains("moved off the fast cores")));
    }

    @Test
    public void aPinnedThreadIsNotPinnedAgainEverySecond() throws Exception {
        SaiCores c = cores(SaiCores.Mode.THREADS);
        thread(25566, 0);
        c.tick(0, false);
        for (int s = 1; s <= 3; s++) second(c, s * 1000, true, new int[] {25566}, new long[] {50});
        moved.clear();
        for (int s = 4; s <= 8; s++) second(c, s * 1000, true, new int[] {25566}, new long[] {50});
        assertTrue("no taskset for nothing: " + moved, moved.isEmpty());
    }

    @Test
    public void allModePinsEveryThreadAtOnceAndNewOnesToo() throws Exception {
        SaiCores c = cores(SaiCores.Mode.ALL);
        thread(25560, 0);
        thread(25566, 0);
        c.tick(0, false);
        assertEquals(Long.valueOf(0xc0), moved.get(25560));
        assertEquals(Long.valueOf(0xc0), moved.get(25566));
        thread(25601, 0);
        c.tick(1000, false);
        assertEquals(Long.valueOf(0xc0), moved.get(25601));
    }

    @Test
    public void offPinsNothing() throws Exception {
        SaiCores c = cores(SaiCores.Mode.OFF);
        thread(25566, 0);
        c.tick(0, false);
        for (int s = 1; s <= 5; s++) second(c, s * 1000, true, new int[] {25566}, new long[] {90});
        assertTrue(moved.isEmpty());
    }

    @Test
    public void allCoresAlikeMeansOffWhateverTheFileSays() throws Exception {
        for (int i = 0; i < 8; i++) write(new File(cpu, "cpu" + i + "/cpu_capacity"), "1024");
        SaiCores c = cores(SaiCores.Mode.ALL);
        thread(25566, 0);
        c.tick(0, false);
        assertEquals(SaiCores.Mode.OFF, c.mode());
        assertTrue(moved.isEmpty());
        assertTrue(c.describe(), c.describe().contains("no slow cores to avoid"));
    }

    @Test
    public void beforeSaiRunsNothingHappens() throws Exception {
        new File(proc, PID + "/comm").delete();
        SaiCores c = cores(SaiCores.Mode.ALL);
        c.tick(0, false);
        assertTrue(moved.isEmpty());
        assertTrue(log.isEmpty());
    }

    @Test
    public void aFailedPinIsLoggedOnceAndNotTriedEverySecond() throws Exception {
        List<Integer> tries = new ArrayList<>();
        SaiCores c = new SaiCores(proc, "sai2.exe", FastCores.find(cpu), SaiCores.Mode.ALL,
                                  (tid, mask) -> { tries.add(tid); return false; }, log::add);
        thread(25566, 0);
        c.tick(0, false);
        c.tick(1000, false);
        c.tick(2000, false);
        assertEquals(1, tries.size());
        assertEquals(1, log.stream().filter(l -> l.contains("could not move thread 25566")).count());
    }

    @Test
    public void theModeFile() throws Exception {
        File files = tmp.newFolder("files");
        assertEquals("no file: drawing threads", SaiCores.Mode.THREADS, SaiCores.readMode(files));
        write(new File(files, SaiCores.MODE_FILE), "off\n");
        assertEquals(SaiCores.Mode.OFF, SaiCores.readMode(files));
        write(new File(files, SaiCores.MODE_FILE), " ALL \n");
        assertEquals(SaiCores.Mode.ALL, SaiCores.readMode(files));
        write(new File(files, SaiCores.MODE_FILE), "something else");
        assertEquals(SaiCores.Mode.THREADS, SaiCores.readMode(files));
    }

    @Test
    public void cpusAllowedIsReadAsHex() throws Exception {
        SaiCores c = cores(SaiCores.Mode.ALL);
        thread(25566, 0);
        c.tick(0, false);
        write(new File(task(25566), "status"), "Cpus_allowed:\t00000000,000000c0\n");
        assertEquals(0xc0, c.allowedMask(25566));
    }

    @Test
    public void aFullSaiGoesOntoEveryCoreUntilBack() throws Exception {
        SaiCores c = cores(SaiCores.Mode.ALL);
        assertEquals(2, c.allowedCores());
        thread(25560, 0);
        thread(25566, 0);
        c.tick(0, false);
        assertEquals(Long.valueOf(0xc0), moved.get(25566));
        c.openAll();
        assertEquals(Long.valueOf(0xff), moved.get(25560));
        assertEquals(Long.valueOf(0xff), moved.get(25566));
        /* while open, neither new threads nor Wine's moves are pinned */
        moved.clear();
        thread(25601, 0);
        c.tick(1000, true);
        assertTrue("nothing pinned while open: " + moved, moved.isEmpty());
        c.back();
        c.tick(2000, false);
        assertEquals(Long.valueOf(0xc0), moved.get(25560));
        assertEquals(Long.valueOf(0xc0), moved.get(25601));
        assertTrue(String.join("\n", log), log.stream().anyMatch(l -> l.contains("let onto all cores")));
    }

    @Test
    public void offHasNothingToOpen() throws Exception {
        SaiCores c = cores(SaiCores.Mode.OFF);
        thread(25560, 0);
        c.openAll();
        c.back();
        assertTrue(moved.isEmpty());
        assertEquals(8, c.allowedCores());
    }
}
