package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

public class DrawingStatsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private static DrawingStats fresh() {
        return new DrawingStats(new TreeMap<>());
    }

    private static void stroke(DrawingStats s, double down, double up, LocalDate day) {
        s.penDown(down);
        assertTrue(s.penUp(up, day));
    }

    @Test
    public void aStrokeCountsItsPenTime() {
        DrawingStats s = fresh();
        stroke(s, 10, 12.5, DAY);
        DrawingStats.Day d = s.days().get("2026-11-20");
        assertEquals(1, d.strokes);
        assertEquals(2.5, d.penSeconds, 1e-9);
        assertEquals(2.5, d.activeSeconds, 1e-9);
    }

    @Test
    public void aShortPauseBetweenStrokesIsDrawingALongOneIsABreak() {
        DrawingStats s = fresh();
        stroke(s, 0, 1, DAY);
        stroke(s, 31, 32, DAY);      /* 30 s pause: drawing */
        stroke(s, 200, 201, DAY);    /* 168 s pause: a break */
        DrawingStats.Day d = s.days().get("2026-11-20");
        assertEquals(3, d.strokes);
        assertEquals(3, d.penSeconds, 1e-9);
        assertEquals(33, d.activeSeconds, 1e-9);
    }

    @Test
    public void aPauseOfExactlyAMinuteStillCounts() {
        DrawingStats s = fresh();
        stroke(s, 0, 1, DAY);
        stroke(s, 61, 62, DAY);
        assertEquals(62, s.days().get("2026-11-20").activeSeconds, 1e-9);
    }

    @Test
    public void aLostPenUpBooksAtMostTenMinutes() {
        DrawingStats s = fresh();
        stroke(s, 0, 5 * 3600, DAY);
        assertEquals(600, s.days().get("2026-11-20").penSeconds, 1e-9);
    }

    @Test
    public void aClockGoingBackwardsBooksNothing() {
        DrawingStats s = fresh();
        stroke(s, 100, 90, DAY);
        DrawingStats.Day d = s.days().get("2026-11-20");
        assertEquals(1, d.strokes);
        assertEquals(0, d.penSeconds, 1e-9);
    }

    @Test
    public void penUpWithoutPenDownIsNoStroke() {
        DrawingStats s = fresh();
        assertFalse(s.penUp(5, DAY));
        assertTrue(s.days().isEmpty());
    }

    @Test
    public void aSecondPenDownKeepsTheStrokesStart() {
        DrawingStats s = fresh();
        s.penDown(0);
        s.penDown(3);
        s.penUp(4, DAY);
        assertEquals(4, s.days().get("2026-11-20").penSeconds, 1e-9);
    }

    @Test
    public void afterCancelTheOpenStrokeAndTheTimeAwayDoNotCount() {
        DrawingStats s = fresh();
        stroke(s, 0, 1, DAY);
        s.penDown(2);
        s.cancel();                   /* SAI closed mid-stroke */
        assertFalse(s.penUp(3, DAY));
        stroke(s, 20, 21, DAY);       /* 19 s after the last counted stroke, but SAI was closed */
        DrawingStats.Day d = s.days().get("2026-11-20");
        assertEquals(2, d.strokes);
        assertEquals(2, d.activeSeconds, 1e-9);
    }

    @Test
    public void aStrokeIsFiledUnderTheDayItEnded() {
        DrawingStats s = fresh();
        stroke(s, 0, 1, DAY);
        stroke(s, 10, 11, DAY.plusDays(1));
        assertEquals(1, s.days().get("2026-11-20").strokes);
        assertEquals(1, s.days().get("2026-11-21").strokes);
    }

    @Test
    public void theFileRoundTrips() {
        DrawingStats s = fresh();
        stroke(s, 0, 2, DAY);
        stroke(s, 100, 103, DAY.minusDays(3));   /* after a break: pen time only */
        String text = DrawingStats.serialize(s.days());
        assertEquals(DrawingStats.HEADER + "\n2026-11-17 1 3 3 1 3 -1 -1\n2026-11-20 1 2 2 1 2 -1 -1\n", text);
        DrawingStats.Parsed p = DrawingStats.parse(text);
        assertEquals(0, p.skipped);
        assertEquals(1, p.days.get("2026-11-17").strokes);
        assertEquals(3, p.days.get("2026-11-17").penSeconds, 1e-9);
    }

    @Test
    public void theMacFileReadsToo() {
        String hours = " 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 600 0 0";
        DrawingStats.Parsed p = DrawingStats.parse(
            "# day strokes pen-seconds active-seconds sessions ...\n"
            + "2026-11-18 340 900 4300 2 2400 75600 79200" + hours + "\n"
            + "2026-11-19 12 30 60 1 60 -1 -1\n");
        assertEquals(0, p.skipped);
        assertEquals(340, p.days.get("2026-11-18").strokes);
        assertEquals(4300, p.days.get("2026-11-18").activeSeconds, 1e-9);
        assertEquals(12, p.days.get("2026-11-19").strokes);
        assertEquals(2, p.days.get("2026-11-18").sessions);
        assertEquals(2400, p.days.get("2026-11-18").longestSession, 1e-9);
        assertEquals(75600, p.days.get("2026-11-18").firstAt, 1e-9);
        assertEquals(600, p.days.get("2026-11-18").hourly[21], 1e-9);
    }

    @Test
    public void badLinesAreSkippedAndCounted() {
        DrawingStats.Parsed p = DrawingStats.parse(
            "2026-11-18 3 4 5\nnot a day\n2026-13-40 1 1 1\n2026-11-19 -1 0 0\n2026-11-20 2 x 3\n");
        assertEquals(4, p.skipped);
        assertEquals(1, p.days.size());
    }

    @Test
    public void activeTimeIsNeverLessThanPenTime() {
        DrawingStats.Parsed p = DrawingStats.parse("2026-11-18 3 50 10\n");
        assertEquals(50, p.days.get("2026-11-18").activeSeconds, 1e-9);
    }

    @Test
    public void sessionsAndHoursMatchTheMacStatsFormat() {
        DrawingStats s = fresh();
        s.penDown(0);
        s.penUp(10, DAY, 8 * 3600 + 10);
        s.penDown(40);
        s.penUp(50, DAY, 8 * 3600 + 50);
        s.penDown(700);
        s.penUp(710, DAY, 9 * 3600 + 10);
        DrawingStats.Day d = s.days().get("2026-11-20");
        assertEquals(2, d.sessions);
        assertEquals(50, d.longestSession, 1e-9);
        assertEquals(8 * 3600, d.firstAt, 1e-9);
        assertEquals(9 * 3600 + 10, d.lastAt, 1e-9);
        assertEquals(50, d.hourly[8], 1e-9);
        assertEquals(10, d.hourly[9], 1e-9);
    }

    @Test
    public void saveWritesOnlyAfterSomethingNew() throws Exception {
        File f = new File(tmp.getRoot(), DrawingStats.FILE);
        DrawingStats s = fresh();
        assertTrue(s.saveIfChanged(f));
        assertFalse("nothing counted, nothing written", f.exists());
        stroke(s, 0, 2, DAY);
        assertTrue(s.saveIfChanged(f));
        assertEquals(1, DrawingStats.load(f).days().get("2026-11-20").strokes);
        f.delete();
        assertTrue(s.saveIfChanged(f));
        assertFalse("saved already, not again", f.exists());
    }

    @Test
    public void aFileWithBadLinesIsCopiedAsideBeforeItIsWrittenOver() throws Exception {
        File f = new File(tmp.getRoot(), DrawingStats.FILE);
        Files.write(f.toPath(), "2026-11-18 3 4 5\ngarbage\n".getBytes(StandardCharsets.UTF_8));
        DrawingStats s = DrawingStats.load(f);
        assertEquals(3, s.days().get("2026-11-18").strokes);
        File[] aside = tmp.getRoot().listFiles((d, n) -> n.startsWith(DrawingStats.FILE + ".damaged-"));
        assertEquals(1, aside.length);
        assertTrue(new String(Files.readAllBytes(aside[0].toPath()), StandardCharsets.UTF_8).contains("garbage"));
    }

    @Test
    public void aGoodFileIsNotCopiedAside() throws Exception {
        File f = new File(tmp.getRoot(), DrawingStats.FILE);
        Files.write(f.toPath(), (DrawingStats.HEADER + "\n2026-11-18 3 4 5\n").getBytes(StandardCharsets.UTF_8));
        DrawingStats.load(f);
        assertEquals(1, tmp.getRoot().listFiles().length);
    }

    @Test
    public void noFileIsNoStatsYet() {
        assertTrue(DrawingStats.load(new File(tmp.getRoot(), "none.txt")).days().isEmpty());
    }

    private static Map<String, DrawingStats.Day> drawnOn(LocalDate... days) {
        TreeMap<String, DrawingStats.Day> m = new TreeMap<>();
        for (LocalDate d : days) {
            DrawingStats.Day x = new DrawingStats.Day();
            x.strokes = 1;
            x.activeSeconds = 60;
            m.put(d.toString(), x);
        }
        return m;
    }

    @Test
    public void streakCountsDaysInARowEndingToday() {
        assertEquals(3, DrawingStats.streak(drawnOn(DAY, DAY.minusDays(1), DAY.minusDays(2), DAY.minusDays(4)), DAY));
    }

    @Test
    public void streakStillCountsWhileTodayIsEmpty() {
        assertEquals(2, DrawingStats.streak(drawnOn(DAY.minusDays(1), DAY.minusDays(2)), DAY));
    }

    @Test
    public void streakIsZeroAfterAMissedDay() {
        assertEquals(0, DrawingStats.streak(drawnOn(DAY.minusDays(2)), DAY));
    }

    @Test
    public void lastSevenDaysLeavesOutTheEighth() {
        Map<String, DrawingStats.Day> m = drawnOn(DAY, DAY.minusDays(6), DAY.minusDays(7));
        DrawingStats.Day week = DrawingStats.lastDays(m, DAY, 7);
        assertEquals(2, week.strokes);
        assertEquals(120, week.activeSeconds, 1e-9);
        assertEquals(3, DrawingStats.total(m).strokes);
    }

    /* ---- the very first start: no stats file yet ---- */

    @Test
    public void aFirstStartHasNothingAndDoesNotFail() {
        File none = new File(tmp.getRoot(), "never-written.txt");
        Map<String, DrawingStats.Day> days = DrawingStats.load(none).days();
        assertTrue(days.isEmpty());
        assertEquals(0, DrawingStats.lastDays(days, DAY, 1).strokes);
        assertEquals(0, DrawingStats.lastDays(days, DAY, 7).strokes);
        assertEquals(0, DrawingStats.total(days).strokes);
        assertEquals(0, DrawingStats.streak(days, DAY));
        assertTrue("the CSV export works with nothing in it", DrawingStats.csv(days) != null);
        assertEquals("nothing counted yet: counting starts today", 1,
            DrawingStats.countedDays(days, DAY.minusDays(29), DAY, DAY));
    }

    @Test
    public void daysBeforeCountingBeganAreNotDaysOff() {
        Map<String, DrawingStats.Day> days = new TreeMap<>();
        DrawingStats.Day d = new DrawingStats.Day();
        d.strokes = 5;
        days.put(DAY.minusDays(1).toString(), d);           // first counted: 19 Nov
        days.put(DAY.toString(), d);
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        assertEquals("the year's calendar: 2 of 2, not 2 of 325", 2, DrawingStats.countedDays(days, jan1, DAY, DAY));
        assertEquals("a week inside counting keeps all 7", 7,
            DrawingStats.countedDays(days, DAY.plusDays(10).minusDays(6), DAY.plusDays(10), DAY.plusDays(10)));
        assertEquals("a year before counting began has none", 0,
            DrawingStats.countedDays(days, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), DAY));
    }
}
