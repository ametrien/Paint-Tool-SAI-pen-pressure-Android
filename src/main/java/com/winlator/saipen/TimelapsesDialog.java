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
    private final LinearLayout root;
    private Dialog dialog;
    /** Held cards, by drawing id (a reload makes new Drawings). */
    private final CardSelection<String> selection;
    /** The title row: "All timelapses" and Close, or what is selected. */
    private LinearLayout head;
    /** The cards on the screen, to show the selection on them. */
    private final java.util.List<Runnable> showOnCards = new java.util.ArrayList<>();

    static void show(SaiLauncherActivity activity, DrawingLibrary lib, ExecutorService worker) {
        new TimelapsesDialog(activity, lib, worker).open();
    }

    private TimelapsesDialog(SaiLauncherActivity activity, DrawingLibrary lib, ExecutorService worker) {
        this.activity = activity;
        this.lib = lib;
        this.worker = worker;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        selection = new CardSelection<>(activity, new CardSelection.Host<String>() {
            @Override public List<String> cards() {
                List<String> ids = new java.util.ArrayList<>();
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

    private void open() {
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
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
    }

    private void reload() {
        worker.execute(() -> {
            DrawingLibrary fresh = TimelapseShelf.load();
            activity.runOnUiThread(() -> {
                lib = fresh;
                fill();
            });
        });
    }

    private void fill() {
        root.removeAllViews();
        showOnCards.clear();
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

        int sessions = 0;
        long bytes = 0;
        for (Drawing d : drawings) {
            sessions += d.pieces.size();
            bytes += size(d);
        }
        TextView summary = text(13, false, TEXT3);
        summary.setText(activity.getString(R.string.saipen_timelapses_summary, drawings.size(), sessions,
                                           Formatter.formatShortFileSize(activity, bytes))
                        + (drawings.isEmpty() ? "" : " · " + activity.getString(R.string.saipen_drawings_hold_hint)));
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
            for (int i = 0; i < drawings.size(); i++) grid.addView(card(drawings.get(i)), cell(i, cols));
            root.addView(grid);
        }

        TextView hint = text(12, false, TEXT3);
        hint.setText(R.string.saipen_timelapses_hint);
        root.addView(hint, lp(-1, -2, 0, 10, 0, 0));
        TextView folder = pill(activity.getString(R.string.saipen_timelapses_folder), true);
        folder.setOnClickListener(v -> activity.openTimelapseFolder());
        root.addView(folder, lp(-2, -2, 0, 14, 0, 0));
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
        File f = TimelapseShelf.still(d);
        worker.execute(() -> {
            Bitmap b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b != null) activity.runOnUiThread(() -> still.setImageBitmap(b));
        });

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
        line2.setText(Formatter.formatShortFileSize(activity, size(d)) + " · "
                      + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(DrawingLibrary.lastDrawn(d))));
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

    private static long size(Drawing d) {
        long n = 0;
        for (int i = 0; i < 8; i++) n += TimelapseShelf.joined(d, i).length();
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
