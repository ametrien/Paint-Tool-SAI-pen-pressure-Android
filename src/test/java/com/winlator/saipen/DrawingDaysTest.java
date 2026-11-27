package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.junit.Test;

public class DrawingDaysTest {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static CanvasTime.Session sitting(long start, long end, int strokes, double seconds) {
        CanvasTime.Session s = new CanvasTime.Session();
        s.start = start; s.end = end; s.strokes = strokes; s.seconds = seconds;
        return s;
    }

    @Test
    public void sittingsAddUpByDay() {
        CanvasTime.Canvas c = new CanvasTime.Canvas();
        c.sessions.add(sitting(at(25, 10, 0), at(25, 11, 0), 100, 3000));
        c.sessions.add(sitting(at(25, 20, 0), at(25, 20, 30), 40, 1200));
        c.sessions.add(sitting(at(26, 9, 0), at(26, 9, 10), 10, 500));
        c.strokes = 150; c.seconds = 4700; c.last = at(26, 9, 10);
        Map<String, DrawingStats.Day> days = DrawingDays.days(c, ZONE);
        assertEquals(2, days.size());
        DrawingStats.Day d = days.get("2026-11-25");
        assertEquals(140, d.strokes);
        assertEquals(4200, d.activeSeconds, 0.01);
        assertEquals(2, d.sessions);
        assertEquals(3000, d.longestSession, 0.01);
        assertEquals(10 * 3600, d.firstAt, 0.01);
        assertEquals(20.5 * 3600, d.lastAt, 0.01);
        assertEquals(3000, d.hourly[10], 0.01);
        assertEquals(1200, d.hourly[20], 0.01);

        DrawingDays.Summary s = DrawingDays.summary(c, ZONE);
        assertEquals(2, s.days);
        assertEquals(150, s.strokes);
        assertEquals(4700, s.seconds, 0.01);
        assertEquals(0, s.earlierSeconds, 0.01);
        assertEquals(at(26, 9, 10), s.last);
    }

    @Test
    public void timeBeforeSittingsWereKeptIsEarlier() {
        CanvasTime.Canvas c = new CanvasTime.Canvas();
        c.sessions.add(sitting(at(26, 9, 0), at(26, 9, 10), 10, 500));
        c.strokes = 300; c.seconds = 4100; c.last = at(26, 9, 10);
        DrawingDays.Summary s = DrawingDays.summary(c, ZONE);
        assertEquals(1, s.days);
        assertEquals(3600, s.earlierSeconds, 0.01);
        assertEquals(4100, s.seconds, 0.01);
    }

    @Test
    public void timeIsSpreadOverTheHoursOfASitting() {
        double[] h = new double[24];
        DrawingDays.spread(h, 10.5 * 3600, 12.5 * 3600, 400);
        assertEquals(100, h[10], 0.01);
        assertEquals(200, h[11], 0.01);
        assertEquals(100, h[12], 0.01);
    }

    @Test
    public void anOldSittingPastMidnightStaysOnItsDay() {
        CanvasTime.Canvas c = new CanvasTime.Canvas();
        c.sessions.add(sitting(at(25, 23, 30), at(26, 0, 30), 20, 3600));
        Map<String, DrawingStats.Day> days = DrawingDays.days(c, ZONE);
        assertEquals(1, days.size());
        assertTrue(days.get("2026-11-25").lastAt < 24 * 3600);
        assertEquals(3600, days.get("2026-11-25").hourly[23], 0.01);
    }

    @Test
    public void aSittingIsSplitAtMidnight() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(1, "a.sai2", at(25, 23, 59));
        t.penDown(30); t.penUp(31, "a.sai2", at(26, 0, 1));
        CanvasTime.Canvas c = t.get("a.sai2");
        assertEquals(2, c.sessions.size());
        Map<String, DrawingStats.Day> days = DrawingDays.days(c, ZONE);
        assertEquals(1, days.get("2026-11-25").strokes);
        assertEquals(1, days.get("2026-11-26").strokes);
        assertFalse(CanvasTime.sameDay(at(25, 23, 59), at(26, 0, 1)));
    }

    @Test
    public void allCopiesEveryDrawing() {
        CanvasTime t = new CanvasTime();
        t.penDown(0); t.penUp(1, "a.sai2", at(25, 10, 0));
        t.penDown(5); t.penUp(6, "comics/b.sai2", at(25, 10, 1));
        Map<String, CanvasTime.Canvas> all = t.all();
        assertEquals(2, all.size());
        all.get("a.sai2").strokes = 99;
        assertEquals(1, t.get("a.sai2").strokes);
        assertEquals("comics/b.sai2", DrawingDays.key("comics", "b.sai2"));
        assertEquals("b.sai2", DrawingDays.key("", "b.sai2"));
    }
}
