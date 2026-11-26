package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.winlator.saipen.DrawingIndex.Entry;
import com.winlator.saipen.DrawingIndex.Sort;
import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Piece;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DrawingIndexTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private long time = 1_700_000_000_000L;

    private File drawing(String path, int bytes) throws Exception {
        File f = new File(tmp.getRoot(), path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[bytes]);
        f.setLastModified(time += 60_000);
        return f;
    }

    private static List<String> titles(List<Entry> l) {
        List<String> r = new ArrayList<>();
        for (Entry e : l) r.add(e.title);
        return r;
    }

    @Test
    public void foldersAreRelativeToSaiDrawings() throws Exception {
        drawing("top.sai2", 1);
        drawing("Cats/kitty.sai2", 1);
        drawing("Cats/old/tom.sai2", 1);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("tom", "kitty", "top"), titles(all));
        assertEquals("Cats/old", all.get(0).folder);
        assertEquals("Cats", all.get(1).folder);
        assertEquals("", all.get(2).folder);
        assertEquals(Arrays.asList("", "Cats", "Cats/old"), DrawingIndex.folders(all));
    }

    @Test
    public void timelapseFolderIsNotADrawingFolder() throws Exception {
        drawing("a.sai2", 1);
        drawing("Timelapses/x.sai2", 1);
        assertEquals(Arrays.asList("a"), titles(DrawingIndex.all(tmp.getRoot())));
    }

    @Test
    public void threeOrders() throws Exception {
        drawing("banana.sai2", 30);
        drawing("Apple.sai2", 10);
        drawing("cherry.sai2", 20);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("cherry", "Apple", "banana"), titles(DrawingIndex.view(all, "", null, Sort.NEWEST)));
        assertEquals(Arrays.asList("Apple", "banana", "cherry"), titles(DrawingIndex.view(all, "", null, Sort.NAME)));
        assertEquals(Arrays.asList("banana", "cherry", "Apple"), titles(DrawingIndex.view(all, "", null, Sort.SIZE)));
    }

    @Test
    public void referencesAreNotDrawings() throws Exception {
        drawing("a.sai2", 1);
        drawing("References/face.jpg", 1);
        drawing("References/hands/b.png", 1);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("a"), titles(all));
        assertEquals(Arrays.asList(""), DrawingIndex.folders(all));
    }

    @Test
    public void hiddenKindsLeftOut() throws Exception {
        drawing("a.sai2", 1);
        drawing("b.png", 1);
        drawing("c.jpeg", 1);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("a"), titles(DrawingIndex.view(all, "", null, Sort.NAME, false,
                                                                  DrawingFormats.parse("png,jpg"))));
        assertEquals(Arrays.asList("a", "b.png", "c.jpeg"), titles(DrawingIndex.view(all, "", null, Sort.NAME)));
    }

    @Test
    public void eachOrderFlips() throws Exception {
        drawing("banana.sai2", 30);
        drawing("Apple.sai2", 10);
        drawing("cherry.sai2", 20);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("banana", "Apple", "cherry"), titles(DrawingIndex.view(all, "", null, Sort.NEWEST, true)));
        assertEquals(Arrays.asList("cherry", "banana", "Apple"), titles(DrawingIndex.view(all, "", null, Sort.NAME, true)));
        assertEquals(Arrays.asList("Apple", "cherry", "banana"), titles(DrawingIndex.view(all, "", null, Sort.SIZE, true)));
    }

    @Test
    public void searchIgnoresCaseAndSpaces() throws Exception {
        drawing("Red Fox.sai2", 1);
        drawing("blue fox.sai2", 1);
        drawing("cat.sai2", 1);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("blue fox", "Red Fox"), titles(DrawingIndex.view(all, "  FOX ", null, Sort.NAME)));
        assertEquals(3, DrawingIndex.view(all, "   ", null, Sort.NAME).size());
        assertEquals(0, DrawingIndex.view(all, "dog", null, Sort.NAME).size());
    }

    @Test
    public void aFolderHoldsItsSubfoldersButNotItsNamesakes() throws Exception {
        drawing("top.sai2", 1);
        drawing("Cats/kitty.sai2", 1);
        drawing("Cats/old/tom.sai2", 1);
        drawing("Cats2/not.sai2", 1);
        List<Entry> all = DrawingIndex.all(tmp.getRoot());
        assertEquals(Arrays.asList("kitty", "tom"), titles(DrawingIndex.view(all, "", "Cats", Sort.NAME)));
        assertEquals(Arrays.asList("top"), titles(DrawingIndex.view(all, "", "", Sort.NAME)));
        assertEquals(4, DrawingIndex.view(all, "", null, Sort.NAME).size());
    }

    @Test
    public void foldersDifferingOnlyInCaseStayTwo() throws Exception {
        /* made by hand: the Mac's disk does not tell "Cats" from "cats" */
        File root = tmp.getRoot();
        List<Entry> all = Arrays.asList(
            new Entry(new File(root, "Cats/a.sai2"), "Cats", "a", 1, 1),
            new Entry(new File(root, "cats/b.sai2"), "cats", "b", 2, 1));
        List<String> folders = DrawingIndex.folders(all);
        assertEquals(2, folders.size());
        for (String f : folders) assertEquals(1, DrawingIndex.view(all, "", f, Sort.NAME).size());
    }

    @Test
    public void totalSize() throws Exception {
        drawing("a.sai2", 5);
        drawing("b.sai2", 7);
        assertEquals(12, DrawingIndex.totalSize(DrawingIndex.all(tmp.getRoot())));
    }

    private static Drawing timelapse(String title, long... starts) {
        Drawing d = new Drawing("id-" + title + starts.length + starts[0], title, title);
        for (long s : starts) d.pieces.add(new Piece(s + ".mp4", s, 10, 100, 100, null, null));
        return d;
    }

    @Test
    public void timelapseByNameWithOrWithoutExtension() throws Exception {
        Entry fox = DrawingIndex.entry(drawing("Fox.sai2", 1), tmp.getRoot());
        Drawing other = timelapse("Cat", 1);
        Drawing withExt = timelapse("fox.sai2", 5);
        assertSame(withExt, DrawingIndex.timelapseFor(fox, Arrays.asList(other, withExt)));
        Drawing plain = timelapse("FOX", 2);
        assertSame(plain, DrawingIndex.timelapseFor(fox, Arrays.asList(plain, other)));
        assertNull(DrawingIndex.timelapseFor(fox, Arrays.asList(other)));
    }

    @Test
    public void twoTimelapsesWithOneNameGiveTheLastDrawn() throws Exception {
        Entry fox = DrawingIndex.entry(drawing("fox.sai2", 1), tmp.getRoot());
        Drawing older = timelapse("fox", 1, 50);
        Drawing newer = timelapse("fox", 60);
        assertSame(newer, DrawingIndex.timelapseFor(fox, Arrays.asList(newer, older)));
        assertSame(newer, DrawingIndex.timelapseFor(fox, Arrays.asList(older, newer)));
    }

    @Test
    public void aTimelapseWithNoSessionsIsNone() throws Exception {
        Entry fox = DrawingIndex.entry(drawing("fox.sai2", 1), tmp.getRoot());
        Drawing empty = new Drawing("e", "fox", "fox");
        assertNull(DrawingIndex.timelapseFor(fox, Arrays.asList(empty)));
    }
}
