package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Notices that SAI has closed, so the session can end by itself.
 *
 * Closing SAI with its own red cross ends only SAI: Wine's desktop stays,
 * and the tablet showed an empty screen with no way back but the menu
 * (2026-11-18). So the session watches for SAI's process (sai2.exe); once
 * it has run and been gone for GONE_FOR_MS, the app goes back to its own
 * screen.
 *
 * THE TRAP: SAI is not running yet for the first seconds of a session.
 * Only a SAI that was seen running counts as closed.
 *
 * No Android types: the JVM tests use a fake /proc folder.
 */
public final class SaiProcessWatch {
    /** SAI must stay gone this long (a moment without it is not a close). */
    /* 1500 ms until 0.4.3; the closing cover now goes up when SAI's window
     * goes (SaiClosing), and this only adds to the time it shows */
    public static final long GONE_FOR_MS = 750;

    /** The kernel keeps a process name to 15 characters. */
    private static final int COMM_LENGTH = 15;

    private boolean seen = false;
    private long goneSince = -1;

    /** One look at the processes. True once SAI ran and has been gone
     *  for GONE_FOR_MS: time to leave the session. */
    public synchronized boolean onPoll(boolean running, long now) {
        if (running) {
            seen = true;
            goneSince = -1;
            return false;
        }
        if (!seen) return false;
        if (goneSince < 0) goneSince = now;
        return now - goneSince >= GONE_FOR_MS;
    }

    /**
     * Is a process named like `exeName` (sai2.exe) running? Looks at
     * proc/<pid>/comm, where Box64 puts the Windows program's name. On
     * Android an app sees only its own processes there, and Wine's are
     * the app's.
     */
    public static boolean isRunning(File proc, String exeName) {
        String want = commOf(exeName);
        File[] entries = proc.listFiles();
        if (entries == null) return false;
        for (File e : entries) {
            if (!isPid(e.getName())) continue;
            try {
                String comm = new String(Files.readAllBytes(new File(e, "comm").toPath()), StandardCharsets.UTF_8).trim();
                if (comm.toLowerCase(Locale.ROOT).equals(want)) return true;
            }
            catch (IOException ignored) {
                /* the process ended while we looked */
            }
        }
        return false;
    }

    /** Processor time of the process named like `exeName`, in clock ticks
     *  (SpeedLog.cpuTicks), or -1 if it is not running. */
    public static long cpuTicks(File proc, String exeName) {
        String want = commOf(exeName);
        File[] entries = proc.listFiles();
        if (entries == null) return -1;
        for (File e : entries) {
            if (!isPid(e.getName())) continue;
            try {
                String comm = new String(Files.readAllBytes(new File(e, "comm").toPath()), StandardCharsets.UTF_8).trim();
                if (!comm.toLowerCase(Locale.ROOT).equals(want)) continue;
                return SpeedLog.cpuTicks(new String(Files.readAllBytes(new File(e, "stat").toPath()),
                                                    StandardCharsets.UTF_8));
            }
            catch (IOException ignored) {
                /* the process ended while we looked */
            }
        }
        return -1;
    }

    static String commOf(String exeName) {
        String n = exeName.toLowerCase(Locale.ROOT);
        return n.length() > COMM_LENGTH ? n.substring(0, COMM_LENGTH) : n;
    }

    private static boolean isPid(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) if (!Character.isDigit(name.charAt(i))) return false;
        return true;
    }
}
