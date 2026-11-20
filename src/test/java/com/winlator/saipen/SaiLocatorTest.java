package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaiLocatorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File mkdirs(File base, String rel) {
        File d = new File(base, rel);
        d.mkdirs();
        return d;
    }

    private File touch(File dir, String name) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[] {1});
        return f;
    }

    /** A SAI folder as its zip unpacks: sai2.exe, sai2.ini, init/... */
    private File sai(File base, String rel) throws IOException {
        File d = mkdirs(base, rel);
        touch(d, "sai2.exe");
        touch(d, "sai2.ini");
        touch(d, "init/papertex/paper.bmp");
        return d;
    }

    @Test
    public void theLicenceNumberIsWhatHidesBehindTheSpoiler() {
        String line = "SAI licence: sai-010062815390.slc";
        int[] r = SaiLocator.licenceNumberRange(line, "sai-010062815390.slc");
        assertEquals("010062815390", line.substring(r[0], r[1]));
        /* a name without a number: all of it */
        String other = "SAI licence: mine.slc";
        int[] all = SaiLocator.licenceNumberRange(other, "mine.slc");
        assertEquals("mine.slc", other.substring(all[0], all[1]));
        /* Russian line, same name */
        String ru = "Лицензия SAI: sai-0123456789.slc";
        int[] r2 = SaiLocator.licenceNumberRange(ru, "sai-0123456789.slc");
        assertEquals("0123456789", ru.substring(r2[0], r2[1]));
        assertEquals(-1, SaiLocator.licenceNumberRange(line, "elsewhere.slc")[0]);
        assertEquals(-1, SaiLocator.licenceNumberRange(line, null)[0]);
    }

    @Test
    public void findsAWholeSaiFolderInsideTelegramDownloads() throws Exception {
        File root = tmp.getRoot();
        File dir = sai(root, "Download/Telegram/sai2-Alpha-20261019-64bit-en");
        List<SaiLocator.Found> found = SaiLocator.scan(root, 4);
        assertEquals(1, found.size());
        assertEquals(dir, found.get(0).dir);
        assertTrue(found.get(0).complete);
        assertEquals(new File(dir, "sai2.exe"), found.get(0).exe());
    }

    @Test
    public void anExeWithoutItsFolderIsIncomplete() throws Exception {
        /* what came over Telegram on 2026-11-18: sai2.exe alone, and SAI
         * stopped with a dialog listing sai2.ini and init\papertex... */
        File root = tmp.getRoot();
        touch(mkdirs(root, "Download/Telegram"), "sai2.exe");
        List<SaiLocator.Found> found = SaiLocator.scan(root, 4);
        assertEquals(1, found.size());
        assertFalse(found.get(0).complete);
    }

    @Test
    public void lettersCaseDoesNotMatter() throws Exception {
        File d = mkdirs(tmp.getRoot(), "Download/SAI");
        touch(d, "SAI2.EXE");
        touch(d, "Sai2.Ini");
        touch(d, "Init/brushtex/x.bmp");
        List<SaiLocator.Found> found = SaiLocator.scan(tmp.getRoot(), 4);
        assertEquals(1, found.size());
        assertTrue(found.get(0).complete);
        assertEquals("SAI2.EXE", found.get(0).exe().getName());
    }

    @Test
    public void aWholeFolderBeatsANewerLoneExe() throws Exception {
        File root = tmp.getRoot();
        File whole = sai(root, "Download/sai2-old");
        File lone = touch(mkdirs(root, "Download/Telegram"), "sai2.exe");
        new File(whole, "sai2.exe").setLastModified(1_000_000_000_000L);
        lone.setLastModified(1_700_000_000_000L);
        assertEquals(whole, SaiLocator.best(SaiLocator.scan(root, 4)).dir);
    }

    @Test
    public void amongWholeFoldersTheNewestExeWins() throws Exception {
        File root = tmp.getRoot();
        File older = sai(root, "Download/sai2-20260702b-64bit-en");
        File newer = sai(root, "Documents/sai2-Alpha-20261019-64bit-en");
        new File(older, "sai2.exe").setLastModified(1_600_000_000_000L);
        new File(newer, "sai2.exe").setLastModified(1_700_000_000_000L);
        assertEquals(newer, SaiLocator.best(SaiLocator.scan(root, 4)).dir);
    }

    @Test
    public void bestOfNothingIsNull() {
        assertNull(SaiLocator.best(SaiLocator.scan(tmp.getRoot(), 4)));
    }

    @Test
    public void hiddenAppDataPhotosAndDeepFoldersAreNotWalked() throws Exception {
        File root = tmp.getRoot();
        sai(root, "Android/data/x");
        sai(root, ".hidden/sai");
        sai(root, "DCIM/sai");
        sai(root, "a/b/c/d/e/sai");          /* 6 levels: below maxDepth 4 */
        assertEquals(0, SaiLocator.scan(root, 4).size());
        sai(root, "a/b/c/sai");              /* 4 levels: found */
        assertEquals(1, SaiLocator.scan(root, 4).size());
    }

    @Test
    public void findsWhatNewerTelegramSavesUnderAndroidMedia() throws Exception {
        File root = tmp.getRoot();
        File dir = sai(root, "Android/media/org.telegram.messenger/Telegram/Telegram Documents/sai2-x");
        /* too deep for the walk from the storage root... */
        assertEquals(0, SaiLocator.scan(root, 4).size());
        /* ...so the launcher walks Android/media on its own */
        List<SaiLocator.Found> found = SaiLocator.scan(new File(root, "Android/media"), 4);
        assertEquals(1, found.size());
        assertEquals(dir, found.get(0).dir);
    }

    @Test
    public void findsSaiZipsButNotOtherZips() throws Exception {
        File dl = mkdirs(tmp.getRoot(), "Download");
        touch(dl, "sai2-Alpha-20261019-64bit-en.zip");
        touch(dl, "photos.zip");
        List<File> zips = SaiLocator.zips(tmp.getRoot(), 4);
        assertEquals(1, zips.size());
        assertEquals("sai2-Alpha-20261019-64bit-en.zip", zips.get(0).getName());
    }

    private File zip(File dir, String name, String... entries) throws IOException {
        File z = new File(dir, name);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(z))) {
            for (String e : entries) {
                out.putNextEntry(new ZipEntry(e));
                if (!e.endsWith("/")) out.write(new byte[] {7});
                out.closeEntry();
            }
        }
        return z;
    }

    @Test
    public void unzipsSaiIntoItsOwnFolder() throws Exception {
        /* SAI's own zip has no folder inside */
        File dl = mkdirs(tmp.getRoot(), "Download");
        File z = zip(dl, "sai2-Alpha-20261019-64bit-en.zip",
                     "sai2.exe", "sai2.ini", "init/", "init/papertex/paper.bmp");
        File dest = SaiLocator.unzipNextToIt(z);
        assertEquals(new File(dl, "sai2-Alpha-20261019-64bit-en"), dest);
        assertTrue(SaiLocator.isComplete(dest));
        assertTrue(new File(dest, "init/papertex/paper.bmp").isFile());
        /* nothing landed loose next to the zip */
        assertFalse(new File(dl, "sai2.exe").exists());
        /* a second time does not mix into the first folder */
        assertEquals(new File(dl, "sai2-Alpha-20261019-64bit-en (2)"), SaiLocator.unzipNextToIt(z));
    }

    @Test
    public void anEntryThatClimbsOutStopsTheUnpacking() throws Exception {
        File dl = mkdirs(tmp.getRoot(), "Download");
        File z = zip(dl, "sai2-evil.zip", "sai2.exe", "../outside.txt");
        try {
            SaiLocator.unzipNextToIt(z);
            fail("unpacked an entry outside its folder");
        }
        catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("outside"));
        }
        assertFalse(new File(dl, "outside.txt").exists());
    }

    @Test
    public void pickerFoldersBecomePaths() {
        String primary = "/storage/emulated/0";
        assertEquals("/storage/emulated/0/Download/Telegram/sai2",
                     SaiLocator.treeDocIdToPath("primary:Download/Telegram/sai2", primary));
        assertEquals("/storage/emulated/0", SaiLocator.treeDocIdToPath("primary:", primary));
        assertEquals("/storage/1A2B-3C4D/SAI", SaiLocator.treeDocIdToPath("1A2B-3C4D:SAI", primary));
        assertNull("a cloud folder has no path", SaiLocator.treeDocIdToPath("acc=1;doc=encoded=abc", primary));
        assertNull(SaiLocator.treeDocIdToPath("nocolon", primary));
        assertNull(SaiLocator.treeDocIdToPath(null, primary));
    }

    @Test
    public void wineScreenHasTheDisplaysProportions() {
        assertEquals("1200x720", SaiLocator.wineScreenSize(2000, 1200, 720));   /* Galaxy Tab S6 Lite */
        assertEquals("1200x720", SaiLocator.wineScreenSize(1200, 2000, 720));   /* held upright */
        assertEquals("1560x720", SaiLocator.wineScreenSize(3120, 1440, 720));   /* Galaxy S24 Ultra */
        assertEquals("1152x720", SaiLocator.wineScreenSize(2560, 1600, 720));   /* 16:10 */
        assertEquals("1282x720", SaiLocator.wineScreenSize(1366, 768, 720));    /* odd width made even */
        assertEquals("1280x720", SaiLocator.wineScreenSize(0, 0, 720));         /* unknown: 16:9 */
    }

    @Test
    public void screenHeightsNeverExceedTheDisplay() {
        /* Galaxy Tab S6 Lite: 1200 lines; 1200 is one Wine pixel per screen pixel */
        assertTrue(java.util.Arrays.equals(new int[] {720, 900, 1200}, SaiLocator.screenHeights(1200)));
        assertTrue(java.util.Arrays.equals(new int[] {720, 900, 1080}, SaiLocator.screenHeights(1080)));
        assertTrue(java.util.Arrays.equals(new int[] {720, 800}, SaiLocator.screenHeights(800)));
        assertTrue(java.util.Arrays.equals(new int[] {720}, SaiLocator.screenHeights(720)));
        assertTrue(java.util.Arrays.equals(new int[] {600}, SaiLocator.screenHeights(600)));
        /* and the default: 900 where there is room (720 looked soft) */
        assertEquals(900, SaiLocator.defaultScreenHeight(1200));
        assertEquals(720, SaiLocator.defaultScreenHeight(800));
        assertEquals(600, SaiLocator.defaultScreenHeight(600));
        /* with the display's shape: 1930x1200 at 900 lines */
        assertEquals("1448x900", SaiLocator.wineScreenSize(1930, 1200, 900));
        assertEquals("1930x1200", SaiLocator.wineScreenSize(1930, 1200, 1200));
    }

    @Test
    public void drivesPutSaiFirst() {
        /* Winlator's format (Container.drivesIterator): letter, colon, path,
         * and the path runs to the next letter-colon. S: first, so a SAI
         * folder inside Download maps to S:, not to D:\... */
        assertEquals("S:/storage/emulated/0/Download/Telegram/sai 2"
                     + "D:/storage/emulated/0/Download"
                     + "E:/data/data/sai.penpress/storage",
                     SaiLocator.drives("/storage/emulated/0/Download/Telegram/sai 2",
                                       "/storage/emulated/0/Download",
                                       "/data/data/sai.penpress/storage"));
    }

    @Test
    public void startsSaiByItselfOnlyWhenOpenedFromTheIcon() {
        assertTrue(SaiLocator.shouldAutoLaunch(true, false, false, true));
        /* THE TRAP: Winlator restarts the app when SAI exits; starting SAI
         * again then would make closing SAI open SAI */
        assertFalse(SaiLocator.shouldAutoLaunch(true, true, false, true));
        assertFalse(SaiLocator.shouldAutoLaunch(true, false, true, true));
        assertFalse(SaiLocator.shouldAutoLaunch(false, false, false, true));
        assertFalse("SAI not found yet", SaiLocator.shouldAutoLaunch(true, false, false, false));
    }

    @Test
    public void theNewestLicenceCertificateWins() throws Exception {
        /* the Mac's certificate (old System ID) and the tablet's new one */
        File root = tmp.getRoot();
        File saiDir = sai(root, "Download/Telegram/sai2");
        File mac = touch(saiDir, "sai-010062815390.slc");
        File tablet = touch(mkdirs(root, "Download"), "SAI-TABLET.SLC");
        mac.setLastModified(1_600_000_000_000L);
        tablet.setLastModified(1_700_000_000_000L);
        assertEquals(tablet, SaiLocator.newestLicence(
            java.util.Arrays.asList(saiDir, new File(root, "Download")), 2));
        assertNull(SaiLocator.newestLicence(java.util.Arrays.asList(mkdirs(root, "empty")), 2));
    }

    @Test
    public void aLicenceGoesWhereSaiV2ReadsIt() throws Exception {
        File slc = touch(mkdirs(tmp.getRoot(), "Download"), "sai-1.slc");
        Files.write(slc.toPath(), new byte[] {1, 2, 3});
        File saiV2 = new File(tmp.getRoot(), "drive_c/users/xuser/Documents/SYSTEMAX Software Development/SAIv2");
        assertTrue("written", SaiLocator.installLicence(slc, saiV2));
        assertTrue(java.util.Arrays.equals(new byte[] {1, 2, 3}, Files.readAllBytes(new File(saiV2, "sai-1.slc").toPath())));
        /* every start of SAI calls it again: no rewrite of the same bytes */
        assertFalse("unchanged", SaiLocator.installLicence(slc, saiV2));
        Files.write(slc.toPath(), new byte[] {9});
        assertTrue("a new certificate replaces the old", SaiLocator.installLicence(slc, saiV2));
    }

    @Test
    public void removingTheLicenceTakesItFromSaiOnly() throws Exception {
        File dl = mkdirs(tmp.getRoot(), "Download");
        File slc = touch(dl, "sai-1.slc");
        Files.write(slc.toPath(), new byte[] {1, 2, 3});
        File saiV2 = new File(tmp.getRoot(), "drive_c/users/xuser/Documents/SYSTEMAX Software Development/SAIv2");
        SaiLocator.installLicence(slc, saiV2);
        File other = touch(saiV2, "sai-settings.ini");
        assertEquals(1, SaiLocator.installedLicences(saiV2).size());
        assertEquals("one removed", 1, SaiLocator.removeLicences(saiV2));
        assertTrue("none left where SAI reads it", SaiLocator.installedLicences(saiV2).isEmpty());
        assertTrue("the file in Downloads stays", slc.isFile());
        assertTrue("SAI's other files stay", other.isFile());
        assertEquals("nothing to remove twice", 0, SaiLocator.removeLicences(saiV2));
        assertEquals("no SAI folder yet", 0, SaiLocator.removeLicences(new File(tmp.getRoot(), "none")));
    }

    @Test
    public void onlyASlcWithSaiInItsNameIsTakenByItself() throws Exception {
        File dl = mkdirs(tmp.getRoot(), "Download");
        File sai = touch(dl, "sai-010062815390.slc");
        File other = touch(dl, "backup.slc");                /* newer, but not SAI's by name */
        sai.setLastModified(1_600_000_000_000L);
        other.setLastModified(1_700_000_000_000L);
        assertEquals(sai, SaiLocator.newestLicence(java.util.Arrays.asList(dl), 2));
    }

    @Test
    public void aPickedFileMustBeACertificate() throws Exception {
        File saiV2 = new File(tmp.getRoot(), "SAIv2");
        /* by hand any .slc is fine, whatever its name */
        assertTrue(SaiLocator.installLicence(new byte[128], "my-licence.SLC", saiV2));
        for (Object[] bad : new Object[][] {
                {new byte[128], "photo.jpg"},
                {new byte[0], "sai-1.slc"},
                {new byte[SaiLocator.MAX_LICENCE_BYTES + 1], "sai-1.slc"}}) {
            try {
                SaiLocator.installLicence((byte[]) bad[0], (String) bad[1], saiV2);
                fail("took " + bad[1] + " of " + ((byte[]) bad[0]).length + " bytes");
            }
            catch (IOException expected) {
                assertTrue(expected.getMessage().contains("licence certificate"));
            }
        }
        assertEquals(1, SaiLocator.installedLicences(saiV2).size());
    }

    @Test
    public void aNewCertificateReplacesTheOldOne() throws Exception {
        /* THE TRAP: the Mac's certificate (another System ID) left next to
         * the tablet's could be the one SAI reads: trial mode again */
        File saiV2 = mkdirs(tmp.getRoot(), "SAIv2");
        Files.write(new File(saiV2, "sai-mac.slc").toPath(), new byte[] {1});
        assertTrue(SaiLocator.installLicence(new byte[] {2}, "sai-tablet.slc", saiV2));
        List<File> left = SaiLocator.installedLicences(saiV2);
        assertEquals(1, left.size());
        assertEquals("sai-tablet.slc", left.get(0).getName());
        /* other files of SAI's folder stay */
        File settings = mkdirs(saiV2, "settings");
        Files.write(new File(saiV2, "notes.txt").toPath(), new byte[] {3});
        SaiLocator.installLicence(new byte[] {4}, "sai-new.slc", saiV2);
        assertTrue(settings.isDirectory());
        assertTrue(new File(saiV2, "notes.txt").isFile());
    }

    @Test
    public void anOlderDownloadDoesNotThrowOutAPickedLicence() throws Exception {
        File dl = mkdirs(tmp.getRoot(), "Download");
        File saiV2 = new File(tmp.getRoot(), "SAIv2");
        File macOne = touch(dl, "sai-mac.slc");
        macOne.setLastModified(1_600_000_000_000L);
        /* picked by hand later: its copy is newer than the Mac file */
        SaiLocator.installLicence(new byte[] {7}, "chosen.slc", saiV2);
        new File(saiV2, "chosen.slc").setLastModified(1_650_000_000_000L);
        assertFalse(SaiLocator.autoInstallLicence(macOne, saiV2));
        assertEquals("chosen.slc", SaiLocator.installedLicences(saiV2).get(0).getName());

        /* a certificate downloaded after that does replace it */
        File fresh = touch(dl, "sai-tablet.slc");
        fresh.setLastModified(1_700_000_000_000L);
        assertTrue(SaiLocator.autoInstallLicence(fresh, saiV2));
        assertEquals("sai-tablet.slc", SaiLocator.installedLicences(saiV2).get(0).getName());
        /* and the same file found again at the next start is left alone */
        new File(saiV2, "sai-tablet.slc").setLastModified(1_710_000_000_000L);
        assertFalse(SaiLocator.autoInstallLicence(fresh, saiV2));
    }

    @Test
    public void theFirstCertificateIsTakenByItself() throws Exception {
        File slc = touch(mkdirs(tmp.getRoot(), "Download"), "sai-010062815390.slc");
        File saiV2 = new File(tmp.getRoot(), "SAIv2");
        assertTrue(SaiLocator.autoInstallLicence(slc, saiV2));
        assertFalse(SaiLocator.autoInstallLicence(null, saiV2));
    }

    @Test
    public void aPathInThePickedNameStaysOut() throws Exception {
        File saiV2 = new File(tmp.getRoot(), "a/SAIv2");
        SaiLocator.installLicence(new byte[] {1}, "../../evil-sai.slc", saiV2);
        assertTrue(new File(saiV2, "evil-sai.slc").isFile());
        assertFalse(new File(tmp.getRoot(), "evil-sai.slc").exists());
    }

    @Test
    public void exeOfAFoundFolderKeepsItsRealName() throws Exception {
        File d = mkdirs(tmp.getRoot(), "Download/S");
        touch(d, "Sai2.exe");
        SaiLocator.Found f = SaiLocator.best(SaiLocator.scan(tmp.getRoot(), 4));
        assertNotNull(f);
        assertEquals("Sai2.exe", f.exe().getName());
    }
}
