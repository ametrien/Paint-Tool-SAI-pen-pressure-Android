package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Kind;
import com.winlator.saipen.DrawingLibrary.Match;
import com.winlator.saipen.DrawingLibrary.Pending;
import com.winlator.saipen.DrawingLibrary.Piece;
import com.winlator.saipen.DrawingLibrary.Signature;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The Mac app's tests/LibraryTests.swift, case for case, with its canvases:
 * the thresholds were tuned on these, so the port must give the same answers.
 */
public class DrawingLibraryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /* ---- synthetic canvases, as on the Mac ---- */

    static byte[] blank(int w, int h) {
        byte[] px = new byte[w * h * 4];
        Arrays.fill(px, (byte) 0xff);
        return px;
    }

    static byte[] blank() {
        return blank(200, 150);
    }

    private static final long A = 6_364_136_223_846_793_005L, C = 1_442_695_040_888_963_407L;

    /** `strokes` dark marks; `seed` picks the picture, more strokes of the
     *  same seed are another evening on the SAME drawing. Marks are fractions
     *  of the canvas, so another size is the same picture scaled. */
    static byte[] drawn(int w, int h, int seed, int strokes) {
        byte[] px = blank(w, h);
        long[] rng = {seed * A + C};
        for (int s = 0; s < strokes; s++) {
            double cx = next(rng), cy = next(rng);
            double rw = 0.08 + next(rng) * 0.22, rh = 0.04 + next(rng) * 0.18;
            int x0 = (int) (cx * w), y0 = (int) (cy * h);
            int x1 = Math.min(w, x0 + Math.max(1, (int) (rw * w)));
            int y1 = Math.min(h, y0 + Math.max(1, (int) (rh * h)));
            if (x0 >= x1 || y0 >= y1) continue;
            ink(px, w, x0, y0, x1, y1);
        }
        return px;
    }

    static byte[] drawn(int seed, int strokes) {
        return drawn(200, 150, seed, strokes);
    }

    /** A few SMALL marks: a drawing barely begun. Two of these are close by
     *  distance and uncorrelated. */
    static byte[] sparse(int seed, int marks, double scale) {
        int w = 200, h = 150;
        byte[] px = blank(w, h);
        long[] rng = {seed * A + C};
        for (int m = 0; m < marks; m++) {
            int x0 = (int) (next(rng) * w * 0.9), y0 = (int) (next(rng) * h * 0.9);
            int x1 = Math.min(w, x0 + Math.max(1, (int) (scale * w)));
            int y1 = Math.min(h, y0 + Math.max(1, (int) (scale * h)));
            ink(px, w, x0, y0, x1, y1);
        }
        return px;
    }

    static byte[] sparse(int seed) {
        return sparse(seed, 3, 0.06);
    }

    private static double next(long[] rng) {
        rng[0] = rng[0] * A + C;
        return Long.remainderUnsigned(rng[0] >>> 33, 10_000) / 10_000.0;
    }

    private static void ink(byte[] px, int w, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int o = (y * w + x) * 4;
                px[o] = px[o + 1] = px[o + 2] = 0x28;
                px[o + 3] = (byte) 0xff;
            }
    }

    static Signature sig(byte[] px) {
        return Signature.of(px, 200, 150, 800);
    }

    static Piece piece(String file, int day, Signature opening, Signature closing) {
        return new Piece(file, 1_750_000_000_000L + day * 86_400_000L, 40, opening.width, opening.height, opening, closing);
    }

    final Signature mondayNight = sig(drawn(1, 8));     /* where we left off */
    final Signature tuesdayStart = sig(drawn(1, 8));    /* reopened, untouched */
    final Signature tuesdayEnd = sig(drawn(1, 14));     /* an evening later */
    final Signature otherDrawing = sig(drawn(9, 8));

    DrawingLibrary withSketch(String title) {
        DrawingLibrary lib = new DrawingLibrary();
        lib.addDrawing(title, piece("2026-07-27 2015.mp4", 0, sig(blank()), mondayNight));
        return lib;
    }

    DrawingLibrary one(String title, Signature closing) {
        DrawingLibrary lib = new DrawingLibrary();
        lib.addDrawing(title, piece("a.mp4", 0, sig(blank()), closing));
        return lib;
    }

    /* ---- fingerprints ---- */

    @Test
    public void fingerprintsAreMadeAndBadBuffersRefused() {
        assertTrue(sig(blank()).isValid());
        assertTrue(sig(drawn(1, 8)).isValid());
        assertNull("a buffer too small", Signature.of(new byte[10], 200, 150, 800));
        assertNull("a stride narrower than a row", Signature.of(blank(), 200, 150, 100));
    }

    @Test
    public void distanceAndSimilarityMeasureWhatTheMacMeasured() {
        Signature d = sig(drawn(1, 8));
        assertEquals(0, Signature.distance(d, d), 0);
        assertTrue(Signature.distance(d, sig(drawn(9, 8))) > DrawingLibrary.ASK_DISTANCE);
        Signature small = Signature.of(drawn(100, 75, 1, 8), 100, 75, 400);
        assertTrue("the same drawing at another size", Signature.distance(d, small) < DrawingLibrary.SAME_DISTANCE);
        assertTrue(sig(blank()).isFeatureless());
        assertFalse(d.isFeatureless());

        Signature one = sig(drawn(4, 1)), other = sig(drawn(11, 1));
        assertTrue("sparse canvases are close by distance", Signature.distance(one, other) < DrawingLibrary.ASK_DISTANCE);
        assertTrue("but uncorrelated", Signature.similarity(one, other) < DrawingLibrary.ASK_SIMILARITY);
        assertTrue(Signature.similarity(d, d) > 0.99);
        assertTrue(Signature.similarity(sig(blank()), sig(blank())) < DrawingLibrary.ASK_SIMILARITY);
        assertTrue("an evening's work still correlates",
                   Signature.similarity(d, sig(drawn(1, 14))) >= DrawingLibrary.ASK_SIMILARITY);
    }

    /* ---- the ladder ---- */

    @Test
    public void reopeningADrawingContinuesIt() {
        DrawingLibrary lib = withSketch("Sketch");
        Match m = lib.classify(tuesdayStart, "Sketch", "s.mp4");
        assertEquals(Kind.SAME, m.kind);
        assertEquals(lib.drawings.get(0).id, m.drawingId);
    }

    @Test
    public void aRenamedCanvasIsStillTheSameDrawing() {
        DrawingLibrary lib = withSketch("Sketch");
        assertEquals(Kind.SAME, lib.classify(tuesdayStart, "final FINAL v3", "s.mp4").kind);
    }

    @Test
    public void twoBlankCanvasesAreNotTheSameDrawing() {
        DrawingLibrary lib = one("NewCanvas1", sig(blank()));
        assertEquals(Kind.NEW, lib.classify(sig(blank()), "NewCanvas1", "b.mp4").kind);
    }

    @Test
    public void twoBarelyBegunCanvasesAreNotMerged() {
        assertTrue(Signature.distance(sig(sparse(4)), sig(sparse(11))) < DrawingLibrary.SAME_DISTANCE);
        DrawingLibrary lib = one("NewCanvas1", sig(sparse(4)));
        assertEquals(Kind.NEW, lib.classify(sig(sparse(11)), "NewCanvas1", "b.mp4").kind);
    }

    @Test
    public void oneFaintSpeckIsTooLittleToJudge() {
        Signature faint = sig(sparse(4, 1, 0.02));
        assertTrue(Signature.similarity(faint, faint) > 0.99);
        assertEquals(0, Signature.distance(faint, faint), 0);
        DrawingLibrary lib = one("NewCanvas1", faint);
        assertEquals(Kind.NEW, lib.classify(faint, "NewCanvas1", "b.mp4").kind);
    }

    /** A strong picture and the same picture nearly invisible (a hidden
     *  layer, a faint first sketch): the ink is in the same places, but the
     *  faint one has too little on it to prove anything, whichever side it
     *  is on. Each side has its own guard. */
    private static Signature cells(int ink) {
        byte[] c = new byte[Signature.SIDE * Signature.SIDE];
        for (int i = 0; i < c.length; i++) c[i] = (byte) (i % 7 == 0 ? ink : 255);
        return new Signature(c, 200, 150);
    }

    @Test
    public void aNearlyBlankOpeningProvesNothing() {
        Signature strong = cells(40), faint = cells(248);
        assertTrue(faint.isFeatureless());
        assertFalse(strong.isFeatureless());
        assertTrue(Signature.similarity(strong, faint) > 0.99);
        assertEquals(Kind.NEW, one("Sketch2", strong).classify(faint, "Other", "b.mp4").kind);
    }

    @Test
    public void aNearlyBlankDrawingInTheLibraryProvesNothing() {
        Signature strong = cells(40), faint = cells(248);
        assertEquals(Kind.NEW, one("Sketch2", faint).classify(strong, "Other", "b.mp4").kind);
    }

    @Test
    public void twoDrawingsOneStrokeOldAreNotTheSame() {
        DrawingLibrary lib = one("NewCanvas1", sig(drawn(4, 1)));
        assertEquals(Kind.NEW, lib.classify(sig(drawn(11, 1)), "NewCanvas1", "b.mp4").kind);
    }

    @Test
    public void saisDefaultNameIsNoEvidence() {
        DrawingLibrary lib = one("NewCanvas1", otherDrawing);
        assertEquals(Kind.NEW, lib.classify(tuesdayStart, "NewCanvas1", "b.mp4").kind);
        assertTrue(DrawingLibrary.isDefaultTitle("NewCanvas12"));
        assertFalse(DrawingLibrary.isDefaultTitle("NewCanvas Portrait"));
    }

    @Test
    public void twoDrawingsSharingAChosenNameAskRatherThanMerge() {
        DrawingLibrary lib = one("Sketch", otherDrawing);
        assertEquals(Kind.ASK, lib.classify(tuesdayStart, "Sketch", "b.mp4").kind);
    }

    @Test
    public void anUnrelatedDrawingIsNewSilently() {
        assertEquals(Kind.NEW, withSketch("Sketch").classify(otherDrawing, "Other", "s.mp4").kind);
    }

    /** The two measures disagree about which candidate is nearer: the one
     *  whose ink is in the same places wins. */
    @Test
    public void theDrawingThatCorrelatesWinsNotTheNearOne() {
        int n = Signature.SIDE * Signature.SIDE;
        byte[] pattern = new byte[n], dimmed = new byte[n], partial = new byte[n];
        for (int i = 0; i < n; i++) pattern[i] = (byte) ((i / 16 + i % 16) % 3 == 0 ? 40 : 255);
        for (int i = 0; i < n; i++) dimmed[i] = (byte) (255 - (255 - (pattern[i] & 0xff)) / 2);
        for (int i = 0; i < n; i++) partial[i] = (pattern[i] & 0xff) != 255 && i % 5 < 2 ? (byte) 255 : pattern[i];
        Signature p = new Signature(pattern, 200, 150), dim = new Signature(dimmed, 200, 150),
                  part = new Signature(partial, 200, 150);
        DrawingLibrary lib = new DrawingLibrary();
        String right = lib.addDrawing("Portrait", piece("p.mp4", 0, p, dim)).id;
        String decoy = lib.addDrawing("Doodle", piece("q.mp4", 1, part, part)).id;
        assertTrue(Signature.distance(p, part) < Signature.distance(p, dim));
        assertTrue(Signature.similarity(p, dim) > Signature.similarity(p, part));
        Match m = lib.classify(p, "Portrait", "s.mp4");
        assertNotEquals(Kind.NEW, m.kind);
        assertEquals(right, m.drawingId);
        assertNotEquals(decoy, m.drawingId);
    }

    /* ---- asking once ---- */

    @Test
    public void realProgressAsksAndANoIsNeverAskedAgain() {
        DrawingLibrary lib = withSketch("Sketch");
        lib.addDrawing("Something else", piece("x.mp4", 1, sig(blank()), otherDrawing));
        Match m = lib.classify(tuesdayEnd, "Sketch", "tonight.mp4");
        assertEquals(Kind.ASK, m.kind);
        lib.decline("tonight.mp4", m.drawingId);
        assertEquals(Kind.NEW, lib.classify(tuesdayEnd, "Sketch", "tonight.mp4").kind);
        assertEquals("only that pair", Kind.ASK, lib.classify(tuesdayEnd, "Sketch", "another.mp4").kind);
    }

    /* ---- filing ---- */

    @Test
    public void filingASameSessionJoinsTheDrawing() {
        DrawingLibrary lib = withSketch("Sketch");
        Drawing d = lib.file(piece("tue.mp4", 1, tuesdayStart, tuesdayEnd), "Sketch");
        assertEquals(1, lib.drawings.size());
        assertEquals(2, d.pieces.size());
        assertEquals(tuesdayEnd, d.closing());
        assertTrue(lib.pending.isEmpty());
    }

    @Test
    public void filingAPlausibleSessionMakesItsOwnDrawingAndAQuestion() {
        DrawingLibrary lib = withSketch("Sketch");
        String sketch = lib.drawings.get(0).id;
        Drawing d = lib.file(piece("tue.mp4", 1, tuesdayEnd, tuesdayEnd), "Sketch");
        assertEquals(2, lib.drawings.size());
        assertNotEquals(sketch, d.id);
        Pending q = lib.questionAbout(Collections.singletonList("tue.mp4"));
        assertNotNull(q);
        assertEquals(sketch, q.candidateId);
        assertNull("an old question is not asked after a new video",
                   lib.questionAbout(Collections.singletonList("other.mp4")));

        Drawing joined = lib.accept(q);
        assertEquals(sketch, joined.id);
        assertEquals(1, lib.drawings.size());
        assertEquals(2, joined.pieces.size());
        assertTrue(lib.pending.isEmpty());
    }

    @Test
    public void refusingRemembersTheNo() {
        DrawingLibrary lib = withSketch("Sketch");
        lib.file(piece("tue.mp4", 1, tuesdayEnd, tuesdayEnd), "Sketch");
        lib.refuse(lib.pending.get(0));
        assertEquals(2, lib.drawings.size());
        assertTrue(lib.hasDeclined("tue.mp4", lib.drawings.get(0).id));
    }

    @Test
    public void aSessionFiledLateGoesInTheMiddle() {
        DrawingLibrary lib = withSketch("Sketch");
        Drawing d = lib.drawings.get(0);
        lib.attach(piece("day5.mp4", 5, tuesdayStart, tuesdayEnd), d);
        lib.attach(piece("day2.mp4", 2, tuesdayStart, sig(drawn(1, 10))), d);
        assertEquals(Arrays.asList("2026-07-27 2015.mp4", "day2.mp4", "day5.mp4"),
                     Arrays.asList(d.ordered().get(0).file, d.ordered().get(1).file, d.ordered().get(2).file));
        assertEquals(tuesdayEnd, d.closing());
        lib.attach(piece("day5.mp4", 5, tuesdayStart, tuesdayEnd), d);
        assertEquals("attaching twice adds nothing", 3, d.pieces.size());
    }

    /* ---- naming ---- */

    @Test
    public void namesSayWhichDrawingAndNeverCollide() {
        long t = 1_750_000_000_000L;
        Set<String> taken = new HashSet<>();
        String a = DrawingLibrary.folderName("NewCanvas1", t, taken);
        assertTrue(a, a.startsWith("NewCanvas1 2025-"));
        taken.add(a);
        assertEquals(a + " 2", DrawingLibrary.folderName("NewCanvas1", t, taken));
        assertEquals("Drawing", DrawingLibrary.folderStem(".."));
        assertEquals("Drawing", DrawingLibrary.folderStem(""));
        assertEquals("ab", DrawingLibrary.folderStem("a/b"));
        assertEquals(60, DrawingLibrary.folderStem(new String(new char[80]).replace('\0', 'x')).length());
        Set<String> pieces = new HashSet<>();
        String p1 = DrawingLibrary.pieceName(t, pieces);
        pieces.add(p1);
        String p2 = DrawingLibrary.pieceName(t, pieces);
        assertNotEquals(p1, p2);
        assertTrue(p2.endsWith(" (2).mp4"));
    }

    /* ---- on disk ---- */

    @Test
    public void theLibrarySurvivesSavingAndLoading() throws Exception {
        DrawingLibrary lib = withSketch("Sketch\twith tab");
        lib.file(piece("tue.mp4", 1, tuesdayEnd, tuesdayEnd), "Other");
        lib.addDrawing("Third", piece("x.mp4", 3, otherDrawing, otherDrawing));
        lib.decline("x.mp4", lib.drawings.get(0).id);
        File f = new File(tmp.getRoot(), "drawings.txt");
        lib.save(f);
        DrawingLibrary back = DrawingLibrary.load(f);
        assertEquals(lib.drawings.size(), back.drawings.size());
        for (int i = 0; i < lib.drawings.size(); i++) {
            Drawing a = lib.drawings.get(i), b = back.drawings.get(i);
            assertEquals(a.id, b.id);
            assertEquals(a.folder, b.folder);
            assertEquals(a.pieces.size(), b.pieces.size());
            assertEquals(0, Signature.distance(a.closing(), b.closing()), 0);
            assertEquals(a.pieces.get(0).startedAt, b.pieces.get(0).startedAt);
        }
        assertEquals("Sketch with tab", back.drawings.get(0).title);
        assertEquals(lib.pending.size(), back.pending.size());
        assertEquals(lib.declined, back.declined);
        /* and decides the same */
        assertEquals(lib.classify(tuesdayStart, "x", "n.mp4").toString(), back.classify(tuesdayStart, "x", "n.mp4").toString());
        assertTrue(DrawingLibrary.load(new File(tmp.getRoot(), "none.txt")).drawings.isEmpty());
    }

    /* ---- one video per drawing ---- */

    @Test
    public void piecesJoinWithinOneSizeAndASecondSizeGetsItsOwnVideo() {
        DrawingLibrary lib = withSketch("Sketch");
        Drawing d = lib.drawings.get(0);
        lib.attach(piece("b.mp4", 1, tuesdayStart, tuesdayEnd), d);
        Signature big = Signature.of(drawn(400, 300, 1, 14), 400, 300, 1600);
        lib.attach(new Piece("c.mp4", 1_750_000_000_000L + 2 * 86_400_000L, 10, 400, 300, big, big), d);
        java.util.List<java.util.List<Piece>> runs = DrawingLibrary.sizeRuns(d);
        assertEquals(2, runs.size());
        assertEquals(2, runs.get(0).size());
        assertEquals("c.mp4", runs.get(1).get(0).file);
        assertEquals(d.folder + ".mp4", DrawingLibrary.joinedName(d, 0));
        assertEquals(d.folder + " (2).mp4", DrawingLibrary.joinedName(d, 1));
    }

    /** Joining two sessions that happen to share a file name must not drop
     *  one: that would be a whole evening gone. */
    @Test
    public void joiningKeepsBothSessionsWhenTheirNamesClash() {
        DrawingLibrary lib = withSketch("Sketch");
        String sketch = lib.drawings.get(0).id;
        Piece clash = new Piece("2026-07-27 2015.mp4", 1_750_000_000_000L + 86_400_000L, 30, 200, 150, tuesdayEnd, tuesdayEnd);
        lib.file(clash, "Sketch");
        Pending q = lib.pending.get(0);
        Drawing joined = lib.accept(q);
        assertEquals(sketch, joined.id);
        assertEquals(2, joined.pieces.size());
        assertNotEquals(joined.pieces.get(0).file, joined.pieces.get(1).file);
    }

    @Test
    public void deletingADrawingTakesItsQuestionsWithIt() {
        DrawingLibrary lib = withSketch("Sketch");
        Drawing other = lib.file(piece("tue.mp4", 1, tuesdayEnd, tuesdayEnd), "Sketch");
        assertNotNull(lib.pendingFor(other));
        lib.remove(lib.drawings.get(0));
        assertTrue(lib.pending.isEmpty());
        assertEquals(1, lib.newestFirst().size());
    }
}
