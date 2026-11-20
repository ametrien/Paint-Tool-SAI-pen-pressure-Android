package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrefixLinksTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void anEmptyDownloadsBecomesTheTabletsDownloads() throws Exception {
        File tablet = tmp.newFolder("storage", "Download");
        File wine = tmp.newFolder("drive_c", "users", "xuser", "Downloads");
        assertTrue(PrefixLinks.linkFolder(wine, tablet));
        assertTrue(Files.isSymbolicLink(wine.toPath()));
        /* what SAI saves there lands in the tablet's Downloads */
        Files.write(new File(wine, "NewCanvas1.sai2").toPath(), new byte[] {1});
        assertTrue(new File(tablet, "NewCanvas1.sai2").isFile());
    }

    @Test
    public void aFolderWithFilesIsLeftAlone() throws Exception {
        /* THE TRAP: never throw a drawing away to make a link */
        File tablet = tmp.newFolder("storage", "Download");
        File wine = tmp.newFolder("drive_c", "users", "xuser", "Downloads");
        Files.write(new File(wine, "saved-before.sai2").toPath(), new byte[] {7});
        assertFalse(PrefixLinks.linkFolder(wine, tablet));
        assertFalse(Files.isSymbolicLink(wine.toPath()));
        assertTrue(new File(wine, "saved-before.sai2").isFile());
    }

    @Test
    public void everyStartIsFineWithTheLinkAlreadyThere() throws Exception {
        File tablet = tmp.newFolder("storage", "Download");
        File wine = new File(tmp.getRoot(), "drive_c/users/xuser/Downloads");
        assertTrue("made, parents too", PrefixLinks.linkFolder(wine, tablet));
        assertTrue(PrefixLinks.linkFolder(wine, tablet));
        assertEquals(tablet.toPath(), Files.readSymbolicLink(wine.toPath()));
    }

    /* ---- SAI's settings folder: brushes, textures, presets ---- */

    private File write(File dir, String path, String text) throws Exception {
        File f = new File(dir, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes("UTF-8"));
        return f;
    }

    private String read(File dir, String path) throws Exception {
        return new String(Files.readAllBytes(new File(dir, path).toPath()), "UTF-8");
    }

    @Test
    public void aFreshInstallGetsTheTabletFolderBeforeSaiEverStarts() throws Exception {
        File tablet = new File(tmp.getRoot(), "storage/Documents/SYSTEMAX Software Development/SAIv2/settings");
        File wine = new File(tmp.getRoot(), "drive_c/users/xuser/Documents/SYSTEMAX Software Development/SAIv2/settings");
        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, new File(wine.getParentFile(), "kept")));
        /* a link to a folder that is there: SAI can fill it on its first start */
        assertTrue(tablet.isDirectory());
        write(wine, "papertex/Paper.bmp", "bmp");
        assertEquals("bmp", read(tablet, "papertex/Paper.bmp"));
        assertFalse(new File(wine.getParentFile(), "kept").exists());
    }

    @Test
    public void settingsMadeInsideTheAppMoveToTheTablet() throws Exception {
        File tablet = new File(tmp.getRoot(), "storage/Documents/SYSTEMAX Software Development/SAIv2/settings");
        File wine = tmp.newFolder("drive_c", "SAIv2", "settings");
        write(wine, "custool/nrm/6.saitdat", "my pencil");
        write(wine, "papertex/Paper.bmp", "bmp");
        write(wine, "settings2.ssd", "prefs");
        File kept = new File(wine.getParentFile(), "settings-inside-the-app");

        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, kept));
        assertTrue(Files.isSymbolicLink(wine.toPath()));
        assertEquals("my pencil", read(tablet, "custool/nrm/6.saitdat"));
        assertEquals("prefs", read(wine, "settings2.ssd"));
        /* THE TRAP: the old folder is kept, not deleted */
        assertEquals("my pencil", read(kept, "custool/nrm/6.saitdat"));
        assertFalse(new File(tablet.getParentFile(), "settings.copying").exists());
    }

    @Test
    public void settingsCopiedFromAPcWinAndNothingIsMixedIn() throws Exception {
        File tablet = tmp.newFolder("storage", "SAIv2", "settings");
        write(tablet, "custool/nrm/_2.saitgrp", "pc group");
        File wine = tmp.newFolder("drive_c", "SAIv2", "settings");
        write(wine, "custool/nrm/_2.saitgrp", "tablet group");
        write(wine, "custool/nrm/39.saitdat", "tablet only");
        File kept = new File(wine.getParentFile(), "settings-inside-the-app");

        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, kept));
        assertEquals("pc group", read(wine, "custool/nrm/_2.saitgrp"));
        assertFalse("no tablet tools among the PC's", new File(tablet, "custool/nrm/39.saitdat").exists());
        assertEquals("tablet only", read(kept, "custool/nrm/39.saitdat"));
    }

    @Test
    public void everyLaterStartLeavesTheLinkAndTheBackupAlone() throws Exception {
        File tablet = new File(tmp.getRoot(), "storage/settings");
        File wine = tmp.newFolder("drive_c", "SAIv2", "settings");
        write(wine, "settings2.ssd", "prefs");
        File kept = new File(wine.getParentFile(), "kept");
        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, kept));
        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, kept));
        assertFalse(new File(wine.getParentFile(), "kept-2").exists());
        assertEquals(tablet.toPath(), Files.readSymbolicLink(wine.toPath()));
    }

    @Test
    public void anOlderBackupIsNotOverwritten() throws Exception {
        File tablet = new File(tmp.getRoot(), "storage/settings");
        File wine = tmp.newFolder("drive_c", "SAIv2", "settings");
        write(wine, "settings2.ssd", "newer");
        File kept = new File(wine.getParentFile(), "kept");
        write(kept, "settings2.ssd", "older");
        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, kept));
        assertEquals("older", read(kept, "settings2.ssd"));
        assertEquals("newer", read(new File(wine.getParentFile(), "kept-2"), "settings2.ssd"));
    }

    @Test
    public void aCopyCutShortLastTimeIsNotTakenForThePersonsSettings() throws Exception {
        File storage = tmp.newFolder("storage");
        File tablet = new File(storage, "settings");
        write(storage, "settings.copying/custool/nrm/6.saitdat", "half");
        File wine = tmp.newFolder("drive_c", "SAIv2", "settings");
        write(wine, "custool/nrm/6.saitdat", "whole");
        write(wine, "settings2.ssd", "prefs");
        assertTrue(PrefixLinks.moveIntoAndLink(wine, tablet, new File(wine.getParentFile(), "kept")));
        assertEquals("whole", read(tablet, "custool/nrm/6.saitdat"));
        assertEquals("prefs", read(tablet, "settings2.ssd"));
        assertFalse(new File(storage, "settings.copying").exists());
    }

    @Test
    public void whenTheTabletRefusesSaiKeepsItsOwnFolder() throws Exception {
        /* shared storage not writable (no permission, say): nothing moves */
        File storage = tmp.newFolder("storage");
        File tablet = new File(storage, "settings");
        File wine = tmp.newFolder("drive_c", "SAIv2", "settings");
        write(wine, "custool/nrm/6.saitdat", "my pencil");
        assertTrue(storage.setWritable(false));
        try {
            assertFalse(PrefixLinks.moveIntoAndLink(wine, tablet, new File(wine.getParentFile(), "kept")));
        }
        finally {
            storage.setWritable(true);
        }
        assertFalse(Files.isSymbolicLink(wine.toPath()));
        assertEquals("my pencil", read(wine, "custool/nrm/6.saitdat"));
        assertFalse(new File(wine.getParentFile(), "kept").exists());
    }

    @Test
    public void aLinkToSomewhereElseIsRepointed() throws Exception {
        File old = tmp.newFolder("old");
        File tablet = tmp.newFolder("storage", "Download");
        File wine = new File(tmp.getRoot(), "Downloads");
        Files.createSymbolicLink(wine.toPath(), old.toPath());
        Files.write(new File(old, "keep.txt").toPath(), new byte[] {1});
        assertTrue(PrefixLinks.linkFolder(wine, tablet));
        assertEquals(tablet.toPath(), Files.readSymbolicLink(wine.toPath()));
        /* only the link went, not what it pointed at */
        assertTrue(new File(old, "keep.txt").isFile());
    }
}
