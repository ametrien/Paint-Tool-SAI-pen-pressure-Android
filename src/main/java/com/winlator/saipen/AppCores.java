package com.winlator.saipen;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Speed test only, no switch in the app: the app's own threads (X server, screen drawing,
 * the pen, Android's) on the slow cores, so both fast cores are left to SAI. SaiCores puts
 * SAI's threads on the fast cores; the app's threads went wherever Android put them, often
 * the same two cores (2 fast + 6 slow on the Galaxy Tab S6 Lite). Whether drawing the screen
 * on a slow core costs more than SAI gains is what the test is for. Read when SAI starts:
 *     adb shell setprop debug.saipen.app_cores slow   (unset or anything else: off)
 *
 * The speed log's thread calls tick() once a second; threads started since are pinned too.
 * No Android types: the JVM tests use a fake /proc/self/task and a fake pinner.
 */
public final class AppCores {
    public static final String PROP = "debug.saipen.app_cores";

    private final File tasks;
    private final long mask;
    private final SaiCores.Pinner pinner;
    private final Consumer<String> log;
    private final Set<Integer> done = new HashSet<>();
    private int failed = 0;

    private AppCores(File tasks, long mask, SaiCores.Pinner pinner, Consumer<String> log) {
        this.tasks = tasks;
        this.mask = mask;
        this.pinner = pinner;
        this.log = log;
    }

    /** Null when off (prop not "slow") or when there are no slow cores to use. */
    public static AppCores create(String prop, File tasks, FastCores cores, SaiCores.Pinner pinner,
                                  Consumer<String> log) {
        if (!"slow".equals(prop) || cores == null || !cores.hasFast()) return null;
        long slow = cores.allMask() & ~cores.fastMask();
        if (slow == 0) return null;
        log.accept("app threads: on the slow cores (mask " + Long.toHexString(slow) + "), " + PROP);
        return new AppCores(tasks, slow, pinner, log);
    }

    public void tick() {
        String[] names = tasks.list();
        if (names == null) return;
        int added = 0, bad = 0;
        for (String n : names) {
            int tid;
            try {
                tid = Integer.parseInt(n);
            }
            catch (NumberFormatException e) {
                continue;
            }
            if (!done.add(tid)) continue;
            if (pinner.pin(tid, mask)) added++;
            else bad++;
        }
        failed += bad;
        if (added > 0 || bad > 0)
            log.accept("app threads: " + added + " more on the slow cores" + (bad > 0 ? ", " + bad + " refused" : "")
                       + " (" + (done.size() - failed) + " in all)");
    }
}
