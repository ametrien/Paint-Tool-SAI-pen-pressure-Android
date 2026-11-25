package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppCoresTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 6 slow cores (0-5) and 2 fast (6-7), as the Snapdragon 720G. */
    private FastCores cores() throws IOException {
        File cpu = tmp.newFolder();
        for (int i = 0; i < 8; i++) {
            File f = new File(cpu, "cpu" + i + "/cpufreq");
            assertTrue(f.mkdirs());
            try (FileWriter w = new FileWriter(new File(f, "cpuinfo_max_freq"))) {
                w.write(i < 6 ? "1804800\n" : "2304000\n");
            }
        }
        return FastCores.find(cpu);
    }

    @Test public void offUnlessAsked() throws IOException {
        assertNull(AppCores.create("", tmp.newFolder("t"), cores(), (t, m) -> true, s -> {}));
        assertNull(AppCores.create("fast", tmp.newFolder("u"), cores(), (t, m) -> true, s -> {}));
    }

    @Test public void everyThreadOnTheSlowCoresOnce() throws IOException {
        File tasks = tmp.newFolder("task");
        assertTrue(new File(tasks, "100").mkdir());
        assertTrue(new File(tasks, "101").mkdir());
        Map<Integer, Long> pinned = new HashMap<>();
        List<String> log = new ArrayList<>();
        AppCores a = AppCores.create("slow", tasks, cores(), (t, m) -> { pinned.put(t, m); return t != 101; }, log::add);
        assertNotNull(a);
        a.tick();
        assertEquals(Long.valueOf(0x3fL), pinned.get(100));
        assertEquals("app threads: 1 more on the slow cores, 1 refused (1 in all)", log.get(1));
        pinned.clear();
        assertTrue(new File(tasks, "102").mkdir());
        a.tick();
        assertEquals("only the new thread", 1, pinned.size());
        assertEquals(Long.valueOf(0x3fL), pinned.get(102));
    }
}
