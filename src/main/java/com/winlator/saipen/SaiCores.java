package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Puts SAI's drawing threads on the device's fast cores (FastCores finds
 * them, DrawingThreads picks the threads). The speed log's thread calls
 * tick() once a second; every step is written to the speed log, so a
 * session can be compared with one without it.
 *
 * Why the app does it: Android does not let adb (or run-as) change the
 * cores of the app's processes ("Permission denied", 2026-11-19), but SAI
 * runs under Wine and Box64 as the app's own processes, and the app may
 * set its own threads' cores (taskset on one thread id).
 *
 * The way of working is read from the app's files folder, `saipen-cores`,
 * so it can be changed for a test without a new build:
 *   threads  (or no file)  the drawing threads on the fast cores
 *   all                    every SAI thread on the fast cores
 *   off                    nothing pinned
 * adb shell run-as sai.penpress sh -c 'echo off > files/saipen-cores'
 *
 * No Android types: the JVM tests use a fake /proc and a fake taskset.
 */
public final class SaiCores {
    public static final String MODE_FILE = "saipen-cores";
    public enum Mode { THREADS, ALL, OFF }
    /** The choices on SAI Pen's screen, in order; the first is the default
     *  (what readMode gives with no file). */
    public static final Mode[] CHOICES = {Mode.THREADS, Mode.ALL, Mode.OFF};

    /** Sets one thread's cores; true if it worked. */
    public interface Pinner {
        boolean pin(int tid, long mask);
    }

    private final File proc;
    private final String saiComm;
    private final FastCores cores;
    private final Mode mode;
    private final Pinner pinner;
    private final Consumer<String> log;
    private final DrawingThreads picker;
    private final Set<Integer> pinned = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private int pid = -1;
    private long lastAt = -1;
    private String lastPick = "";

    public SaiCores(File proc, String saiComm, FastCores cores, Mode mode, Pinner pinner, Consumer<String> log) {
        this.proc = proc;
        this.saiComm = saiComm;
        this.cores = cores;
        this.mode = cores.hasFast() ? mode : Mode.OFF;
        this.pinner = pinner;
        this.log = log;
        this.picker = new DrawingThreads(cores.fast.length);
    }

    public Mode mode() {
        return mode;
    }

    /** The speed log's first lines about the cores. */
    public String describe() {
        String fast = cores.hasFast() ? "fast cores " + range(cores.fast) : "no slow cores to avoid";
        return "cores: " + cores.describe() + "; " + fast + "; SAI's threads: " + mode.name().toLowerCase(Locale.ROOT);
    }

    public static Mode readMode(File filesDir) {
        try {
            String s = new String(Files.readAllBytes(new File(filesDir, MODE_FILE).toPath()), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
            if (s.equals("off")) return Mode.OFF;
            if (s.equals("all")) return Mode.ALL;
        }
        catch (IOException ignored) {
            /* no file: the default */
        }
        return Mode.THREADS;
    }

    /** SAI Pen's screen writes the choice here; SAI reads it at its next start. */
    public static boolean writeMode(File filesDir, Mode mode) {
        try {
            Files.write(new File(filesDir, MODE_FILE).toPath(),
                        (mode.name().toLowerCase(Locale.ROOT) + "\n").getBytes(StandardCharsets.UTF_8));
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    /** BusySai: SAI filled every core it can use, so its threads may run
     *  anywhere until back(); nothing is pinned in between. */
    private boolean open;

    /** Every SAI thread on every core, at once (BusySai.Step.OPEN_ALL). */
    public void openAll() {
        if (mode == Mode.OFF || open) return;
        open = true;
        int moved = 0;
        for (int tid : sorted(pinned)) if (pinner.pin(tid, cores.allMask())) moved++;
        pinned.clear();
        log.accept("cores: SAI filled every core it can use: " + moved + " thread(s) let onto all cores");
    }

    /** The fast cores again, from the next tick (BusySai.Step.BACK). */
    public void back() {
        if (!open) return;
        open = false;
        lastPick = "";
        log.accept("cores: SAI is calm again: back on " + range(cores.fast));
    }

    /** The cores SAI's threads may run on with this mode. */
    public int allowedCores() {
        return mode == Mode.ALL ? cores.fast.length : cores.cores.length;
    }

    /** Once a second. penDown: the pen touched since the last tick. */
    public void tick(long now, boolean penDown) {
        if (mode == Mode.OFF || open) return;
        if (pid < 0 || !new File(proc, Integer.toString(pid)).isDirectory()) {
            int found = findPid();
            if (found != pid) {
                pid = found;
                pinned.clear();
                failed.clear();
                lastAt = -1;
            }
            if (pid < 0) return;
        }
        Map<Integer, Long> ticks = threadTicks();
        pinned.retainAll(ticks.keySet());
        undoneByWine();
        if (mode == Mode.ALL) {
            int before = pinned.size();
            for (int tid : sorted(ticks.keySet())) if (!pinned.contains(tid)) pinTo(tid, cores.fastMask(), null);
            if (pinned.size() != before)
                log.accept("cores: " + pinned.size() + " SAI thread(s) on " + range(cores.fast));
            return;
        }
        picker.sample(ticks, lastAt < 0 ? 0 : now - lastAt, penDown);
        lastAt = now;
        List<Integer> pick = picker.pick();
        List<Set<Integer>> change = DrawingThreads.diff(pinned, pick);
        for (int tid : change.get(1)) {
            if (pinner.pin(tid, cores.allMask())) {
                pinned.remove(tid);
                log.accept("cores: thread " + tid + " back on all cores (" + share(picker.load(tid)) + " of a core)");
            }
        }
        for (int tid : sorted(change.get(0))) pinTo(tid, cores.fastMask(), share(picker.load(tid)) + " of a core");
        String now2 = pick.toString();
        if (!pick.isEmpty() && !now2.equals(lastPick)) {
            double sum = 0;
            for (int tid : pick) sum += picker.load(tid);
            log.accept("cores: " + pick.size() + " drawing thread(s) on " + range(cores.fast) + ", "
                       + share(sum) + " of a core in all, after " + picker.drawMs() / 1000 + " s of drawing");
            lastPick = now2;
        }
    }

    /**
     * THE TRAP: Winlator gives SAI's whole process all the container's cores
     * every time one of SAI's own windows comes up (Win32AppWorkarounds,
     * through Wine), which undoes our pins without a word. So the pinned
     * threads' cores are read back every second, and a thread that lost its
     * fast cores is pinned again.
     */
    private void undoneByWine() {
        int undone = 0;
        for (int tid : sorted(pinned)) {
            long m = allowedMask(tid);
            if (m >= 0 && m != cores.fastMask()) {
                pinned.remove(tid);
                undone++;
            }
        }
        if (undone > 0) log.accept("cores: " + undone + " thread(s) were moved off the fast cores: pinning them again");
    }

    /** The cores `tid` may run on (Cpus_allowed in its status), -1 if unknown. */
    long allowedMask(int tid) {
        File status = new File(new File(new File(new File(proc, Integer.toString(pid)), "task"),
                                        Integer.toString(tid)), "status");
        try {
            for (String line : new String(Files.readAllBytes(status.toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("Cpus_allowed:")) continue;
                String hex = line.substring("Cpus_allowed:".length()).trim().replace(",", "");
                /* more than 64 cores: only the low 64 matter here */
                if (hex.length() > 16) hex = hex.substring(hex.length() - 16);
                return Long.parseUnsignedLong(hex, 16);
            }
        }
        catch (IOException | NumberFormatException ignored) {
            /* the thread ended while we looked */
        }
        return -1;
    }

    private void pinTo(int tid, long mask, String why) {
        if (failed.contains(tid)) return;
        if (pinner.pin(tid, mask)) {
            pinned.add(tid);
            if (why != null) log.accept("cores: thread " + tid + " on " + range(cores.fast) + " (" + why + ")");
        }
        else {
            failed.add(tid);
            log.accept("cores: could not move thread " + tid);
        }
    }

    private int findPid() {
        File[] entries = proc.listFiles();
        if (entries == null) return -1;
        for (File e : entries) {
            String n = e.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            try {
                String comm = new String(Files.readAllBytes(new File(e, "comm").toPath()), StandardCharsets.UTF_8).trim();
                if (comm.equalsIgnoreCase(saiComm)) return Integer.parseInt(n);
            }
            catch (IOException | NumberFormatException ignored) {
                /* the process ended while we looked */
            }
        }
        return -1;
    }

    private Map<Integer, Long> threadTicks() {
        Map<Integer, Long> out = new HashMap<>();
        File[] tasks = new File(new File(proc, Integer.toString(pid)), "task").listFiles();
        if (tasks == null) return out;
        for (File t : tasks) {
            try {
                long ticks = SpeedLog.cpuTicks(new String(Files.readAllBytes(new File(t, "stat").toPath()),
                                                          StandardCharsets.UTF_8));
                if (ticks >= 0) out.put(Integer.parseInt(t.getName()), ticks);
            }
            catch (IOException | NumberFormatException ignored) {
                /* the thread ended while we looked */
            }
        }
        return out;
    }

    private static List<Integer> sorted(Set<Integer> s) {
        List<Integer> l = new ArrayList<>(s);
        l.sort(null);
        return l;
    }

    static String share(double load) {
        return String.format(Locale.ROOT, "%.2f", load);
    }

    /** {6, 7} as "cpu6-7", {0, 2} as "cpu0,2". */
    static String range(int[] cs) {
        StringBuilder out = new StringBuilder("cpu");
        int i = 0;
        while (i < cs.length) {
            int j = i;
            while (j + 1 < cs.length && cs[j + 1] == cs[j] + 1) j++;
            if (i > 0) out.append(',');
            out.append(cs[i]);
            if (j > i) out.append('-').append(cs[j]);
            i = j + 1;
        }
        return out.toString();
    }

    /** The real taskset, on one thread (no -a: that would be every thread). */
    public static boolean taskset(int tid, long mask) {
        try {
            Process p = new ProcessBuilder("taskset", "-p", Long.toHexString(mask), Integer.toString(tid))
                .redirectErrorStream(true).start();
            byte[] buf = new byte[256];
            while (p.getInputStream().read(buf) >= 0) { /* taskset's own lines: not needed */ }
            return p.waitFor() == 0;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
