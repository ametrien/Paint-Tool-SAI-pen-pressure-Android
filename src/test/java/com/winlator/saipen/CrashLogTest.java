package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class CrashLogTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void newestFirstAndAtMostTen() throws Exception {
        File dir = tmp.newFolder();
        assertTrue(CrashLog.entries(dir).isEmpty());
        for (int i = 1; i <= 12; i++) CrashLog.add(dir, CrashLog.entry("crash " + i, "line a " + i, "line b " + i));
        List<String> e = CrashLog.entries(dir);
        assertEquals(CrashLog.MAX, e.size());
        assertEquals("crash 12\n  line a 12\n  line b 12", e.get(0));
        assertTrue("the oldest two are gone", e.get(9).startsWith("crash 3"));
    }

    @Test
    public void anEntrysLinesAreIndentedAndEmptyOnesLeftOut() {
        assertEquals("head\n  one\n  two\n  three", CrashLog.entry("head", "one", null, "", "two\nthree"));
    }

    @Test
    public void aSeparatorLikeLineInsideStaysInItsEntry() {
        List<String> e = CrashLog.split(CrashLog.join(CrashLog.withEntry(
            CrashLog.split(null), CrashLog.entry("a", "x -- y"))));
        assertEquals(1, e.size());
        assertEquals("a\n  x -- y", e.get(0));
    }

    @Test
    public void theErrorWithWhereItHappenedIsTakenOnce() throws Exception {
        File dir = tmp.newFolder();
        RuntimeException e = new RuntimeException("outer", new IllegalStateException("inner"));
        CrashLog.saveError(dir, Thread.currentThread(), e, 1_795_000_000_000L);
        String t = CrashLog.takeError(dir);
        assertTrue(t, t.startsWith("at 20"));
        assertTrue(t, t.contains("java.lang.RuntimeException: outer"));
        assertTrue(t, t.contains("caused by java.lang.IllegalStateException: inner"));
        assertTrue(t, t.contains("  at com.winlator.saipen.CrashLogTest"));
        assertNull("taken: gone", CrashLog.takeError(dir));
    }

    @Test
    public void aDeepStackIsCut() {
        Throwable deep = deep(60);
        String t = CrashLog.errorText("main", deep, 0);
        int at = 0;
        for (String l : t.split("\n")) if (l.startsWith("  at ")) at++;
        assertEquals(CrashLog.STACK_LINES, at);
    }

    private static Throwable deep(int n) {
        if (n == 0) return new RuntimeException("deep");
        return deep(n - 1);
    }

    @Test
    public void theReportHidesPathsAndLicenceNumbersInCrashLines() {
        String r = new BugReport("0.5.2", 58)
            .line(2, "1. SAI Pen stopped with an error")
            .line(5, "java.io.FileNotFoundException: /storage/emulated/0/Documents/x.sai2")
            .line(5, "sai-000123456789.slc not read")
            .text();
        assertTrue(r, r.contains("\n  1. SAI Pen stopped with an error\n"));
        assertTrue(r, r.contains("\n     (a path, hidden)\n"));
        assertFalse(r, r.contains("000123456789"));
    }
}
