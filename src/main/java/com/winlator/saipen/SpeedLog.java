package com.winlator.saipen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How fast SAI is on this device: the speed log, one per SAI session, to
 * compare the sharpness settings (720, 900, 1200 lines) and to see what SAI
 * does in the seconds after the start-up cover lifts.
 *
 * What the app can see from outside SAI:
 * - when SAI's window appears and when the cover lifts;
 * - every time a window's content changes (SAI drew something);
 * - pen down and pen up, and for every pen sample the tablet's own time of
 *   it (MotionEvent.getEventTime, the moment the screen felt the pen);
 * - every frame the app draws for Android (Winlator's GLRenderer);
 * - how much processor time SAI and the whole app use, once a second.
 *
 * Per stroke it writes:
 * - ink: the app hearing of the pen to SAI's first drawing after it (the
 *   first logs had only this);
 * - to screen: the tablet feeling the pen to the end of the first frame
 *   that holds SAI's first drawing, in three parts: input (tablet to app),
 *   SAI (= ink), frame (SAI's drawing to that frame). The owner's point: the
 *   delay felt starts when the pen touches, and ends on the glass. Android
 *   puts the frame on the glass a refresh or two later: not seen from here;
 * - draws/s: how often SAI drew while the pen was down;
 * - frames/s: how often the app drew a frame while the pen was down;
 * - tail: pen up to SAI's last drawing for that stroke (catching up).
 *
 * THE TRAP: SAI's status bar and other windows draw too, so an update is
 * not always the stroke. Ink counts only updates after the pen went down,
 * and the tail only those within TAIL_MS of the pen going up.
 *
 * Times are milliseconds of any monotonic clock. No Android: the JVM tests
 * drive it.
 */
public final class SpeedLog {
    public interface Out {
        void line(String text);
    }

    /** SAI drawing later than this after the pen went up is not that stroke. */
    public static final long TAIL_MS = 500;
    /** SAI's drawings no frame has shown yet, at most. Past it the second
     *  oldest is dropped: a frame's lag needs only the oldest, and the
     *  newest decide what the next frame leaves undrawn. */
    public static final int MAX_UNDRAWN = 1024;
    /** Samples kept per list (input, frames): about an hour of drawing. */
    public static final int MAX_SAMPLES = 200_000;

    private final Out out;
    private final long start;
    private long saiWindow = -1, coverLifted = -1, settled = -1;
    /** The same rule as the start-up cover's (SaiCalm). */
    private final SaiCalm calm = new SaiCalm();

    private long down = -1, up = -1, ink = -1, lastUpdate = -1;
    /** this stroke: the tablet's touch time, SAI's first drawing, the end
     *  of the first frame holding it */
    private long touch = -1, firstDrawing = -1, onScreen = -1;
    private int strokeUpdates = 0, strokeFrames = 0;
    private final ArrayDeque<Long> undrawn = new ArrayDeque<>();
    private int strokes = 0;
    private int updatesThisSecond = 0;
    private long firstStrokeAt = -1;

    private final List<Long> inks = new ArrayList<>();
    private final List<Long> tails = new ArrayList<>();
    private final List<Long> rates = new ArrayList<>();
    private final List<Long> toScreen = new ArrayList<>();
    private final List<Long> inputs = new ArrayList<>();
    private final List<Long> frameLags = new ArrayList<>();
    private final List<Long> frameTimes = new ArrayList<>();
    private final List<Long> frameRates = new ArrayList<>();

    public SpeedLog(long now, Out out) {
        this.start = now;
        this.out = out;
    }

    private String at(long now) {
        return String.format(Locale.ROOT, "%6.1fs", (now - start) / 1000.0);
    }

    public synchronized void onSaiWindow(long now) {
        if (saiWindow >= 0) return;
        saiWindow = now;
        out.line(at(now) + "  SAI's window is up");
    }

    public synchronized void onCoverLifted(long now) {
        if (coverLifted >= 0) return;
        coverLifted = now;
        out.line(at(now) + "  start-up cover lifted");
    }

    /** A line of its own, at this time (a keyboard's first key: KeyboardKeys). */
    public synchronized void note(long now, String text) {
        out.line(at(now) + "  " + text);
    }

    public synchronized void onContentUpdate(long now) {
        updatesThisSecond++;
        lastUpdate = now;
        if (undrawn.size() >= MAX_UNDRAWN) {
            Long oldest = undrawn.pollFirst();
            undrawn.pollFirst();
            undrawn.addFirst(oldest);
        }
        undrawn.addLast(now);
        if (down >= 0 && up < 0) {
            strokeUpdates++;
            if (ink < 0) {
                ink = now - down;
                firstDrawing = now;
            }
        }
    }

    /** The app drew a frame for Android from start to end. It shows what
     *  SAI had drawn by start. */
    public synchronized void onFrame(long start, long end) {
        long oldest = -1;
        while (!undrawn.isEmpty() && undrawn.peekFirst() <= start) {
            long u = undrawn.pollFirst();
            if (oldest < 0) oldest = u;
        }
        if (down >= 0 && up < 0) {
            strokeFrames++;
            add(frameTimes, end - start);
            if (oldest >= 0) add(frameLags, end - oldest);
        }
        if (firstDrawing >= 0 && onScreen < 0 && start >= firstDrawing) onScreen = end;
    }

    public synchronized void onPenDown(long now) {
        onPenDown(now, now);
    }

    /** touchedAt: the tablet's time of the touch, on the same clock as now. */
    public synchronized void onPenDown(long now, long touchedAt) {
        endStroke();
        down = now;
        touch = touchedAt > 0 && touchedAt <= now ? touchedAt : now;
        add(inputs, now - touch);
        up = -1;
        ink = -1;
        firstDrawing = -1;
        onScreen = -1;
        strokeUpdates = 0;
        strokeFrames = 0;
        if (firstStrokeAt < 0) firstStrokeAt = now;
    }

    /** Another pen sample while it may touch; counted only while it does. */
    public synchronized void onPenMove(long now, long touchedAt) {
        if (down >= 0 && up < 0 && touchedAt > 0 && touchedAt <= now) add(inputs, now - touchedAt);
    }

    /** For the tests: SAI's drawings waiting for a frame. */
    synchronized int undrawnCount() {
        return undrawn.size();
    }

    private static void add(List<Long> list, long v) {
        if (list.size() < MAX_SAMPLES) list.add(v);
    }

    public synchronized void onPenUp(long now) {
        if (down >= 0 && up < 0) up = now;
    }

    /**
     * Once a second. cpuSai and cpuAll are percent of one core (200 = two
     * cores busy), -1 when unknown.
     */
    public synchronized void tick(long now, int cpuSai, int cpuAll) {
        if (up >= 0 && now - up > TAIL_MS) endStroke();
        /* a second wholly after SAI's window appeared */
        if (saiWindow >= 0 && now - saiWindow >= 1000 && cpuSai >= 0 && calm.onReading(cpuSai, now)) {
            settled = now;
            out.line(at(now) + "  SAI settled (" + cpuSai + "%, it loaded at " + calm.loadingLevel() + "%)");
        }
        out.line(at(now) + "  draws " + updatesThisSecond + "  cpu SAI " + cpu(cpuSai) + " app " + cpu(cpuAll));
        updatesThisSecond = 0;
    }

    private static String cpu(int v) {
        return v < 0 ? "?" : v + "%";
    }

    private void endStroke() {
        if (down < 0 || up < 0) return;
        strokes++;
        long dur = Math.max(1, up - down);
        long tail = lastUpdate > up && lastUpdate - up <= TAIL_MS ? lastUpdate - up : 0;
        long rate = strokeUpdates * 1000L / dur;
        long frameRate = strokeFrames * 1000L / dur;
        String screen = "?";
        if (ink >= 0 && onScreen >= 0) {
            screen = (onScreen - touch) + " ms (input " + (down - touch) + ", SAI " + ink
                     + ", frame " + (onScreen - firstDrawing) + ")";
            toScreen.add(onScreen - touch);
        }
        out.line(at(down) + "  stroke " + strokes + "  " + dur + " ms  ink " + (ink < 0 ? "none" : ink + " ms")
                 + "  to screen " + screen + "  draws/s " + rate + "  frames/s " + frameRate
                 + "  tail " + tail + " ms");
        if (ink >= 0) inks.add(ink);
        tails.add(tail);
        if (dur >= 200) {                     /* a dab says little about a rate */
            rates.add(rate);
            frameRates.add(frameRate);
        }
        down = -1;
        up = -1;
    }

    /** The summary at the end of the session. */
    public synchronized void finish(long now) {
        endStroke();
        out.line("");
        out.line("summary");
        out.line("  SAI's window:   " + since(saiWindow));
        out.line("  cover lifted:   " + since(coverLifted));
        out.line("  SAI settled:    " + since(settled));
        out.line("  first stroke:   " + since(firstStrokeAt));
        out.line("  strokes:        " + strokes);
        out.line("  ink, median:    " + ms(median(inks)) + "   slowest 10%: " + ms(percentile(inks, 90)));
        out.line("  touch to screen, median: " + ms(median(toScreen)) + "   slowest 10%: " + ms(percentile(toScreen, 90)));
        out.line("  input, median:  " + ms(median(inputs)) + "   slowest 10%: " + ms(percentile(inputs, 90)));
        out.line("  SAI to frame, median: " + ms(median(frameLags)) + "   slowest 10%: " + ms(percentile(frameLags, 90)));
        out.line("  frame drawing, median: " + ms(median(frameTimes)) + "   slowest 10%: " + ms(percentile(frameTimes, 90)));
        out.line("  draws/s, median: " + num(median(rates)));
        out.line("  frames/s, median: " + num(median(frameRates)));
        out.line("  tail, median:   " + ms(median(tails)) + "   slowest 10%: " + ms(percentile(tails, 90)));
        out.line("  session:        " + since(now));
    }

    private String since(long t) {
        return t < 0 ? "never" : String.format(Locale.ROOT, "%.1f s after the start", (t - start) / 1000.0);
    }

    private static String ms(long v) {
        return v < 0 ? "?" : v + " ms";
    }

    private static String num(long v) {
        return v < 0 ? "?" : String.valueOf(v);
    }

    public static long median(List<Long> values) {
        return percentile(values, 50);
    }

    /** The value at p percent (nearest rank), -1 for no values. */
    public static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) return -1;
        List<Long> s = new ArrayList<>(values);
        Collections.sort(s);
        int rank = (int) Math.ceil(p / 100.0 * s.size());
        return s.get(Math.max(0, Math.min(s.size() - 1, rank - 1)));
    }

    /**
     * Processor time in clock ticks (utime + stime) from a /proc/<pid>/stat
     * line, -1 if it cannot be read. The name in brackets may hold spaces
     * and brackets ("(sai2.exe)", "(Wine Desktop)"), so fields are counted
     * from the last ')'.
     */
    public static long cpuTicks(String stat) {
        if (stat == null) return -1;
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 > stat.length()) return -1;
        String[] f = stat.substring(close + 2).trim().split("\\s+");
        /* f[0] is field 3 (state); utime is field 14, stime 15 */
        if (f.length < 13) return -1;
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }
}
