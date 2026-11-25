package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FastCoresTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), (text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A fake /sys/devices/system/cpu with cpu_capacity per core. */
    private File withCapacity(long... capacity) throws Exception {
        File cpu = tmp.newFolder();
        for (int i = 0; i < capacity.length; i++) write(new File(cpu, "cpu" + i + "/cpu_capacity"), "" + capacity[i]);
        /* the real folder has more in it, none of which are cores */
        new File(cpu, "cpufreq").mkdirs();
        new File(cpu, "cpuidle").mkdirs();
        write(new File(cpu, "online"), "0-" + (capacity.length - 1));
        return cpu;
    }

    @Test
    public void galaxyTabS6LiteHasTwoFastCores() throws Exception {
        /* read from the tablet, 2026-11-19 */
        FastCores c = FastCores.find(withCapacity(467, 467, 467, 467, 467, 467, 1024, 1024));
        assertArrayEquals(new int[] {6, 7}, c.fast);
        assertEquals(0xc0, c.fastMask());
        assertEquals(0xff, c.allMask());
        assertEquals("cpu0-5 467, cpu6-7 1024", c.describe());
    }

    @Test
    public void strengthIsAShareOfTheStrongestCore() throws Exception {
        FastCores c = FastCores.find(withCapacity(467, 467, 467, 467, 467, 467, 1024, 1024));
        assertTrue(c.fromCapacity);
        assertEquals(46, c.strengthPercent(0));
        assertEquals(100, c.strengthPercent(7));
    }

    @Test
    public void noStrengthFromClockSpeedsAlone() throws Exception {
        /* 1.8 against 2.3 GHz would say 78 %, while the kernel says 46 */
        File cpu = tmp.newFolder();
        write(new File(cpu, "cpu0/cpufreq/cpuinfo_max_freq"), "1804800");
        write(new File(cpu, "cpu1/cpufreq/cpuinfo_max_freq"), "2304000");
        FastCores c = FastCores.find(cpu);
        assertFalse(c.fromCapacity);
        assertEquals(-1, c.strengthPercent(0));
    }

    @Test
    public void allCoresAlikeMeansNoFastCores() throws Exception {
        /* THE TRAP: "the fastest group" would be every core, and pinning to
         * it changes nothing at best; there is nothing to prefer */
        FastCores c = FastCores.find(withCapacity(1024, 1024, 1024, 1024));
        assertFalse(c.hasFast());
        assertEquals(0, c.fastMask());
    }

    @Test
    public void oneTopCoreTakesTheMiddleCoresWithIt() throws Exception {
        /* 1 + 3 + 4, a common phone layout: one core alone is too little
         * room for SAI's busy threads */
        FastCores c = FastCores.find(withCapacity(325, 325, 325, 325, 870, 870, 870, 1024));
        assertArrayEquals(new int[] {4, 5, 6, 7}, c.fast);
    }

    @Test
    public void twoTopCoresAndSixNearlyAsFastPinNothing() throws Exception {
        /* THE TRAP: Snapdragon 8 Elite kind (S25, S26 Ultra): two top cores
         * at 4.47 GHz, six at 3.53. The values are made up from those clocks
         * (3.53 / 4.47 of 1024), not read from a phone. No core is slow, so
         * SAI keeps all eight; 0.4.7 would have squeezed it onto two. */
        FastCores c = FastCores.find(withCapacity(809, 809, 809, 809, 809, 809, 1024, 1024));
        assertFalse(c.hasFast());
    }

    @Test
    public void oneTopCoreFiveMiddleAndTwoSmall() throws Exception {
        /* Snapdragon 8 Gen 3 kind (S24 Ultra): 1 top, 3 + 2 middle cores of
         * two speeds, 2 small. Made-up but typical values. Only the small
         * ones are slow. */
        FastCores c = FastCores.find(withCapacity(280, 280, 830, 830, 870, 870, 870, 1024));
        assertArrayEquals(new int[] {2, 3, 4, 5, 6, 7}, c.fast);
    }

    @Test
    public void justUnderTheLineIsSlow() throws Exception {
        /* 0.6 of 1024 is 614.4 */
        assertArrayEquals(new int[] {2, 3}, FastCores.find(withCapacity(614, 614, 1024, 1024)).fast);
        assertFalse(FastCores.find(withCapacity(615, 615, 1024, 1024)).hasFast());
    }

    @Test
    public void oneTopCoreAndTheRestAlikeKeepsTheOneCore() throws Exception {
        /* adding "the next kind" would be every core: then the one stays */
        FastCores c = FastCores.find(withCapacity(400, 400, 400, 1024));
        assertArrayEquals(new int[] {3}, c.fast);
    }

    @Test
    public void bigCoresWithSlightlyDifferentValuesAreOneKind() throws Exception {
        FastCores c = FastCores.find(withCapacity(450, 450, 450, 450, 1004, 1004, 1024, 1024));
        assertArrayEquals(new int[] {4, 5, 6, 7}, c.fast);
    }

    @Test
    public void withoutCapacityTheTopFrequencyIsUsed() throws Exception {
        File cpu = tmp.newFolder();
        for (int i = 0; i < 6; i++) write(new File(cpu, "cpu" + i + "/cpufreq/cpuinfo_max_freq"), "1804800");
        for (int i = 6; i < 8; i++) write(new File(cpu, "cpu" + i + "/cpufreq/cpuinfo_max_freq"), "2304000");
        assertArrayEquals(new int[] {6, 7}, FastCores.find(cpu).fast);
    }

    @Test
    public void aCoreWithNoSpeedGivenMeansWeCannotTell() throws Exception {
        File cpu = withCapacity(467, 467, 1024);
        new File(cpu, "cpu1/cpu_capacity").delete();
        assertFalse(FastCores.find(cpu).hasFast());
    }

    @Test
    public void capacityOnSomeCoresAndFrequencyOnOthersIsNotCompared() throws Exception {
        /* 2304000 kHz would beat capacity 1024 by far, for no reason */
        File cpu = withCapacity(467, 1024);
        write(new File(cpu, "cpu2/cpufreq/cpuinfo_max_freq"), "2304000");
        assertFalse(FastCores.find(cpu).hasFast());
    }

    @Test
    public void coresAreInNumberOrderNotNameOrder() throws Exception {
        /* "cpu10" sorts before "cpu2" as text */
        long[] cap = new long[12];
        for (int i = 0; i < 12; i++) cap[i] = i >= 10 ? 1024 : 500;
        FastCores c = FastCores.find(withCapacity(cap));
        assertArrayEquals(new int[] {10, 11}, c.fast);
        assertEquals(0xc00, c.fastMask());
        assertEquals("cpu0-9 500, cpu10-11 1024", c.describe());
    }

    @Test
    public void noFolderNoCores() {
        FastCores c = FastCores.find(new File(tmp.getRoot(), "missing"));
        assertFalse(c.hasFast());
        assertEquals(0, c.cores.length);
    }

    @Test
    public void rangesForTheLog() {
        assertEquals("cpu6-7", SaiCores.range(new int[] {6, 7}));
        assertEquals("cpu0,2", SaiCores.range(new int[] {0, 2}));
        assertEquals("cpu3", SaiCores.range(new int[] {3}));
        assertTrue(SaiCores.range(new int[] {4, 5, 6, 7}).equals("cpu4-7"));
    }
}
