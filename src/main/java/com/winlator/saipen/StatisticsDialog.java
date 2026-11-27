package com.winlator.saipen;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Android's compact counterpart to the macOS Statistics tab.  It deliberately
 * reads DrawingStats rather than an Android-only database: a copied stats file
 * has the same meaning on both platforms.
 *
 * Colours are the launcher's AppThemeDark (Winlator styles.xml), so the window
 * reads as part of the app and not as a white system dialog on top of it.
 */
final class StatisticsDialog {
    interface Exporter { void export(String csv); }

    /* AppThemeDark: colorPrimarySurface, colorSecondarySurface, colorPrimaryText,
       colorSecondaryText, colorAccent */
    static final int BG = 0xFF1B1E21, SURFACE = 0xFF2D3238, TEXT = 0xFFFAFAFA,
                     TEXT2 = 0xFF9E9E9E, ACCENT = 0xFF0288D1;
    static final int TEXT3 = 0xFF6E7378, LINE = 0x22FFFFFF;

    private enum Page { DAYS, HOURS, WEEKDAYS, CALENDAR }
    private enum Period { WEEK(7, "Week"), MONTH(30, "Month"), YEAR(365, "Year"), ALL(0, "All time");
        final int days; final String label;
        Period(int days, String label) { this.days = days; this.label = label; }
    }

    private final Activity activity;
    private final Map<String, DrawingStats.Day> days;
    private final Exporter exporter;
    /** One drawing's days (DrawingDays) instead of all drawing: its name, else null. */
    private final String drawing;
    /** That drawing's time counted before sittings were kept: in no day. */
    private final double earlierSeconds;
    private final TextView[] cardValue = new TextView[4], cardDetail = new TextView[4];
    private final StatsChartView chart;
    private final TextView footer;
    private final LinearLayout periods, pages, yearBox;
    private final TextView yearLabel;
    private Page page = Page.DAYS;
    private Period period = Period.MONTH;
    private int year = LocalDate.now().getYear();

    static void show(Activity activity, Map<String, DrawingStats.Day> days, Exporter exporter) {
        new StatisticsDialog(activity, days, exporter, null, 0).open();
    }

    /** One drawing's statistics: the same window over its days only;
     *  `exporter` (may be null) gets its CSV (DrawingDays.csv). */
    static void showDrawing(Activity activity, String name, Map<String, DrawingStats.Day> days, double earlierSeconds,
                            Exporter exporter) {
        StatisticsDialog d = new StatisticsDialog(activity, days, exporter, name, earlierSeconds);
        /* a drawing's days are few: all of them, not the last month */
        d.period = Period.ALL;
        d.open();
    }

    private StatisticsDialog(Activity activity, Map<String, DrawingStats.Day> days, Exporter exporter,
                             String drawing, double earlierSeconds) {
        this.activity = activity;
        this.days = days;
        this.exporter = exporter;
        this.drawing = drawing;
        this.earlierSeconds = earlierSeconds;
        chart = new StatsChartView(activity);
        footer = text(13, false, TEXT2);
        periods = segmented();
        pages = segmented();
        yearBox = new LinearLayout(activity);
        yearLabel = text(17, true, TEXT);
    }

    private void open() {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(root);

        // Header: title on the left, close on the right.
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(24, true, TEXT); title.setText(drawing == null ? "Statistics" : drawing);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = pill("Close", false); close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        root.addView(head);
        TextView intro = text(13, false, TEXT2);
        intro.setText(drawing == null ? "Counted while SAI is in front, whether or not you record."
            : "This drawing only: counted while SAI showed it, since SAI Pen 0.5.0.");
        root.addView(intro, lp(-1, -2, 0, 2, 0, 16));

        for (Period p : Period.values()) addSegment(periods, p.label, p, p == period, () -> { period = p; refresh(); });
        root.addView(periods, lp(-1, -2, 0, 0, 0, 10));

        yearBox.setGravity(Gravity.CENTER);
        TextView back = pill("‹", false), next = pill("›", false);
        yearBox.addView(back); yearBox.addView(yearLabel, lp(-2, -2, 18, 0, 18, 0)); yearBox.addView(next);
        back.setOnClickListener(v -> { year--; refresh(); });
        next.setOnClickListener(v -> { year++; refresh(); });
        root.addView(yearBox, lp(-1, -2, 0, 0, 0, 10));

        GridLayout grid = new GridLayout(activity); grid.setColumnCount(2);
        String[] names = drawing == null ? new String[] {"TOTAL", "DAYS DRAWN", "AVERAGE DAY", "STREAK"}
                                         : new String[] {"TOTAL", "DAYS DRAWN", "AVERAGE DAY", "LAST DRAWN"};
        for (int i = 0; i < 4; i++) {
            LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(SURFACE, 14)); card.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView name = text(11, true, TEXT2); name.setText(names[i]); name.setLetterSpacing(.08f);
            cardValue[i] = text(22, true, TEXT); cardValue[i].setSingleLine(); cardValue[i].setEllipsize(TextUtils.TruncateAt.END);
            cardDetail[i] = text(12, false, TEXT2); cardDetail[i].setSingleLine(); cardDetail[i].setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name); card.addView(cardValue[i], lp(-1, -2, 0, 4, 0, 2)); card.addView(cardDetail[i]);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0; p.height = -2; p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            p.setMargins(i % 2 == 0 ? 0 : dp(5), 0, i % 2 == 0 ? dp(5) : 0, dp(10));
            grid.addView(card, p);
        }
        root.addView(grid);

        LinearLayout chartCard = new LinearLayout(activity); chartCard.setOrientation(LinearLayout.VERTICAL);
        chartCard.setBackground(round(SURFACE, 14)); chartCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        String[] labels = {"Days", "Hours", "Weekdays", "Calendar"};
        for (int i = 0; i < labels.length; i++) {
            Page pg = Page.values()[i];
            addSegment(pages, labels[i], pg, pg == page, () -> { page = pg; refresh(); });
        }
        chartCard.addView(pages, lp(-1, -2, 0, 0, 0, 8));
        chartCard.addView(chart, lp(-1, -2, 0, 0, 0, 0));
        root.addView(chartCard, lp(-1, -2, 0, 0, 0, 14));

        root.addView(footer, lp(-1, -2, 0, 0, 0, 4));
        TextView note = text(12, false, TEXT3);
        note.setText("Short pauses count as drawing. A pause over a minute is a break.");
        root.addView(note);
        if (exporter != null) {
            TextView export = pill("Export CSV…", true);
            export.setOnClickListener(v -> exporter.export(drawing == null ? DrawingStats.csv(days)
                                                                           : DrawingDays.csv(drawing, days, earlierSeconds)));
            root.addView(export, lp(-2, -2, 0, 16, 0, 0));
        }

        dialog.setContentView(scroll);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(BG));
            w.setStatusBarColor(BG); w.setNavigationBarColor(BG);
            w.setLayout(-1, -1);
        }
        refresh();
        dialog.show();
    }

    private void refresh() {
        LocalDate today = LocalDate.now();
        int firstYear = today.getYear();
        for (String key : days.keySet()) try { firstYear = Math.min(firstYear, LocalDate.parse(key).getYear()); } catch (RuntimeException ignored) {}
        year = Math.max(firstYear, Math.min(today.getYear(), year));
        boolean calendar = page == Page.CALENDAR;
        periods.setVisibility(calendar ? View.GONE : View.VISIBLE);
        yearBox.setVisibility(calendar ? View.VISIBLE : View.GONE);
        yearLabel.setText(String.valueOf(year));
        select(periods, period); select(pages, page);
        LocalDate from, to;
        if (calendar) {
            from = LocalDate.of(year, 1, 1);
            LocalDate end = LocalDate.of(year, 12, 31);
            to = today.isBefore(end) ? today : end;
        }
        else { to = today; from = period == Period.ALL ? first(days, today) : today.minusDays(period.days - 1); }
        DrawingStats.Day sum = sum(from, to);
        // Days before counting began are not days off: "2 of 2", not "2 of 325".
        int span = DrawingStats.countedDays(days, from, to, today), drawn = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) if (get(d).strokes > 0) drawn++;
        card(0, duration(sum.activeSeconds), strokes(sum.strokes));
        if (drawing == null) {
            card(1, drawn + " of " + span, drawn == 0 ? "No strokes yet" : "days with drawing");
            card(2, duration(span == 0 ? 0 : sum.activeSeconds / span), "including days off");
            int streak = DrawingStats.streak(days, today);
            card(3, streak + (streak == 1 ? " day" : " days"), "current run");
        }
        else {
            /* a drawing has days on it and days on other things, not days off */
            card(1, drawn + (drawn == 1 ? " day" : " days"), drawn == 0 ? "No strokes yet" : "with work on it");
            card(2, duration(drawn == 0 ? 0 : sum.activeSeconds / drawn), "on a day worked on it");
            LocalDate last = last(days);
            card(3, last == null ? "—" : StatsChartView.shortDate(last),
                 last == null ? "" : last.equals(today) ? "today" : (today.toEpochDay() - last.toEpochDay()) + " days ago");
        }
        chart.set(days, page, from, to, year);
        DrawingStats.Day all = DrawingStats.total(days);
        String earlier = earlierSeconds > 0 ? " · " + duration(earlierSeconds) + " before days were kept" : "";
        footer.setText(all.strokes == 0 && earlierSeconds == 0 ? "Nothing counted yet. Draw in SAI and it will show up here."
            : all.strokes == 0 ? "Earlier: " + duration(earlierSeconds) + ", from before days were kept."
            : "All time: " + duration(all.activeSeconds) + " · " + strokes(all.strokes) + " · since " + first(days, today) + earlier);
    }

    private void card(int i, String value, String detail) { cardValue[i].setText(value); cardDetail[i].setText(detail); }

    /* ---- segmented control: a dark track with the chosen piece in the accent ---- */

    private LinearLayout segmented() {
        LinearLayout l = new LinearLayout(activity);
        l.setBackground(round(SURFACE, 10));
        l.setPadding(dp(3), dp(3), dp(3), dp(3));
        return l;
    }
    private void addSegment(LinearLayout bar, String label, Object tag, boolean on, Runnable click) {
        TextView t = text(14, true, TEXT2); t.setText(label); t.setTag(tag); t.setGravity(Gravity.CENTER);
        t.setSingleLine(); t.setPadding(dp(6), dp(8), dp(6), dp(8));
        t.setOnClickListener(v -> click.run());
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
    }
    private void select(LinearLayout bar, Object tag) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            TextView t = (TextView) bar.getChildAt(i); boolean on = t.getTag() == tag;
            t.setBackground(on ? round(ACCENT, 8) : null);
            t.setTextColor(on ? Color.WHITE : TEXT2);
        }
    }
    private TextView pill(String label, boolean accent) {
        TextView t = text(14, true, accent ? Color.WHITE : TEXT); t.setText(label); t.setGravity(Gravity.CENTER);
        t.setBackground(round(accent ? ACCENT : SURFACE, 10));
        t.setPadding(dp(16), dp(9), dp(16), dp(9)); t.setMinWidth(dp(44));
        return t;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }

    private DrawingStats.Day sum(LocalDate from, LocalDate to) {
        DrawingStats.Day sum = new DrawingStats.Day();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DrawingStats.Day x = get(d); sum.strokes += x.strokes; sum.penSeconds += x.penSeconds; sum.activeSeconds += x.activeSeconds;
        }
        return sum;
    }
    private DrawingStats.Day get(LocalDate d) { DrawingStats.Day x = days.get(d.toString()); return x == null ? new DrawingStats.Day() : x; }
    private static LocalDate first(Map<String, DrawingStats.Day> days, LocalDate fallback) {
        LocalDate first = fallback;
        for (String key : days.keySet()) try { first = LocalDate.parse(key); break; } catch (RuntimeException ignored) {}
        return first;
    }
    private static LocalDate last(Map<String, DrawingStats.Day> days) {
        LocalDate last = null;
        for (Map.Entry<String, DrawingStats.Day> e : days.entrySet()) {
            if (e.getValue().strokes <= 0) continue;
            try { LocalDate d = LocalDate.parse(e.getKey()); if (last == null || d.isAfter(last)) last = d; } catch (RuntimeException ignored) {}
        }
        return last;
    }
    private static String duration(double seconds) { int m = (int) (seconds / 60); return m < 60 ? m + " min" : m / 60 + " h" + (m % 60 == 0 ? "" : " " + m % 60 + " min"); }
    private static String strokes(int n) { return String.format(Locale.getDefault(), "%,d stroke%s", n, n == 1 ? "" : "s"); }
    private TextView text(int size, boolean bold, int color) {
        TextView v = new TextView(activity); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private int dp(int n) { return (int) (n * activity.getResources().getDisplayMetrics().density + .5f); }

    /** Four visualizations from the macOS tab, drawn on the chart card. */
    private static final class StatsChartView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Map<String, DrawingStats.Day> days;
        private Page page; private LocalDate from, to; private int year;

        StatsChartView(Activity a) { super(a); }

        void set(Map<String, DrawingStats.Day> d, Page pg, LocalDate f, LocalDate t, int y) {
            days = d; page = pg; from = f; to = t; year = y; requestLayout(); invalidate();
        }

        /* The calendar is taller than the bars: twelve month grids. */
        private int columns(int w) { return w >= dp(620) ? 4 : 3; }
        private float cell(int w) { int cols = columns(w); return Math.min((w - dp(16) * (cols - 1)) / (cols * 7f), dp(18)); }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h;
            if (page == Page.CALENDAR) { int rows = 12 / columns(w); h = (int) (dp(28) + rows * (6 * cell(w) + dp(30)) + dp(30)); }
            else h = dp(260);
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (days == null) return;
            if (page == Page.CALENDAR) calendar(c); else bars(c);
        }

        private void label(Canvas c, String s, float x, float y, int size, int color, boolean bold, Paint.Align align) {
            p.setTextSize(dp(size)); p.setColor(color); p.setTextAlign(align);
            p.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
            c.drawText(s, x, y, p);
        }

        private void bars(Canvas c) {
            int w = getWidth(), h = getHeight(); double[] values; String title;
            int highlight = -1;
            if (page == Page.HOURS) {
                values = new double[24];
                for (DrawingStats.Day d : range()) if (d.hourly != null) for (int i = 0; i < 24; i++) values[i] += d.hourly[i];
                title = "BY HOUR OF DAY";
            }
            else if (page == Page.WEEKDAYS) {
                values = new double[7]; int[] n = new int[7];
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) { int i = d.getDayOfWeek().getValue() - 1; values[i] += get(d).activeSeconds; n[i]++; }
                for (int i = 0; i < 7; i++) if (n[i] > 0) values[i] /= n[i];
                title = "AVERAGE BY DAY OF WEEK";
            }
            else {
                int n = (int) (to.toEpochDay() - from.toEpochDay() + 1), buckets = Math.min(31, n);
                values = new double[buckets];
                for (int i = 0; i < n; i++) values[(int) ((long) i * buckets / n)] += get(from.plusDays(i)).activeSeconds;
                highlight = buckets - 1;
                title = n <= 31 ? "DRAWING TIME BY DAY" : "DRAWING TIME";
            }
            label(c, title, dp(4), dp(14), 11, TEXT2, true, Paint.Align.LEFT);

            double max = 0; int best = -1;
            for (int i = 0; i < values.length; i++) if (values[i] > max) { max = values[i]; best = i; }
            double top = niceTop(max);
            float left = dp(52), plotTop = dp(30), bottom = h - dp(24), width = w - left - dp(4), slot = width / values.length;

            // Grid: three faint lines with round values.
            for (int k = 0; k <= 3; k++) {
                float y = bottom - (bottom - plotTop) * k / 3f;
                p.setColor(k == 0 ? 0x44FFFFFF : LINE); c.drawRect(left, y - .5f, left + width, y + .5f, p);
                label(c, k == 0 ? "0" : duration(top * k / 3), left - dp(8), y + dp(4), 10, TEXT3, false, Paint.Align.RIGHT);
            }

            if (max <= 0) {
                label(c, "No drawing in this period", left + width / 2, (plotTop + bottom) / 2, 13, TEXT3, false, Paint.Align.CENTER);
            }
            float bw = Math.max(dp(2), Math.min(values.length <= 7 ? dp(36) : dp(20), slot * .62f)), rad = Math.min(dp(4), bw / 2);
            for (int i = 0; i < values.length; i++) {
                if (values[i] <= 0) continue;
                float bh = Math.max(dp(3), (float) ((bottom - plotTop) * values[i] / top));
                float cx = left + slot * (i + .5f);
                p.setColor(i == best || i == highlight ? ACCENT : withAlpha(ACCENT, 0.55f));
                r.set(cx - bw / 2, bottom - bh, cx + bw / 2, bottom + rad);
                c.save(); c.clipRect(left, plotTop - dp(4), left + width, bottom); c.drawRoundRect(r, rad, rad, p); c.restore();
            }

            float ly = bottom + dp(16);
            if (page == Page.HOURS) {
                for (int hr : new int[] {0, 6, 12, 18, 23}) label(c, String.valueOf(hr), left + slot * (hr + .5f), ly, 10, TEXT3, false, Paint.Align.CENTER);
            }
            else if (page == Page.WEEKDAYS) {
                for (int i = 0; i < 7; i++) label(c, java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                                  left + slot * (i + .5f), ly, 10, TEXT3, false, Paint.Align.CENTER);
            }
            else {
                label(c, shortDate(from), left, ly, 10, TEXT3, false, Paint.Align.LEFT);
                label(c, "Today", left + width, ly, 10, TEXT3, false, Paint.Align.RIGHT);
            }
        }

        private void calendar(Canvas c) {
            int w = getWidth(), cols = columns(w); float cell = cell(w), gap = Math.max(1, cell * .14f);
            label(c, "EVERY DAY OF " + year, dp(4), dp(14), 11, TEXT2, true, Paint.Align.LEFT);
            float monthW = 7 * cell, colGap = cols > 1 ? (w - cols * monthW) / (cols - 1) : 0;
            LocalDate today = LocalDate.now();
            for (int m = 1; m <= 12; m++) {
                int col = (m - 1) % cols, row = (m - 1) / cols;
                float x = col * (monthW + colGap), y = dp(28) + row * (6 * cell + dp(30)) + dp(18);
                LocalDate d = LocalDate.of(year, m, 1);
                label(c, d.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()), x, y - dp(6), 11, TEXT, true, Paint.Align.LEFT);
                int lead = d.getDayOfWeek().getValue() - 1;
                for (int day = 1; day <= d.lengthOfMonth(); day++, d = d.plusDays(1)) {
                    int pos = lead + day - 1;
                    r.set(x + (pos % 7) * cell, y + (pos / 7) * cell, x + (pos % 7 + 1) * cell - gap, y + (pos / 7 + 1) * cell - gap);
                    if (d.isAfter(today)) {
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(0x22FFFFFF);
                        c.drawRoundRect(r, dp(2), dp(2), p); p.setStyle(Paint.Style.FILL);
                        continue;
                    }
                    p.setColor(levelColor(level(get(d).activeSeconds)));
                    c.drawRoundRect(r, dp(2), dp(2), p);
                    if (d.equals(today)) {
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(TEXT);
                        c.drawRoundRect(r, dp(2), dp(2), p); p.setStyle(Paint.Style.FILL);
                    }
                }
            }
            // Legend with swatches, like the Mac.
            String[] names = {"None", "< 30 min", "30–60 min", "1–2 h", "2 h +"};
            float lx = 0, ly = getHeight() - dp(10), sw = dp(11);
            p.setTextSize(dp(10));
            for (int i = 0; i < 5; i++) {
                p.setColor(levelColor(i)); r.set(lx, ly - sw + dp(1), lx + sw, ly + dp(1)); c.drawRoundRect(r, dp(2), dp(2), p);
                label(c, names[i], lx + sw + dp(5), ly, 10, TEXT2, false, Paint.Align.LEFT);
                lx += sw + dp(5) + p.measureText(names[i]) + dp(14);
            }
        }

        private static int level(double v) { return v == 0 ? 0 : v < 1800 ? 1 : v < 3600 ? 2 : v < 7200 ? 3 : 4; }
        /** More drawing reads as more colour: brighter on dark, as on the Mac. */
        private static int levelColor(int level) {
            switch (level) {
                case 0: return 0x17FFFFFF;
                case 1: return withAlpha(ACCENT, .35f);
                case 2: return withAlpha(ACCENT, .6f);
                case 3: return withAlpha(ACCENT, .85f);
                default: return blend(ACCENT, Color.WHITE, .35f);
            }
        }
        private static int withAlpha(int c, float a) { return (Math.round(a * 255) << 24) | (c & 0xFFFFFF); }
        private static int blend(int a, int b, float f) {
            return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                             Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                             Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
        }
        /** A round top for the axis: 15/30 min, 1/2/3/4/6 h and so on. */
        private static double niceTop(double max) {
            double[] steps = {15 * 60, 30 * 60, 3600, 2 * 3600, 3 * 3600, 4 * 3600, 6 * 3600, 9 * 3600, 12 * 3600, 18 * 3600, 24 * 3600};
            for (double s : steps) if (max <= s) return s;
            double h = Math.ceil(max / (3 * 3600)) * 3 * 3600; return h;
        }
        private static String shortDate(LocalDate d) { return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()); }
        private List<DrawingStats.Day> range() { List<DrawingStats.Day> out = new ArrayList<>(); for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) out.add(get(d)); return out; }
        private DrawingStats.Day get(LocalDate d) { DrawingStats.Day x = days.get(d.toString()); return x == null ? new DrawingStats.Day() : x; }
        private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    }
}
