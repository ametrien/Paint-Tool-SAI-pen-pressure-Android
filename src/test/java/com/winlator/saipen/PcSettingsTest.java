package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PcSettingsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File zip(File f, Charset charset, String... names) throws IOException {
        f.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f), charset)) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                z.write(n.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return f;
    }

    private File zip(File f, String... names) throws IOException {
        return zip(f, StandardCharsets.UTF_8, names);
    }

    private File settingsFolder(File parent) throws IOException {
        File s = new File(parent, "settings");
        new File(s, "brushtex").mkdirs();
        assertTrue(new File(s, "settings2.ssd").createNewFile());
        return s;
    }

    @Test
    public void aZipFromWindowsSendToIsFound() throws Exception {
        File root = tmp.getRoot();
        File z = zip(new File(root, "Download/settings.zip"), "settings/settings2.ssd", "settings/brushtex/a.bmp");
        List<PcSettings.Found> found = PcSettings.find(root, 4, null);
        assertEquals(1, found.size());
        assertEquals(z, found.get(0).file);
        assertTrue(found.get(0).zip);
    }

    @Test
    public void aZipWithAnyNameAndSaiBesideItIsFound() throws Exception {
        File root = tmp.getRoot();
        zip(new File(root, "Download/my sai things.zip"), "SAI/sai2.exe",
            "SYSTEMAX Software Development/SAIv2/settings/settings2.ssd");
        assertEquals(1, PcSettings.find(root, 4, null).size());
    }

    @Test
    public void aBrushPackOrSaiItselfIsNotSettings() throws Exception {
        File root = tmp.getRoot();
        zip(new File(root, "Download/pack.zip"), "brushtex/a.bmp", "papertex/b.bmp");
        zip(new File(root, "Download/sai2-Alpha-64bit-en.zip"), "sai2.exe", "sai2.ini");
        assertTrue(PcSettings.find(root, 4, null).isEmpty());
    }

    @Test
    public void anUnpackedSettingsFolderIsFound() throws Exception {
        File root = tmp.getRoot();
        File s = settingsFolder(new File(root, "Download"));
        List<PcSettings.Found> found = PcSettings.find(root, 4, null);
        assertEquals(1, found.size());
        assertEquals(s, found.get(0).file);
        assertFalse(found.get(0).zip);
    }

    @Test
    public void theDevicesOwnSettingsAndBackupsAreLeftOut() throws Exception {
        File root = tmp.getRoot();
        File saiV2 = new File(root, "Documents/SYSTEMAX Software Development/SAIv2");
        settingsFolder(saiV2);
        File backup = new File(saiV2, "settings-before-2026-11-24-1200");
        backup.mkdirs();
        assertTrue(new File(backup, "settings2.ssd").createNewFile());
        assertTrue(PcSettings.find(root, 6, saiV2).isEmpty());
        assertEquals(2, PcSettings.find(root, 6, null).size());
    }

    @Test
    public void newestFirst() throws Exception {
        File root = tmp.getRoot();
        File older = zip(new File(root, "Download/old.zip"), "settings/settings2.ssd");
        File newer = zip(new File(root, "Documents/new.zip"), "settings/settings2.ssd");
        assertTrue(older.setLastModified(1_000_000_000_000L));
        assertTrue(newer.setLastModified(1_700_000_000_000L));
        List<PcSettings.Found> found = PcSettings.find(root, 4, null);
        assertEquals(newer, found.get(0).file);
        assertEquals(older, found.get(1).file);
    }

    @Test
    public void aZipMadeOnAnOldWindowsIsReadToo() throws Exception {
        File root = tmp.getRoot();
        zip(new File(root, "Download/настройки.zip"), Charset.forName("IBM437"),
            "Einstellungen ä/settings/settings2.ssd");
        assertEquals(1, PcSettings.find(root, 4, null).size());
    }

    @Test
    public void aBrokenZipIsSkipped() throws Exception {
        File root = tmp.getRoot();
        File bad = new File(root, "Download/broken.zip");
        bad.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(bad)) { o.write("not a zip".getBytes(StandardCharsets.UTF_8)); }
        assertTrue(PcSettings.find(root, 4, null).isEmpty());
    }
}
