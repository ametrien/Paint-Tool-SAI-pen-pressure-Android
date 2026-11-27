package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Piece;
import com.winlator.saipen.DrawingLibrary.Signature;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Which drawing a session joins when the DLL saw which file its document was
 * opened from and saved to (PLAN-TIMELAPSE-WHICH-DRAWING.md, scenarios B).
 */
public class TimelapseFilesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final long DAY = 86_400_000L, T0 = 1_750_000_000_000L;

    final Signature sketch = DrawingLibraryTest.sig(DrawingLibraryTest.drawn(1, 8));
    final Signature sketchLater = DrawingLibraryTest.sig(DrawingLibraryTest.drawn(1, 14));
    final Signature changedOnPc = DrawingLibraryTest.sig(DrawingLibraryTest.drawn(5, 20));
    final Signature blank = DrawingLibraryTest.sig(DrawingLibraryTest.blank(200, 150));
    final Signature other = DrawingLibraryTest.sig(DrawingLibraryTest.drawn(9, 8));

    static Piece piece(String file, int day, Signature opening, Signature closing, String opened, String... saved) {
        Piece p = new Piece(file, T0 + day * DAY, 40, 200, 150, opening, closing);
        p.openedFrom = opened;
        p.savedTo.addAll(Arrays.asList(saved));
        return p;
    }

    /* ---- B: which drawing ---- */

    @Test
    public void openingTheSavedFileContinuesTheDrawingWithoutAQuestion() {   /* B1 */
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, blank, sketch, "", "D:\\SAI Drawings\\test1.sai2"), "NewCanvas1");
        Drawing b = lib.file(piece("2.mp4", 1, sketch, sketchLater, "D:\\SAI Drawings\\test1.sai2"), "test1");
        assertSame(a, b);
        assertEquals(2, a.pieces.size());
        assertTrue(lib.pending.isEmpty());
        assertEquals("test1", a.title);
    }

    @Test
    public void aFileChangedOnAPcStillContinuesItsDrawing() {               /* B2 */
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, blank, sketch, "", "D:\\art.sai2"), "art");
        Drawing b = lib.file(piece("2.mp4", 1, changedOnPc, changedOnPc, "d:/ART.sai2"), "art");
        assertSame("the same file, whatever case or slash", a, b);
    }

    @Test
    public void aNewCanvasSavedOverAnOldFileTakesTheFileOver() {            /* B3, the readme case */
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, blank, sketch, "", "D:\\x.sai2"), "x");
        Drawing b = lib.file(piece("2.mp4", 1, blank, other, "", "D:\\x.sai2"), "x");
        assertNotSame("a new canvas is a drawing of its own, even saved under an old name", a, b);
        assertSame(b, lib.ownerOf("D:\\x.sai2"));
        Drawing c = lib.file(piece("3.mp4", 2, other, other, "D:\\x.sai2"), "x");
        assertSame("opening x.sai2 now continues the drawing saved there last", b, c);
    }

    @Test
    public void everyNameItWasSavedUnderLeadsBackToIt() {                   /* B4, B5 */
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, blank, sketch, "", "D:\\x.sai2", "D:\\x-v2.sai2", "D:\\x.png"), "x-v2");
        assertSame(a, lib.file(piece("2.mp4", 1, sketch, sketch, "D:\\x.sai2"), "x"));
        assertSame(a, lib.file(piece("3.mp4", 2, sketch, sketch, "D:\\x-v2.sai2"), "x-v2"));
        assertSame(a, lib.file(piece("4.mp4", 3, sketch, sketch, "D:\\X.PNG"), "x.png"));
    }

    @Test
    public void aFileOnlyOpenedBeforeStillLeadsToItsDrawing() {
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, sketch, sketchLater, "E:\\from pc\\rose.psd"), "rose.psd");
        assertSame(a, lib.file(piece("2.mp4", 1, sketch, sketch, "E:\\from pc\\rose.psd"), "rose.psd"));
    }

    @Test
    public void withoutFilesItIsByLookAsBefore() {                          /* B6, B7 */
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, blank, sketch, ""), "NewCanvas1");
        Drawing b = lib.file(piece("2.mp4", 1, sketch, sketchLater, ""), "NewCanvas1");
        assertSame("reopened untouched: the same drawing, by look", a, b);
        Drawing c = lib.file(piece("3.mp4", 2, other, other, ""), "NewCanvas1");
        assertNotSame(a, c);
    }

    @Test
    public void theSameNameInTwoFoldersIsTwoDrawings() {                    /* B10 */
        DrawingLibrary lib = new DrawingLibrary();
        Drawing a = lib.file(piece("1.mp4", 0, blank, sketch, "", "D:\\a\\art.sai2"), "art");
        Drawing b = lib.file(piece("2.mp4", 0, blank, other, "", "D:\\b\\art.sai2"), "art");
        assertNotSame(a, b);
        assertSame(a, lib.file(piece("3.mp4", 1, sketch, sketch, "D:\\a\\art.sai2"), "art"));
        assertSame(b, lib.file(piece("4.mp4", 1, other, other, "D:\\b\\art.sai2"), "art"));
    }

    @Test
    public void theFilesAreKeptInDrawingsTxtAndOldFilesStillLoad() throws IOException {   /* B17 */
        DrawingLibrary lib = new DrawingLibrary();
        lib.file(piece("1.mp4", 0, blank, sketch, "D:\\рисунок.sai2", "D:\\рисунок.sai2", "D:\\b.png"), "рисунок");
        File f = tmp.newFile("drawings.txt");
        lib.save(f);
        DrawingLibrary back = DrawingLibrary.load(f);
        Piece p = back.drawings.get(0).pieces.get(0);
        assertEquals("D:\\рисунок.sai2", p.openedFrom);
        assertEquals(Arrays.asList("D:\\рисунок.sai2", "D:\\b.png"), p.savedTo);

        File old = tmp.newFile("old.txt");
        Files.write(old.toPath(), ("saipen-drawings 1\ndrawing\td1\tf\tSketch\n"
            + "piece\t1.mp4\t1750000000000\t40\t200\t150\t\t\n").getBytes(StandardCharsets.UTF_8));
        Piece o = DrawingLibrary.load(old).drawings.get(0).pieces.get(0);
        assertEquals("", o.openedFrom);
        assertTrue(o.savedTo.isEmpty());
    }

    /* ---- documents.txt ---- */

    File session(String text) throws IOException {
        File dir = tmp.newFolder();
        Files.write(new File(dir, TimelapseDocs.FILE).toPath(), text.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void documentsTxtSaysWhereADocumentCameFromAndWent() throws IOException {
        File s = session("start\t1000\n"
            + "2000\t7f0012340000\topen\tD:\\SAI Drawings\\art.sai2\tart\n"
            + "5000\t1007f0055550000\topen\tD:\\pics\\IMG_1.jpg\tIMG_1.jpg\n"
            + "9000\t7f0012340000\tsave\tD:\\SAI Drawings\\art.sai2\tart\n"
            + "9500\t7f0012340000\tsave\tD:\\SAI Drawings\\art-v2.sai2\tart\n"
            + "9600\t7f0012340000\tsave\td:/sai drawings/ART.sai2\tart-v2\n");
        TimelapseDocs.Doc d = TimelapseDocs.of(s, 0x7f0012340000L, 3000);
        assertTrue(d.known);
        assertEquals("D:\\SAI Drawings\\art.sai2", d.openedFrom);
        assertEquals("one file once, whatever case or slash",
                     Arrays.asList("D:\\SAI Drawings\\art.sai2", "D:\\SAI Drawings\\art-v2.sai2"), d.savedTo);
        assertEquals("art-v2", d.title());

        TimelapseDocs.Doc photo = TimelapseDocs.of(s, 0x1007f0055550000L, 6000);
        assertEquals("a second document at a reused address has its own id", "D:\\pics\\IMG_1.jpg", photo.openedFrom);
        assertEquals("IMG_1", photo.title());
        assertTrue(TimelapseDocs.of(s, 0x7f0055550000L, 6000).isEmpty());
    }

    @Test
    public void aNewCanvasIsKnownButOpenedFromNothing() throws IOException {
        File s = session("start\t1000\n4000\tabc\tsave\tD:\\n.sai2\tNewCanvas1\n");
        TimelapseDocs.Doc d = TimelapseDocs.of(s, 0xabc, 2000);
        assertTrue(d.known);
        assertEquals("", d.openedFrom);
        assertEquals("n", d.title());
        TimelapseDocs.Doc none = TimelapseDocs.of(s, 0xdef, 2000);
        assertTrue(none.known);
        assertTrue(none.isEmpty());
    }

    @Test
    public void anotherSaiStartsFileSaysNothing() throws IOException {      /* B8 */
        File s = session("start\t50000\n60000\tabc\topen\tD:\\a.sai2\ta\n");
        TimelapseDocs.Doc d = TimelapseDocs.of(s, 0xabc, 2000);
        assertFalse(d.known);
        assertTrue(d.isEmpty());
        assertFalse(TimelapseDocs.of(tmp.newFolder(), 0xabc, 2000).known);
    }

    @Test
    public void halfALineOrRubbishIsSkipped() throws IOException {          /* B14 */
        File s = session("start\t1000\n2000\tabc\topen\tD:\\a.sai2\ta\nzz\tqq\topen\tD:\\b.sai2\tb\n3000\tabc\tsa");
        TimelapseDocs.Doc d = TimelapseDocs.of(s, 0xabc, 2500);
        assertEquals("D:\\a.sai2", d.openedFrom);
        assertTrue(d.savedTo.isEmpty());
    }

    @Test
    public void copyKeepsTheFileWithTheFramesAndOnlyWhenItChanged() throws IOException {
        File src = tmp.newFile("documents.txt");
        File dir = new File(tmp.getRoot(), "2026-11-27 18-54-00");
        Files.write(src.toPath(), "start\t1\n".getBytes(StandardCharsets.UTF_8));
        TimelapseDocs.copyInto(src, dir);
        File dest = new File(dir, TimelapseDocs.FILE);
        assertEquals("start\t1\n", new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));
        Files.write(src.toPath(), "start\t1\n2\tabc\tsave\tD:\\a.sai2\ta\n".getBytes(StandardCharsets.UTF_8));
        TimelapseDocs.copyInto(src, dir);
        assertTrue(new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8).contains("a.sai2"));
        TimelapseDocs.copyInto(new File(tmp.getRoot(), "none.txt"), dir);
        assertTrue("no source: what was copied stays", dest.isFile());
    }

    @Test
    public void aSessionFolderWithOnlyDocumentsTxtLeftGoes() throws IOException {
        File root = tmp.newFolder("packed");
        File s = new File(root, "2026-11-27 18-54-00");
        assertTrue(s.mkdirs());
        Files.write(new File(s, TimelapseDocs.FILE).toPath(), "start\t1\n".getBytes(StandardCharsets.UTF_8));
        TimelapsePack.removeEmptySessions(root);
        assertFalse(s.exists());
    }

    @Test
    public void stemAndKey() {
        assertEquals("art", TimelapseDocs.stem("D:\\SAI Drawings\\art.sai2"));
        assertEquals("a.b", TimelapseDocs.stem("D:/x/a.b.png"));
        assertEquals("noext", TimelapseDocs.stem("C:noext"));
        assertEquals(TimelapseDocs.key("D:\\A\\b.SAI2"), TimelapseDocs.key(" d:/a/B.sai2"));
    }
}
