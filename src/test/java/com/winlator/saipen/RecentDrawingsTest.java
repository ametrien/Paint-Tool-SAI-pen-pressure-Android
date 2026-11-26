package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecentDrawingsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private long time = 1_700_000_000_000L;

    private File drawing(File dir, String name) throws Exception {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[]{1});
        f.setLastModified(time += 60_000);
        return f;
    }

    private static List<String> names(List<File> files) {
        List<String> r = new ArrayList<>();
        for (File f : files) r.add(f.getName());
        return r;
    }

    @Test
    public void newestFirst() throws Exception {
        File root = tmp.getRoot();
        drawing(root, "old.sai2");
        drawing(root, "middle.sai2");
        drawing(root, "new.sai2");
        assertEquals(List.of("new.sai2", "middle.sai2", "old.sai2"), names(RecentDrawings.newest(root, 12)));
    }

    @Test
    public void onlyTheNewestUpToMax() throws Exception {
        File root = tmp.getRoot();
        for (int i = 0; i < 5; i++) drawing(root, "d" + i + ".sai2");
        assertEquals(List.of("d4.sai2", "d3.sai2"), names(RecentDrawings.newest(root, 2)));
    }

    @Test
    public void onlySai2FilesAndNotTheTimelapses() throws Exception {
        File root = tmp.getRoot();
        drawing(root, "a.sai2");
        drawing(root, "B.SAI2");
        drawing(root, "picture.png");
        drawing(root, "old.sai");
        drawing(root, ".hidden.sai2");
        drawing(new File(root, "Timelapses"), "x.sai2");
        Files.write(new File(root, "empty.sai2").toPath(), new byte[0]);
        assertEquals(List.of("B.SAI2", "a.sai2"), names(RecentDrawings.newest(root, 12)));
    }

    @Test
    public void subfoldersToo() throws Exception {
        File root = tmp.getRoot();
        drawing(new File(root, "comics/page 1"), "deep.sai2");
        drawing(new File(root, "a/b/c/d/e"), "too-deep.sai2");
        assertEquals(List.of("deep.sai2"), names(RecentDrawings.newest(root, 12)));
    }

    @Test
    public void missingFolderIsEmpty() {
        assertEquals(List.of(), RecentDrawings.newest(new File(tmp.getRoot(), "none"), 12));
    }

    @Test
    public void windowsPathOnDriveF() {
        File root = new File("/storage/emulated/0/Documents/SAI Drawings");
        assertEquals("F:\\New Canvas.sai2", RecentDrawings.windowsPath(new File(root, "New Canvas.sai2"), root));
        assertEquals("F:\\comics\\p1.sai2", RecentDrawings.windowsPath(new File(root, "comics/p1.sai2"), root));
        assertNull(RecentDrawings.windowsPath(new File("/storage/emulated/0/Download/x.sai2"), root));
        assertNull(RecentDrawings.windowsPath(new File("/storage/emulated/0/Documents/SAI Drawings2/x.sai2"), root));
    }

    @Test
    public void titleWithoutExtension() {
        assertEquals("New Canvas", RecentDrawings.title(new File("New Canvas.sai2")));
        assertEquals("X", RecentDrawings.title(new File("X.SAI2")));
    }
}
