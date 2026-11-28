package com.winlator.saipen;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.R;
import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Pending;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The Mac app's Videos tab on Android: one card per drawing, its still, how
 * many sessions and frames, how long, how big, when last drawn. A tap plays
 * the drawing's video. Holding a card selects it, then a tap adds or takes
 * away more, and Delete removes them after asking once, as in All drawings
 * (CardSelection). A session that may
 * continue another drawing says so on its card, with the question one tap
 * away. Same colours and shapes as StatisticsDialog.
 */
final class TimelapsesDialog {
    private static final int BG = StatisticsDialog.BG, SURFACE = StatisticsDialog.SURFACE,
                             TEXT = StatisticsDialog.TEXT, TEXT2 = StatisticsDialog.TEXT2,
                             TEXT3 = StatisticsDialog.TEXT3, ACCENT = StatisticsDialog.ACCENT;

    private final SaiLauncherActivity activity;
    private final ExecutorService worker;
    private DrawingLibrary lib;
    /** Each joined video's size by its file name (sizes()), read off the
     *  main thread: hundreds of single file checks on shared storage held
     *  the window ~3 s on a Tab S6 Lite (2026-11-28). */
    private java.util.Map<String, Long> sizes;
    private final LinearLayout root;
    private Dialog dialog;
    /** Held cards, by drawing id (a reload makes new Drawings). */
    private final CardSelection<String> selection;
    /** The title row: "All timelapses" and Close, or what is selected. */
    private LinearLayout head;
    /** The cards on the screen, to show the selection on them. */
    private final java.util.List<Runnable> showOnCards = new java.util.ArrayList<>();

    private ScrollView scroll;

    /** A card's picture, read only when the card is on the screen or about
     *  to be: with 69 drawings, reading every picture first kept the window
     *  slow to fill (2026-11-28). */
    private static final class LazyStill {
        final View card;
        final ImageView view;
        final File file;
        boolean asked;

        LazyStill(View card, ImageView view, File file) {
            this.card = card;
            this.view = view;
            this.file = file;
        }
    }
    private final List<LazyStill> stills = new java.util.ArrayList<>();

    /** The window's own thread for its list, sizes and pictures, so they
     *  never wait behind the start screen's work on the launcher's worker. */
    private static final ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sai-timelapses-window");
        t.setDaemon(true);
        return t;
    });
    /** Cards built at once; the rest follow CHUNK at a time, a frame apart. */
    static final int FIRST_CARDS = 9, CHUNK = 12;

    /** When the tap came (uptimeMillis), for the log's times. */
    private long tappedAt;
    private boolean closed, timed;
    /** Each fill() a new one: chunks of an older fill stop. */
    private int generation;
    private TextView summary;
    /** Each card's size line, set again once the sizes are read. */
    private final List<Runnable> sizeLines = new java.util.ArrayList<>();

    /**
     * Opens the window at once, with "Loading" in it, and fills it as the
     * list, the cards, the sizes and the pictures come: waiting for them
     * before opening felt like a frozen app (2 s with 68 drawings on a Tab S6
     * Lite, 2026-11-28). The log says how long each step took after the tap.
     */
    static void open(SaiLauncherActivity activity, ExecutorService worker, long tappedAt) {
        TimelapsesDialog t = new TimelapsesDialog(activity, worker);
        t.tappedAt = tappedAt;
        t.openNow();
    }

    /** The timelapse folder's videos and their sizes, in one look at the
     *  folder. Not on the main thread. */
    static java.util.Map<String, Long> sizes() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        File[] all = TimelapseShelf.root().listFiles();
        if (all != null) for (File f : all) if (f.getName().endsWith(".mp4")) m.put(f.getName(), f.length());
        return m;
    }

    private TimelapsesDialog(SaiLauncherActivity activity, ExecutorService worker) {
        this.activity = activity;
        this.worker = worker;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        selection = new CardSelection<>(activity, new CardSelection.Host<String>() {
            @Override public List<String> cards() {
                List<String> ids = new java.util.ArrayList<>();
                if (TimelapsesDialog.this.lib == null) return ids;
                for (Drawing d : TimelapsesDialog.this.lib.newestFirst()) ids.add(d.id);
                return ids;
            }
            @Override public void selectionChanged() {
                fillHead();
                for (Runnable r : showOnCards) r.run();
            }
            @Override public void delete(List<String> chosen) {
                TimelapsesDialog.this.delete(chosen);
            }
        }, R.plurals.saipen_timelapses_delete_q, R.string.saipen_timelapses_delete_what);
    }

    private void openNow() {
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.setOnDismissListener(d -> closed = true);
        scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        /* pictures as their cards come near the screen */
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisible());
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> loadVisible());
        dialog.setContentView(scroll);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(BG));
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
            w.setLayout(-1, -1);
        }
        /* Back while selecting lets go of the selection, not of the window */
        dialog.setOnKeyListener((d, key, e) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK || selection.isEmpty()) return false;
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) selection.clear();
            return true;
        });
        fill();
        dialog.show();
        scroll.post(() -> android.util.Log.i("SaiPen", "All timelapses: window up "
            + (android.os.SystemClock.uptimeMillis() - tappedAt) + " ms after the tap"));
        load();
    }

    /** The list, then the cards, then the sizes; each shows as it comes. */
    private void load() {
        LOADER.execute(() -> {
            DrawingLibrary fresh = TimelapseShelf.load();
            long listAt = android.os.SystemClock.uptimeMillis();
            activity.runOnUiThread(() -> {
                if (closed) return;
                lib = fresh;
                fill();
                if (!timed) android.util.Log.i("SaiPen", "All timelapses: " + fresh.drawings.size()
                    + " drawing(s) read " + (listAt - tappedAt) + " ms after the tap, the first cards up at "
                    + (android.os.SystemClock.uptimeMillis() - tappedAt) + " ms");
            });
            java.util.Map<String, Long> freshSizes = sizes();
            long sizesAt = android.os.SystemClock.uptimeMillis();
            activity.runOnUiThread(() -> {
                if (closed) return;
                sizes = freshSizes;
                if (summary != null) summary.setText(summaryText());
                for (Runnable r : sizeLines) r.run();
                if (!timed) android.util.Log.i("SaiPen", "All timelapses: sizes read " + (sizesAt - tappedAt)
                    + " ms after the tap");
                timed = true;
            });
        });
    }

    private void reload() {
        load();
    }

    private void fill() {
        final int gen = ++generation;
        root.removeAllViews();
        stills.clear();
        showOnCards.clear();
        sizeLines.clear();
        summary = null;
        if (lib == null) {
            /* the window first, the list in a moment (load()) */
            head = new LinearLayout(activity);
            root.addView(head, lp(-1, -2, 0, 0, 0, 0));
            fillHead();
            TextView wait = text(15, false, TEXT2);
            wait.setText(R.string.saipen_timelapses_loading);
            root.addView(wait, lp(-1, -2, 0, 12, 0, 0));
            return;
        }
        List<Drawing> drawings = lib.newestFirst();
        /* only what is there stays selected */
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Drawing d : drawings) ids.add(d.id);
        selection.keepOnly(ids);
        head = new LinearLayout(activity);
        root.addView(head, lp(-1, -2, 0, 0, 0, 0));
        fillHead();

        TextView intro = text(13, false, TEXT2);
        intro.setText(R.string.saipen_timelapses_intro);
        root.addView(intro, lp(-1, -2, 0, 2, 0, 6));

        summary = text(13, false, TEXT3);
        summary.setText(summaryText());
        root.addView(summary, lp(-1, -2, 0, 0, 0, 14));

        if (drawings.isEmpty()) {
            TextView empty = text(15, false, TEXT2);
            empty.setText(R.string.saipen_timelapses_empty);
            empty.setBackground(round(SURFACE, 14));
            empty.setPadding(dp(16), dp(20), dp(16), dp(20));
            root.addView(empty, lp(-1, -2, 0, 0, 0, 0));
        }
        else {
            GridLayout grid = new GridLayout(activity);
            int cols = activity.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
            grid.setColumnCount(cols);
            /* the first screenful now, the rest a chunk a frame, so the window never stalls */
            int first = Math.min(FIRST_CARDS, drawings.size());
            for (int i = 0; i < first; i++) grid.addView(card(drawings.get(i)), cell(i, cols));
            root.addView(grid);
            addCards(grid, drawings, first, cols, gen);
        }

        TextView hint = text(12, false, TEXT3);
        hint.setText(R.string.saipen_timelapses_hint);
        root.addView(hint, lp(-1, -2, 0, 10, 0, 0));
        TextView folder = pill(activity.getString(R.string.saipen_timelapses_folder), true);
        folder.setOnClickListener(v -> activity.openTimelapseFolder());
        root.addView(folder, lp(-2, -2, 0, 14, 0, 0));
    }

    /** Cards `from` on, CHUNK a frame, until another fill() or the window closes. */
    private void addCards(GridLayout grid, List<Drawing> drawings, int from, int cols, int gen) {
        if (from >= drawings.size()) return;
        root.post(() -> {
            if (closed || gen != generation) return;
            int to = Math.min(drawings.size(), from + CHUNK);
            for (int i = from; i < to; i++) grid.addView(card(drawings.get(i)), cell(i, cols));
            addCards(grid, drawings, to, cols, gen);
        });
    }

    /** "68 drawings · 96 sessions · 3.2 GB · hold…", the size as "…" until read. */
    private String summaryText() {
        List<Drawing> drawings = lib.newestFirst();
        int sessions = 0;
        long bytes = 0;
        for (Drawing d : drawings) {
            sessions += d.pieces.size();
            bytes += Math.max(0, size(d));
        }
        return activity.getString(R.string.saipen_timelapses_summary, drawings.size(), sessions,
                                  sizes == null ? "…" : Formatter.formatShortFileSize(activity, bytes))
               + (drawings.isEmpty() ? "" : " · " + activity.getString(R.string.saipen_drawings_hold_hint));
    }

    /** "All timelapses" and Close; while selecting, how many, Select all,
     *  Delete and Cancel (CardSelection). */
    private void fillHead() {
        if (head == null) return;
        if (!selection.isEmpty()) {
            selection.fillHead(head);
            return;
        }
        head.removeAllViews();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(24, true, TEXT);
        title.setText(R.string.saipen_all_timelapses);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = pill(activity.getString(R.string.saipen_close), false);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
    }

    private View card(Drawing d) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(SURFACE, 14));
        card.setPadding(dp(10), dp(10), dp(10), dp(12));

        ImageView still = new ImageView(activity);
        still.setScaleType(ImageView.ScaleType.FIT_CENTER);
        still.setBackground(round(0xFF24282C, 10));
        still.setAdjustViewBounds(false);
        /* while selecting: an empty ring, or a tick on the accent colour */
        android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
        frame.addView(still, new android.widget.FrameLayout.LayoutParams(-1, -1));
        TextView check = selection.newCheck();
        android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(
            dp(CardSelection.CHECK_DP), dp(CardSelection.CHECK_DP), Gravity.TOP | Gravity.START);
        cp.setMargins(dp(6), dp(6), dp(6), dp(6));
        frame.addView(check, cp);
        card.addView(frame, lp(-1, dp(150), 0, 0, 0, 8));
        stills.add(new LazyStill(card, still, TimelapseShelf.still(d)));

        TextView name = text(15, true, TEXT);
        name.setText(d.title.isEmpty() ? d.folder : d.title);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        int frames = d.totalFrames();
        int seconds = Math.max(1, Math.round(frames / (float) TimelapseVideo.FPS));
        TextView line1 = text(12, false, TEXT2);
        line1.setText(activity.getResources().getQuantityString(R.plurals.saipen_sessions, d.pieces.size(), d.pieces.size())
                      + " · " + activity.getResources().getQuantityString(R.plurals.saipen_frames, frames, frames)
                      + " · " + activity.getString(R.string.saipen_seconds_short, seconds));
        line1.setSingleLine();
        line1.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(line1, lp(-1, -2, 0, 2, 0, 0));

        TextView line2 = text(12, false, TEXT3);
        final String day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(DrawingLibrary.lastDrawn(d)));
        Runnable sizeLine = () -> line2.setText(
            (sizes == null ? "…" : Formatter.formatShortFileSize(activity, size(d))) + " · " + day);
        sizeLines.add(sizeLine);
        sizeLine.run();
        line2.setSingleLine();
        card.addView(line2);

        Pending q = lib.pendingFor(d);
        Drawing other = q == null ? null : lib.drawing(q.candidateId);
        if (other != null) {
            TextView ask = text(12, true, ACCENT);
            ask.setText(activity.getString(R.string.saipen_continues_card, other.title.isEmpty() ? other.folder : other.title));
            ask.setOnClickListener(v -> activity.askContinues(lib, q, this::reload));
            card.addView(ask, lp(-1, -2, 0, 6, 0, 0));
        }

        card.setOnClickListener(v -> {
            if (selection.isEmpty()) play(activity, d);
            else selection.toggle(d.id);
        });
        card.setOnLongClickListener(v -> {
            selection.toggle(d.id);
            return true;
        });
        Runnable show = () -> selection.show(card, check, d.id);
        showOnCards.add(show);
        show.run();
        return card;
    }

    /** A drawing's still, decoded only as big as its card needs (it is the
     *  drawing's whole size, often 2000 x 1200 or more). */
    static Bitmap stillBitmap(File f, int height) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sampleSize(o.outHeight, height);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The largest power of two that keeps the picture at least `want` high. */
    static int sampleSize(int have, int want) {
        int s = 1;
        while (want > 0 && have / (s * 2) >= want) s *= 2;
        return s;
    }

    /** Plays the drawing's joined video on the whole screen, inside the app
     *  (VideoPlayerDialog); also from DrawingsDialog. */
    static void play(SaiLauncherActivity activity, Drawing d) {
        File video = TimelapseShelf.joined(d, 0);
        if (!video.isFile()) {
            Toast.makeText(activity, R.string.saipen_timelapse_missing, Toast.LENGTH_LONG).show();
            return;
        }
        VideoPlayerDialog.show(activity, video, () -> playOutside(activity, video));
    }

    /** The video in the device's own player, from the in-app player's "Other app". */
    static void playOutside(SaiLauncherActivity activity, File video) {
        android.media.MediaScannerConnection.scanFile(activity, new String[] {video.getPath()},
            new String[] {"video/mp4"}, (path, uri) -> activity.runOnUiThread(() -> {
                if (uri == null) {
                    Toast.makeText(activity, R.string.saipen_timelapse_missing, Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "video/mp4");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(i);
                }
                catch (RuntimeException e) {
                    Toast.makeText(activity, R.string.saipen_timelapse_no_player, Toast.LENGTH_LONG).show();
                }
            }));
    }

    /** Deletes the chosen timelapses (videos and sessions; the drawings
     *  stay), then shows what is left. */
    private void delete(List<String> ids) {
        selection.busy = true;
        DrawingLibrary from = lib;
        worker.execute(() -> {
            int gone = 0;
            for (String id : ids) {
                Drawing d = from.drawing(id);
                if (d == null) continue;
                try {
                    TimelapseShelf.delete(from, d);
                    gone++;
                }
                catch (Exception e) {
                    android.util.Log.e("SaiPen", "timelapse: delete", e);
                }
            }
            int done = gone, failed = ids.size() - gone;
            activity.runOnUiThread(() -> {
                selection.busy = false;
                selection.forget();
                String msg = activity.getResources().getQuantityString(R.plurals.saipen_timelapses_deleted, done, done);
                if (failed > 0) msg += ". " + activity.getString(R.string.saipen_drawings_not_deleted, failed);
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            });
            reload();
        });
    }

    /** Reads the pictures of the cards on the screen and of the next
     *  screenful below it (and half one above), each once. */
    private void loadVisible() {
        if (scroll == null || scroll.getHeight() == 0) return;
        int top = scroll.getScrollY(), height = scroll.getHeight();
        int from = top - height / 2, to = top + 2 * height;
        for (LazyStill s : stills) {
            if (s.asked || s.card.getHeight() == 0) continue;
            int y = yInRoot(s.card);
            if (y < 0 || y > to || y + s.card.getHeight() < from) continue;
            s.asked = true;
            final int cardPx = Math.max(1, s.view.getHeight());
            LOADER.execute(() -> {
                Bitmap b = s.file.isFile() ? stillBitmap(s.file, cardPx) : null;
                if (b != null) activity.runOnUiThread(() -> s.view.setImageBitmap(b));
            });
        }
    }

    /** Where `v` is from the top of the scrolled content, or -1 if not in it. */
    private int yInRoot(View v) {
        int y = 0;
        View at = v;
        while (at != null && at != root) {
            y += at.getTop();
            if (!(at.getParent() instanceof View)) return -1;
            at = (View) at.getParent();
        }
        return at == root ? y : -1;
    }

    /** The drawing's videos' size, -1 while the sizes are not read yet. */
    private long size(Drawing d) {
        if (sizes == null) return -1;
        long n = 0;
        for (int i = 0; i < 8; i++) {
            Long len = sizes.get(DrawingLibrary.joinedName(d, i));
            if (len != null) n += len;
        }
        return n;
    }

    /* ---- the look: StatisticsDialog's ---- */

    private GridLayout.LayoutParams cell(int i, int cols) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0;
        p.height = -2;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int col = i % cols;
        p.setMargins(col == 0 ? 0 : dp(5), 0, col == cols - 1 ? 0 : dp(5), dp(10));
        return p;
    }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String label, boolean accent) {
        TextView t = text(14, true, accent ? 0xFFFFFFFF : TEXT);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(round(accent ? ACCENT : SURFACE, 18));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
