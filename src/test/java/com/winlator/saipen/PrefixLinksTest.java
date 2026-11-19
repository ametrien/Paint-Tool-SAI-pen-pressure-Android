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
