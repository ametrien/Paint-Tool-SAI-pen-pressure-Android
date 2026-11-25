package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the device's fast processor cores, on any device, from what the
 * kernel says about each core:
 *   /sys/devices/system/cpu/cpuN/cpu_capacity   (the fastest core is 1024)
 *   /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq   (if not)
 *
 * The question is not "which cores are the very fastest" but "which cores
 * are slow": SAI should stay off the slow ones and keep all the others.
 * With cpu_capacity a core is slow below SLOW_BELOW of the fastest:
 *   Galaxy Tab S6 Lite (Snapdragon 720G): cpu0 to 5 are 467 of 1024, slow;
 *     the fast cores are 6 and 7.
 *   A phone with one top core, a few middle ones and small ones (Snapdragon
 *     8 Gen 2 and 3 kind): the top and middle cores are fast, the small
 *     ones slow.
 *   A phone with two top cores and six cores nearly as fast (Snapdragon 8
 *     Elite kind, S25 and S26 Ultra): no core is slow, so nothing is pinned.
 *
 * THE TRAP: "the fastest kind of core" would be the two top cores on that
 * last phone, and SAI squeezed onto two of them while six nearly as fast
 * cores stand idle could only be slower. The first version (0.4.7) did
 * exactly that. Also: where every core is alike, or where the kernel does
 * not say how fast a core is, there is nothing to prefer and nothing is
 * pinned.
 *
 * Without cpu_capacity only the top clock speed is known, and it hides how
 * slow the small cores are (on the Tab S6 Lite 1.8 GHz against 2.3, but 467
 * against 1024 in capacity). Then the old rule stays: the fastest kind of
 * core, with the next kind if the fastest is one core alone.
 *
 * No Android types: the JVM tests use a fake /sys folder.
 */
public final class FastCores {
    /** Cores within this share of the fastest count as the same kind (some
     *  kernels give the big cores of one cluster slightly different values). */
    static final double SAME_KIND = 0.9;
    /** With cpu_capacity: a core under this share of the fastest is slow. */
    static final double SLOW_BELOW = 0.6;

    /** All the cores, in order; speed[i] is cores[i]'s speed. */
    public final int[] cores;
    public final long[] speed;
    /** The cores to prefer; empty: none (all alike, or unknown). */
    public final int[] fast;
    /** True when speed[] is every core's cpu_capacity (the kernel's own
     *  measure of strength), false when it is the top clock or unknown. */
    public final boolean fromCapacity;

    private FastCores(int[] cores, long[] speed, int[] fast, boolean fromCapacity) {
        this.cores = cores;
        this.speed = speed;
        this.fast = fast;
        this.fromCapacity = fromCapacity;
    }

    /** cores[i]'s strength as a share of the strongest core, 0 to 100,
     *  or -1 when only clock speeds are known: a share of the top clock
     *  would only repeat the GHz and hide how slow the small cores are. */
    public int strengthPercent(int i) {
        if (!fromCapacity) return -1;
        long top = 0;
        for (long s : speed) top = Math.max(top, s);
        return top <= 0 ? -1 : (int) Math.round(speed[i] * 100.0 / top);
    }

    public static FastCores find(File cpuDir) {
        List<Integer> ids = new ArrayList<>();
        File[] entries = cpuDir.listFiles();
        if (entries != null) {
            for (File e : entries) {
                String n = e.getName();
                if (n.length() > 3 && n.startsWith("cpu") && isNumber(n.substring(3)))
                    ids.add(Integer.parseInt(n.substring(3)));
            }
        }
        ids.sort(null);
        int[] cores = new int[ids.size()];
        long[] speed = new long[ids.size()];
        boolean known = !ids.isEmpty();
        boolean capacity = true;
        for (int i = 0; i < cores.length; i++) {
            cores[i] = ids.get(i);
            File core = new File(cpuDir, "cpu" + cores[i]);
            speed[i] = readNumber(new File(core, "cpu_capacity"));
            if (speed[i] <= 0) {
                capacity = false;
                speed[i] = readNumber(new File(new File(core, "cpufreq"), "cpuinfo_max_freq"));
            }
            if (speed[i] <= 0) known = false;
        }
        /* capacity on some cores and frequency on others would compare
         * apples with pears: then all must have come from the same file */
        if (known && !sameSource(cpuDir, cores)) known = false;
        int[] fast = !known ? new int[0] : capacity ? notSlow(cores, speed) : fastestKind(cores, speed);
        return new FastCores(cores, speed, fast, known && capacity);
    }

    /** With cpu_capacity: every core that is not slow; none if no core is. */
    static int[] notSlow(int[] cores, long[] speed) {
        long top = 0;
        for (long s : speed) top = Math.max(top, s);
        int[] fast = atLeast(cores, speed, top * SLOW_BELOW);
        return fast.length == cores.length ? new int[0] : fast;
    }

    /** With clock speeds only: the fastest kind of core (see above). */
    static int[] fastestKind(int[] cores, long[] speed) {
        long top = 0;
        for (long s : speed) top = Math.max(top, s);
        int[] fast = atLeast(cores, speed, top * SAME_KIND);
        if (fast.length == cores.length) return new int[0];        /* all alike */
        if (fast.length == 1) {
            /* one top core: add the next kind of core, unless that is all of them */
            long next = 0;
            for (long s : speed) if (s < top * SAME_KIND) next = Math.max(next, s);
            int[] two = atLeast(cores, speed, next * SAME_KIND);
            if (two.length < cores.length) return two;
        }
        return fast;
    }

    private static int[] atLeast(int[] cores, long[] speed, double min) {
        int n = 0;
        for (long s : speed) if (s >= min) n++;
        int[] out = new int[n];
        n = 0;
        for (int i = 0; i < cores.length; i++) if (speed[i] >= min) out[n++] = cores[i];
        return out;
    }

    /** The mask taskset takes: bit N for core N. */
    public static long mask(int[] cores) {
        long m = 0;
        for (int c : cores) if (c >= 0 && c < 64) m |= 1L << c;
        return m;
    }

    public long fastMask() {
        return mask(fast);
    }

    public long allMask() {
        return mask(cores);
    }

    public boolean hasFast() {
        return fast.length > 0;
    }

    /** "cpu0-5 467, cpu6-7 1024" style, for the speed log. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < cores.length) {
            int j = i;
            while (j + 1 < cores.length && speed[j + 1] == speed[i] && cores[j + 1] == cores[j] + 1) j++;
            if (out.length() > 0) out.append(", ");
            out.append("cpu").append(cores[i]);
            if (j > i) out.append('-').append(cores[j]);
            out.append(' ').append(speed[i]);
            i = j + 1;
        }
        return out.toString();
    }

    private static boolean sameSource(File cpuDir, int[] cores) {
        int withCapacity = 0;
        for (int c : cores)
            if (readNumber(new File(new File(cpuDir, "cpu" + c), "cpu_capacity")) > 0) withCapacity++;
        return withCapacity == 0 || withCapacity == cores.length;
    }

    private static long readNumber(File f) {
        try {
            return Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        }
        catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }
}
