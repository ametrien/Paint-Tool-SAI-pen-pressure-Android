package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DeviceDrawingsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File xuser, device;
    private List<File> folders;

    @Before
    public void prefix() throws Exception {
        xuser = tmp.newFolder("drive_c", "users", "xuser");
        for (String f : new String[] {"Desktop", "Documents", "Pictures", "Downloads", "AppData"}) {
            new File(xuser, f).mkdirs();
        }
        device = new File(tmp.getRoot(), "storage/Documents/SAI Drawings");
        folders = new ArrayList<>();
        for (String f : DeviceDrawings.FOLDERS) folders.add(f.isEmpty() ? xuser : new File(xuser, f));
    }

    private File write(File dir, String name, String text) throws Exception {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes("UTF-8"));
        return f;
    }

    private String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    private List<File> pass() {
        return DeviceDrawings.moveOut(folders, device);
    }

    private boolean gone(File f) {
        return !Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private String[] deviceNames() {
        String[] names = device.list();
        Arrays.sort(names);
        return names;
    }

    @Test
    public void aDrawingSavedInTheUserFolderMovesToTheDevice() throws Exception {
        File saved = write(xuser, "yyuuhb.sai2", "my drawing");
        List<File> moved = pass();

        File onDevice = new File(device, "yyuuhb.sai2");
        assertEquals(Arrays.asList(onDevice), moved);
        assertEquals("my drawing", read(onDevice));
        assertTrue("nothing left inside the app, not even a link", gone(saved));
        assertArrayEquals("no half copy left", new String[] {"yyuuhb.sai2"}, deviceNames());
    }

    @Test
    public void onlyDrawingsRightInTheFoldersGo() throws Exception {
        write(new File(xuser, "Desktop"), "sketch.PNG", "png");
        write(new File(xuser, "Documents"), "comic.psd", "psd");
        write(new File(xuser, "Pictures"), "ref.jpeg", "jpg");
        File notes = write(xuser, "notes.txt", "not a drawing");
        File shortcut = write(new File(xuser, "Desktop"), "SAI.lnk", "lnk");
        File temp = write(xuser, "half-saved.tmp", "tmp");
        File deep = write(new File(xuser, "AppData/Local/SAI"), "thumb.png", "app data");
        File settings = write(new File(xuser, "Documents/SYSTEMAX Software Development/SAIv2/settings"),
                              "settings2.ssd", "SAI's own");

        assertEquals(3, pass().size());

        assertArrayEquals(new String[] {"comic.psd", "ref.jpeg", "sketch.PNG"}, deviceNames());
        for (File stays : new File[] {notes, shortcut, temp, deep, settings}) assertTrue(stays + " stays", stays.isFile());
    }

    @Test
    public void foldersThatAreLinksToTheDeviceAreLeftAlone() throws Exception {
        /* users/xuser/SAI Drawings is the device's folder: its files are there already */
        Files.createDirectories(device.toPath());
        Files.createSymbolicLink(new File(xuser, "SAI Drawings").toPath(), device.toPath());
        write(device, "saved-right.sai2", "already on the device");

        assertTrue(pass().isEmpty());
        assertEquals("already on the device", read(new File(device, "saved-right.sai2")));
        assertArrayEquals(new String[] {"saved-right.sai2"}, deviceNames());
    }

    @Test
    public void aSaiDrawingsFolderThatNeverBecameALinkIsEmptiedToo() throws Exception {
        File stuck = write(new File(xuser, "SAI Drawings"), "stuck.sai2", "stuck");
        pass();
        assertTrue(gone(stuck));
        assertEquals("stuck", read(new File(device, "stuck.sai2")));
    }

    @Test
    public void aTakenNameKeepsBothDrawings() throws Exception {
        /* THE TRAP: never overwrite a drawing on the device */
        write(device, "NewCanvas1.sai2", "saved into SAI Drawings last week");
        write(xuser, "NewCanvas1.sai2", "another drawing");

        pass();
        assertEquals("saved into SAI Drawings last week", read(new File(device, "NewCanvas1.sai2")));
        assertEquals("another drawing", read(new File(device, "NewCanvas1 (2).sai2")));
    }

    @Test
    public void theSameDrawingAlreadyOnTheDeviceLetsTheInAppCopyGo() throws Exception {
        write(device, "copied.sai2", "same bytes");
        File inApp = write(xuser, "copied.sai2", "same bytes");
        pass();
        assertTrue(gone(inApp));
        assertArrayEquals(new String[] {"copied.sai2"}, deviceNames());
    }

    @Test
    public void linksLeftBy0423AreTakenAwayButNotWhatTheyPointTo() throws Exception {
        /* SAI could not save through a link to a single file */
        File onDevice = write(device, "linked.sai2", "the drawing");
        Path link = new File(xuser, "linked.sai2").toPath();
        Files.createSymbolicLink(link, onDevice.toPath());

        assertTrue(pass().isEmpty());
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertEquals("the drawing", read(onDevice));
    }

    @Test
    public void someoneElsesLinkIsLeftAlone() throws Exception {
        File elsewhere = write(new File(tmp.getRoot(), "elsewhere"), "theirs.sai2", "theirs");
        Path link = new File(xuser, "theirs.sai2").toPath();
        Files.createSymbolicLink(link, elsewhere.toPath());

        assertTrue(pass().isEmpty());
        assertEquals(elsewhere.toPath(), Files.readSymbolicLink(link));
        assertTrue(elsewhere.isFile());
    }

    @Test
    public void everyPassAfterTheFirstMovesNothing() throws Exception {
        write(xuser, "a.sai2", "a");
        write(new File(xuser, "Desktop"), "b.png", "b");
        assertEquals(2, pass().size());
        assertTrue(pass().isEmpty());
        assertArrayEquals(new String[] {"a.sai2", "b.png"}, deviceNames());
    }

    @Test
    public void noAccessToTheDeviceLeavesEveryDrawingInPlace() throws Exception {
        File blocker = write(tmp.getRoot(), "storage-not-a-folder", "x");
        File saved = write(xuser, "safe.sai2", "safe");
        assertTrue(DeviceDrawings.moveOut(folders, new File(blocker, "SAI Drawings")).isEmpty());
        assertEquals("safe", read(saved));
    }

    @Test
    public void aFailedCopyLeavesTheDrawingAndNoHalfFile() throws Exception {
        Files.createDirectories(device.toPath());
        File saved = write(xuser, "kept.sai2", "kept");
        assertTrue(device.setWritable(false));
        try {
            assertTrue(pass().isEmpty());
        }
        finally {
            device.setWritable(true);
        }
        assertEquals("kept", read(saved));
        assertEquals(0, device.list().length);
        pass();
        assertTrue(gone(saved));
        assertEquals("kept", read(new File(device, "kept.sai2")));
    }

    @Test
    public void freeNamesCountUp() throws Exception {
        write(device, "a.sai2", "1");
        write(device, "a (2).sai2", "2");
        assertEquals("a (3).sai2", DeviceDrawings.freeName(device, "a.sai2").getName());
        assertEquals("noext (2)", DeviceDrawings.freeName(device, "noext").getName());
    }

    @Test
    public void drawingTypesAreSaisOwn() {
        for (String n : new String[] {"a.sai2", "a.SAI", "a.psd", "a.psb", "a.png", "a.jpg", "a.JPEG", "a.bmp", "a.tga"}) {
            assertTrue(n, DeviceDrawings.isDrawing(n));
        }
        for (String n : new String[] {"a.tmp", "a.txt", "a.lnk", "png", ".png", "a.sai2.saipen-part", "settings2.ssd"}) {
            assertFalse(n, DeviceDrawings.isDrawing(n));
        }
    }

    /* ---- keepOriginals: SAI's Recent list keeps working ---- */

    private List<File> passKeep() {
        return DeviceDrawings.moveOut(folders, device, true);
    }

    @Test
    public void keepOriginalsLeavesTheDrawingInsideTheApp() throws Exception {
        File saved = write(xuser, "recent.sai2", "my drawing");
        List<File> moved = passKeep();

        File onDevice = new File(device, "recent.sai2");
        assertEquals(Arrays.asList(onDevice), moved);
        assertEquals("my drawing", read(onDevice));
        assertTrue("original stays for SAI's Recent", saved.isFile());
    }

    @Test
    public void keepOriginalsStillCopiesToTheDevice() throws Exception {
        write(new File(xuser, "Desktop"), "sketch.PNG", "png");
        write(new File(xuser, "Documents"), "comic.psd", "psd");
        assertEquals(2, passKeep().size());
        /* both originals still inside the app */
        assertTrue(new File(xuser, "Desktop/sketch.PNG").isFile());
        assertTrue(new File(xuser, "Documents/comic.psd").isFile());
        assertArrayEquals(new String[] {"comic.psd", "sketch.PNG"}, deviceNames());
    }

    @Test
    public void keepOriginalsDoesNotDuplicateOnRepeatedPasses() throws Exception {
        write(xuser, "a.sai2", "a");
        passKeep();
        passKeep();     /* the original is there, same content on the device: no "(2)" */
        assertArrayEquals(new String[] {"a.sai2"}, deviceNames());
        assertTrue("original still stays", new File(xuser, "a.sai2").isFile());
    }

    @Test
    public void aRegularPassAfterKeepCleansUp() throws Exception {
        File saved = write(xuser, "a.sai2", "a");
        passKeep();     /* copy to device, keep original */
        assertTrue(saved.isFile());
        pass();         /* copy + delete original: SAI is closing */
        assertTrue(gone(saved));
        assertEquals("a", read(new File(device, "a.sai2")));
    }

    @Test
    public void listScannableReturnsDrawingsAndVideos() throws Exception {
        device.mkdirs();
        write(device, "sketch.sai2", "sai2");
        write(device, "render.png", "png");
        write(device, "timelapse.mp4", "mp4");
        write(device, ".sketch.sai2.saipen-part", "part");
        write(device, ".hidden", "hidden");
        write(device, "notes.txt", "txt");
        new File(device, "subfolder").mkdirs();

        List<File> scannable = DeviceDrawings.listScannable(device);
        List<String> names = new ArrayList<>();
        for (File f : scannable) names.add(f.getName());
        java.util.Collections.sort(names);

        assertEquals(Arrays.asList("render.png", "sketch.sai2", "timelapse.mp4"), names);
    }

    @Test
    public void listScannableFindsFilesInSubdirectories() throws Exception {
        device.mkdirs();
        File sub = new File(device, "Timelapses");
        write(device, "sketch.sai2", "sai2");
        write(sub, "video.mp4", "mp4");
        write(sub, ".hidden.mp4", "hidden");

        List<File> scannable = DeviceDrawings.listScannable(device);
        List<String> names = new ArrayList<>();
        for (File f : scannable) names.add(f.getName());
        java.util.Collections.sort(names);

        assertEquals(Arrays.asList("sketch.sai2", "video.mp4"), names);
    }

    @Test
    public void migrateTimelapsesMovesMp4Files() throws Exception {
        device.mkdirs();
        File timelapses = new File(device, "Timelapses");
        File v1 = write(device, "draw1 timelapse.mp4", "v1");
        File v2 = write(device, "draw2 timelapse.mp4", "v2");
        File s = write(device, "canvas.sai2", "sai2");

        List<File> moved = DeviceDrawings.migrateTimelapses(device, timelapses);
        assertEquals(2, moved.size());
        assertFalse(v1.exists());
        assertFalse(v2.exists());
        assertTrue(s.exists());
        assertTrue(new File(timelapses, "draw1 timelapse.mp4").exists());
        assertTrue(new File(timelapses, "draw2 timelapse.mp4").exists());
    }

    /* ---- delete (the Drawings window) ---- */

    @Test
    public void deleteTakesTheInAppCopyWithIt() throws Exception {
        write(new File(xuser, "Desktop"), "fox.sai2", "fox");
        DeviceDrawings.moveOut(folders, device, true);          /* before SAI starts: copied, kept */
        File onDevice = new File(device, "fox.sai2");
        assertTrue(onDevice.isFile());
        assertTrue(DeviceDrawings.delete(onDevice, folders));
        assertTrue(gone(onDevice));
        assertTrue(gone(new File(xuser, "Desktop/fox.sai2")));
        pass();
        assertEquals(0, deviceNames().length);                  /* it does not come back */
    }

    @Test
    public void deleteKeepsAnInAppFileWithOtherContent() throws Exception {
        File onDevice = write(device, "fox.sai2", "old fox");
        File newer = write(new File(xuser, "Desktop"), "fox.sai2", "new fox");
        assertTrue(DeviceDrawings.delete(onDevice, folders));
        assertTrue(gone(onDevice));
        assertEquals("new fox", read(newer));
    }

    @Test
    public void deleteFindsACopyUnderAnotherName() throws Exception {
        File onDevice = write(device, "fox (2).sai2", "fox");
        File inApp = write(xuser, "fox.sai2", "fox");
        assertTrue(DeviceDrawings.delete(onDevice, folders));
        assertTrue(gone(inApp));
    }

    @Test
    public void deletePassesOverTheLinkedDrawingsFolder() throws Exception {
        File onDevice = write(device, "fox.sai2", "fox");
        File other = write(device, "fox copy.sai2", "fox");     /* the same picture saved twice on purpose */
        Files.createSymbolicLink(new File(xuser, "SAI Drawings").toPath(), device.toPath());
        assertTrue(DeviceDrawings.delete(onDevice, folders));
        assertTrue(gone(onDevice));
        assertTrue(other.isFile());
    }

    @Test
    public void aDrawingThatCannotBeDeletedKeepsItsInAppCopy() throws Exception {
        File missing = new File(device, "fox.sai2");
        File inApp = write(new File(xuser, "Desktop"), "fox.sai2", "fox");
        assertFalse(DeviceDrawings.delete(missing, folders));
        assertTrue(inApp.isFile());
    }
}
