package com.winlator.saipen;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.winlator.R;
import com.winlator.saipen.DrawingIndex.Entry;
import com.winlator.saipen.DrawingIndex.Sort;
import com.winlator.saipen.DrawingLibrary.Drawing;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "All drawings": every drawing in Documents/SAI Drawings as cards, with a
 * search by name, three orders and the folders. A tap shows the drawing's
 * page: its picture larger, where it is, when it was saved, how big it is,
 * its timelapse if it has one, and Open in SAI and Share. The timelapse
 * shows as its last frame and plays in place while the pen hovers over it
 * (HoverVideo); a tap plays it in the device's player. A card whose drawing
 * has a timelapse carries a small ▶.
 *
 * The picture is the small one SAI keeps in the file (Sai2Thumb, 256 pixels
 * at most); nothing else of the drawing is read. Nothing is renamed or
 * deleted here (the owner's choice, 2026-11-26).
 *
 * The cards are a GridView, as BrowseDialog's: only the cards on the screen
 * exist, and the pictures sit in a bounded cache, so a few hundred drawings
 * cost no more memory than a screenful.
 *
 * Same colours and shapes as StatisticsDialog and TimelapsesDialog.
 */
final class DrawingsDialog {
    private static final String TAG = "SaiPen";
    private static final int BG = StatisticsDialog.BG, SURFACE = StatisticsDialog.SURFACE,
                             TEXT = StatisticsDialog.TEXT, TEXT2 = StatisticsDialog.TEXT2,
                             TEXT3 = StatisticsDialog.TEXT3, ACCENT = StatisticsDialog.ACCENT;
    /** A card: its narrowest width and its picture's height. */
    private static final int CARD_W_DP = 170, CARD_PICTURE_DP = 150;
    /** SAI's preview is 256 pixels at most: the page shows it at most this
     *  many times larger, so it is not a blur across the whole screen. */
    private static final float PAGE_MAX_SCALE = 2.5f;
    private static final int PREVIEW_PX = 256;
    /** Files made for Share live here, inside the app (the FileProvider's "files" path). */
    private static final String SHARE_DIR = "saipen-share";

    private final SaiLauncherActivity activity;
    /** The launcher's thumbnail thread: decoding pictures. */
    private final ExecutorService thumbs;
    /** This window's own thread: the list of files and Share's copies, so
     *  neither waits behind the launcher's worker (unpacking Wine). */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LruCache<File, Bitmap> pictures;
    private final Set<File> loading = new HashSet<>(), unreadable = new HashSet<>();
    private Dialog dialog;
    private LinearLayout page;
    private volatile boolean closed;

    private List<Entry> all = Collections.emptyList();
    /** Each drawing's timelapse, for those that have one (DrawingIndex.timelapseFor). */
    private Map<File, Drawing> videoOf = Collections.emptyMap();
    private String query = "";
    private String folder = null;
    private Sort sort = Sort.NEWEST;
    /** The drawing whose page is open; null on the cards. */
    private Entry shown;
    /** The first card on the screen when a page opened: Back returns there. */
    private int cardsAt;
    private boolean sharing;

    private List<Entry> list = Collections.emptyList();
    private GridView grid;
    private BaseAdapter adapter;
    private TextView summary, empty;

    static void show(SaiLauncherActivity activity, ExecutorService thumbs) {
        new DrawingsDialog(activity, thumbs).open();
    }

    private DrawingsDialog(SaiLauncherActivity activity, ExecutorService thumbs) {
        this.activity = activity;
        this.thumbs = thumbs;
        int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 8, 24 * 1024);
        pictures = new LruCache<File, Bitmap>(maxKb) {
            @Override protected int sizeOf(File k, Bitmap b) { return Math.max(1, b.getByteCount() / 1024); }
        };
    }

    private void open() {
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        dialog.setContentView(page);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(BG));
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
            w.setLayout(-1, -1);
            /* the search field must not open the keyboard by itself */
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                               | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        /* back on a drawing's page goes to the cards, not out */
        dialog.setOnKeyListener((d, key, e) -> {
            if (key != KeyEvent.KEYCODE_BACK || shown == null) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) showCards();
            return true;
        });
        dialog.setOnDismissListener(d -> {
            closed = true;
            io.shutdown();   /* a Share copy already started still finishes */
        });
        /* turned the device: the page is laid out again for the new width
           (the cards follow by themselves) */
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l, before = or - ol;
            if (before == 0 || width == before) return;
            if (grid != null) grid.setNumColumns(columns(width));
            if (shown != null) v.post(() -> { if (shown != null && !closed) showDrawing(shown); });
        });
        loading();
        dialog.show();

        File root = SaiPenSetup.drawingsFolder();
        run(io, () -> {
            clearShared();
            List<Entry> found;
            List<Drawing> videos;
            try {
                found = DrawingIndex.all(root);
            }
            catch (RuntimeException e) {
                Log.w(TAG, "drawings: listing", e);
                found = Collections.emptyList();
            }
            try {
                videos = TimelapseShelf.load().newestFirst();
            }
            catch (RuntimeException e) {
                Log.w(TAG, "drawings: no timelapse list", e);
                videos = Collections.emptyList();
            }
            Map<File, Drawing> v = new HashMap<>();
            for (Entry e : found) {
                Drawing d = DrawingIndex.timelapseFor(e, videos);
                if (d != null) v.put(e.file, d);
            }
            List<Entry> f = found;
            activity.runOnUiThread(() -> {
                if (closed) return;
                all = f;
                videoOf = v;
                showCards();
            });
        });
    }

    private void loading() {
        page.removeAllViews();
        page.addView(head(null), lp(-1, -2, 20, 16, 20, 0));
        TextView t = text(14, false, TEXT2);
        t.setText(R.string.saipen_drawings_reading);
        page.addView(t, lp(-1, -2, 20, 16, 20, 0));
    }

    /* ---- the cards ---- */

    private void showCards() {
        shown = null;
        page.removeAllViews();
        LinearLayout top = column();
        top.setPadding(dp(20), dp(16), dp(20), 0);
        top.addView(head(null), lp(-1, -2, 0, 0, 0, 10));

        if (!all.isEmpty()) {
            EditText search = new EditText(activity);
            search.setHint(R.string.saipen_drawings_search);
            search.setSingleLine();
            search.setText(query);
            search.setTextColor(TEXT);
            search.setHintTextColor(TEXT3);
            search.setTextSize(15);
            search.setBackground(round(SURFACE, 10));
            search.setPadding(dp(14), dp(10), dp(14), dp(10));
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    query = e.toString();
                    refill();
                }
            });
            top.addView(search, lp(-1, -2, 0, 0, 0, 10));

            LinearLayout bar = new LinearLayout(activity);
            bar.setBackground(round(SURFACE, 10));
            bar.setPadding(dp(3), dp(3), dp(3), dp(3));
            addSort(bar, R.string.saipen_sort_newest, Sort.NEWEST);
            addSort(bar, R.string.saipen_sort_name, Sort.NAME);
            addSort(bar, R.string.saipen_sort_size, Sort.SIZE);
            top.addView(bar, lp(-1, -2, 0, 0, 0, 10));

            List<String> folders = DrawingIndex.folders(all);
            if (folders.size() > 1) {
                HorizontalScrollView hs = new HorizontalScrollView(activity);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout chips = new LinearLayout(activity);
                chips.addView(chip(activity.getString(R.string.saipen_folder_all), null), lp(-2, -2, 0, 0, 6, 0));
                for (String f : folders) {
                    String label = f.isEmpty() ? activity.getString(R.string.saipen_folder_root) : f;
                    chips.addView(chip(label, f), lp(-2, -2, 0, 0, 6, 0));
                }
                hs.addView(chips);
                top.addView(hs, lp(-1, -2, 0, 0, 0, 10));
            }
        }
        summary = text(13, false, TEXT3);
        top.addView(summary, lp(-1, -2, 0, 0, 0, 10));
        page.addView(top);

        empty = text(15, false, TEXT2);
        empty.setBackground(round(SURFACE, 14));
        empty.setPadding(dp(16), dp(20), dp(16), dp(20));
        page.addView(empty, lp(-1, -2, 20, 0, 20, 0));

        grid = new GridView(activity);
        grid.setNumColumns(columns(page.getWidth()));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setClipToPadding(false);
        grid.setPadding(dp(20), 0, dp(20), dp(24));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int i) { return list.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                Card c = convert == null ? new Card() : (Card) convert.getTag();
                c.bind(list.get(i));
                return c.view;
            }
        };
        grid.setAdapter(adapter);
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        refill();
        if (cardsAt > 0 && cardsAt < list.size()) grid.setSelection(cardsAt);
        cardsAt = 0;
    }

    /** How many cards side by side in `widthPx` (the screen's width before
     *  the first layout): as many as fit, at least 2. */
    private int columns(int widthPx) {
        if (widthPx <= 0) widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int room = widthPx - dp(40) + dp(10);
        return Math.max(2, room / (dp(CARD_W_DP) + dp(10)));
    }

    private void addSort(LinearLayout bar, int label, Sort s) {
        TextView t = text(14, true, s == sort ? Color.WHITE : TEXT2);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        if (s == sort) t.setBackground(round(ACCENT, 8));
        t.setOnClickListener(v -> {
            if (sort == s) return;
            sort = s;
            for (int i = 0; i < bar.getChildCount(); i++) {
                TextView o = (TextView) bar.getChildAt(i);
                boolean on = o == t;
                o.setBackground(on ? round(ACCENT, 8) : null);
                o.setTextColor(on ? Color.WHITE : TEXT2);
            }
            refill();
            grid.setSelection(0);
        });
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private TextView chip(String label, String f) {
        boolean on = f == null ? folder == null : f.equals(folder);
        TextView c = text(13, on, on ? Color.WHITE : TEXT2);
        c.setText(label);
        c.setSingleLine();
        c.setBackground(round(on ? ACCENT : SURFACE, 16));
        c.setPadding(dp(14), dp(7), dp(14), dp(7));
        c.setOnClickListener(v -> {
            if (f == null ? folder == null : f.equals(folder)) return;
            folder = f;
            showCards();
        });
        return c;
    }

    /** The cards for the search, the order and the folder now chosen. */
    private void refill() {
        list = DrawingIndex.view(all, query, folder, sort);
        String n = activity.getResources().getQuantityString(R.plurals.saipen_n_drawings, list.size(), list.size());
        summary.setText(list.isEmpty() ? "" : n + " · " + Formatter.formatShortFileSize(activity, DrawingIndex.totalSize(list)));
        summary.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setText(all.isEmpty() ? R.string.saipen_drawings_none : R.string.saipen_drawings_none_found);
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** One card, reused for another drawing as the grid scrolls. */
    private final class Card {
        final LinearLayout view = column();
        final ImageView picture = new ImageView(activity);
        final TextView badge = text(11, true, TEXT);
        final TextView name = text(14, true, TEXT), line = text(12, false, TEXT3), where = text(12, false, TEXT3);
        Entry entry;

        Card() {
            view.setTag(this);
            view.setBackground(round(SURFACE, 14));
            /* a tap shows, as on the start screen's rows */
            view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, round(Color.WHITE, 14)));
            view.setPadding(dp(8), dp(8), dp(8), dp(10));
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setBackground(round(0xFF24282C, 10));
            FrameLayout frame = new FrameLayout(activity);
            frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            /* this drawing has a timelapse */
            badge.setText("▶");
            badge.setBackground(round(0xCC1B1E21, 10));
            badge.setPadding(dp(7), dp(2), dp(7), dp(3));
            badge.setContentDescription(activity.getString(R.string.saipen_drawing_timelapse));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(dp(6), dp(6), dp(6), dp(6));
            frame.addView(badge, bp);
            view.addView(frame, lp(-1, dp(CARD_PICTURE_DP), 0, 0, 0, 8));
            for (TextView t : new TextView[] {name, line, where}) {
                t.setSingleLine();
                t.setEllipsize(TextUtils.TruncateAt.END);
            }
            where.setEllipsize(TextUtils.TruncateAt.START);
            view.addView(name);
            view.addView(line, lp(-1, -2, 0, 2, 0, 0));
            view.addView(where);
            view.setOnClickListener(v -> { if (entry != null) showDrawing(entry); });
        }

        void bind(Entry e) {
            entry = e;
            view.setContentDescription(e.title);
            name.setText(e.title);
            line.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(e.modified))
                         + " · " + Formatter.formatShortFileSize(activity, e.size));
            /* which folder, when the cards come from more than one */
            boolean showWhere = folder == null && !e.folder.isEmpty();
            where.setText(showWhere ? e.folder : "");
            where.setVisibility(showWhere ? View.VISIBLE : View.INVISIBLE);
            badge.setVisibility(videoOf.containsKey(e.file) ? View.VISIBLE : View.GONE);
            picture.setImageBitmap(pictures.get(e.file));
            load(e.file, dp(CARD_PICTURE_DP));
        }
    }

    /** Decodes a drawing's picture once, off the UI thread, then puts it on
     *  the cards (or the page) showing that drawing. */
    private void load(File f, int px) {
        if (pictures.get(f) != null || unreadable.contains(f) || !loading.add(f)) return;
        run(thumbs, () -> {
            Bitmap b = closed ? null : SaiLauncherActivity.thumbnail(f, px);
            activity.runOnUiThread(() -> {
                loading.remove(f);
                if (closed) return;
                if (b == null) {
                    unreadable.add(f);
                    return;
                }
                pictures.put(f, b);
                if (grid != null && shown == null) {
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        Object tag = grid.getChildAt(i).getTag();
                        if (tag instanceof Card && ((Card) tag).entry != null && ((Card) tag).entry.file.equals(f)) {
                            ((Card) tag).picture.setImageBitmap(b);
                        }
                    }
                }
                if (shown != null && shown.file.equals(f) && pagePicture != null) pagePicture.setImageBitmap(b);
            });
        });
    }

    /* ---- one drawing's page ---- */

    private ImageView pagePicture;

    private void showDrawing(Entry e) {
        if (shown == null && grid != null) cardsAt = grid.getFirstVisiblePosition();
        shown = e;
        /* the search's keyboard, if it was up, goes with the cards */
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(page.getWindowToken(), 0);
        page.removeAllViews();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout col = column();
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        col.addView(head(e), lp(-1, -2, 0, 0, 0, 12));

        int screenW = page.getWidth() > 0 ? page.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        boolean wide = screenW > screenH || screenW >= dp(600);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        col.addView(body);

        pagePicture = new ImageView(activity);
        pagePicture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pagePicture.setBackground(round(SURFACE, 14));
        pagePicture.setPadding(dp(8), dp(8), dp(8), dp(8));
        int h = Math.min((int) (screenH * (wide ? 0.6f : 0.45f)),
                         Math.max(dp(240), Math.round(PREVIEW_PX * PAGE_MAX_SCALE)));
        body.addView(pagePicture, wide ? lpw(0, h, 1f, 0, 0, 0, 0) : new LinearLayout.LayoutParams(-1, h));
        pagePicture.setImageBitmap(pictures.get(e.file));
        load(e.file, dp(CARD_PICTURE_DP));

        LinearLayout info = column();
        if (wide) body.addView(info, lpw(0, -2, 1f, 16, 0, 0, 0));
        else body.addView(info, lp(-1, -2, 0, 14, 0, 0));

        TextView note = text(12, false, TEXT3);
        note.setText(R.string.saipen_drawing_preview_note);
        info.addView(note, lp(-1, -2, 0, 0, 0, 10));

        LinearLayout facts = column();
        facts.setBackground(round(SURFACE, 14));
        facts.setPadding(dp(14), dp(4), dp(14), dp(4));
        String where = activity.getString(R.string.saipen_folder_root) + (e.folder.isEmpty() ? "" : "/" + e.folder);
        fact(facts, R.string.saipen_drawing_folder, where, true);
        fact(facts, R.string.saipen_drawing_saved,
             DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(e.modified)), true);
        fact(facts, R.string.saipen_drawing_size, Formatter.formatShortFileSize(activity, e.size), false);
        info.addView(facts, lp(-1, -2, 0, 0, 0, 12));

        Drawing video = videoOf.get(e.file);
        if (video != null) info.addView(timelapseBlock(video), lp(-1, -2, 0, 0, 0, 12));

        TextView openIt = pill(activity.getString(R.string.saipen_drawing_open), ACCENT, Color.WHITE);
        openIt.setOnClickListener(v -> {
            if (activity.openDrawing(e.file)) dialog.dismiss();
        });
        info.addView(openIt, lp(-1, -2, 0, 0, 0, 8));

        LinearLayout shares = new LinearLayout(activity);
        TextView shareFile = pill(activity.getString(R.string.saipen_drawing_share), SURFACE, TEXT);
        TextView sharePic = pill(activity.getString(R.string.saipen_drawing_share_picture), SURFACE, TEXT);
        shareFile.setOnClickListener(v -> share(e, false, shareFile, sharePic));
        sharePic.setOnClickListener(v -> share(e, true, shareFile, sharePic));
        shares.addView(shareFile, lpw(0, -2, 1f, 0, 0, 4, 0));
        shares.addView(sharePic, lpw(0, -2, 1f, 4, 0, 0, 0));
        info.addView(shares, lp(-1, -2, 0, 0, 0, 0));
    }

    /** The timelapse: its last frame, playing in place under a hovering
     *  pen; a tap or Watch plays it in the device's player. */
    private View timelapseBlock(Drawing video) {
        LinearLayout tl = column();
        tl.setBackground(round(SURFACE, 14));
        tl.setPadding(dp(10), dp(10), dp(10), dp(10));

        HoverVideo preview = new HoverVideo(activity);
        preview.setBackground(round(0xFF24282C, 10));
        preview.setClipToOutline(true);
        preview.setVideo(TimelapseShelf.joined(video, 0));
        preview.setContentDescription(activity.getString(R.string.saipen_drawing_watch));
        preview.setOnClickListener(v -> TimelapsesDialog.play(activity, video));
        tl.addView(preview, lp(-1, dp(180), 0, 0, 0, 10));
        File still = TimelapseShelf.still(video);
        int px = dp(360);
        run(io, () -> {
            Bitmap b = closed || !still.isFile() ? null : decodeSampled(still, px);
            if (b != null) activity.runOnUiThread(() -> preview.setStill(b));
        });

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, 0, 0);
        LinearLayout words = column();
        TextView t = text(15, false, TEXT);
        t.setText(R.string.saipen_drawing_timelapse);
        words.addView(t);
        int frames = video.totalFrames();
        int seconds = Math.max(1, Math.round(frames / (float) TimelapseVideo.FPS));
        TextView d = text(12, false, TEXT3);
        d.setText(activity.getResources().getQuantityString(R.plurals.saipen_sessions, video.pieces.size(), video.pieces.size())
                  + " · " + activity.getString(R.string.saipen_seconds_short, seconds));
        words.addView(d, lp(-1, -2, 0, 2, 0, 0));
        TextView hint = text(12, false, TEXT3);
        hint.setText(R.string.saipen_drawing_timelapse_hint);
        words.addView(hint, lp(-1, -2, 0, 2, 0, 0));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        /* on the grey block, a button the colour of the page, so it shows */
        TextView watch = pill(activity.getString(R.string.saipen_drawing_watch), BG, TEXT);
        watch.setOnClickListener(v -> TimelapsesDialog.play(activity, video));
        row.addView(watch, lpw(-2, -2, 0, 10, 0, 0, 0));
        tl.addView(row);
        return tl;
    }

    /** A picture file decoded no bigger than about twice `maxPx` across. */
    private static Bitmap decodeSampled(File f, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    private void fact(LinearLayout into, int label, String value, boolean line) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        TextView l = text(14, false, TEXT2);
        l.setText(label);
        row.addView(l, lpw(-2, -2, 0, 0, 0, 12, 0));
        TextView v = text(14, false, TEXT);
        v.setText(value);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        into.addView(row);
        if (line) {
            View d = new View(activity);
            d.setBackgroundColor(0xFF3A4047);
            into.addView(d, new LinearLayout.LayoutParams(-1, 1));
        }
    }

    /* ---- Share: the .sai2 itself, or SAI's preview as a JPEG ---- */

    /** Copies the file inside the app (the FileProvider serves only the
     *  app's own files) and opens Android's share sheet. A big drawing takes
     *  a moment: the buttons are dimmed meanwhile. */
    private void share(Entry e, boolean picture, View... buttons) {
        if (sharing) return;
        sharing = true;
        for (View b : buttons) b.setAlpha(0.5f);
        Runnable done = () -> {
            sharing = false;
            for (View b : buttons) b.setAlpha(1f);
        };
        boolean queued = run(io, () -> {
            try {
                clearShared();
                File dir = new File(activity.getFilesDir(), SHARE_DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder for sharing");
                File out;
                if (picture) {
                    out = new File(dir, e.title + ".jpg");
                    byte[] jpeg = Sai2Thumb.jpeg(e.file);
                    try (OutputStream o = new FileOutputStream(out)) {
                        o.write(jpeg);
                    }
                }
                else {
                    out = new File(dir, e.file.getName());
                    Files.copy(e.file.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", out);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(picture ? "image/jpeg" : "application/octet-stream");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_TITLE, out.getName());
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.runOnUiThread(() -> {
                    done.run();
                    if (closed) return;
                    try {
                        activity.startActivity(Intent.createChooser(send, out.getName()));
                    }
                    catch (RuntimeException x) {
                        failed(x);
                    }
                });
            }
            catch (IOException | RuntimeException x) {
                Log.w(TAG, "drawings: share", x);
                activity.runOnUiThread(() -> {
                    done.run();
                    failed(x);
                });
            }
        });
        if (!queued) done.run();
    }

    /** The copies an earlier Share left: one at a time is enough. */
    private void clearShared() {
        File[] old = new File(activity.getFilesDir(), SHARE_DIR).listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private void failed(Exception x) {
        Toast.makeText(activity, activity.getString(R.string.saipen_drawing_share_failed, String.valueOf(x.getMessage())),
                       Toast.LENGTH_LONG).show();
    }

    /** False when the thread is gone (the window or the launcher closed). */
    private static boolean run(ExecutorService on, Runnable r) {
        try {
            on.execute(r);
            return true;
        }
        catch (RejectedExecutionException e) {
            return false;
        }
    }

    /* ---- the look: StatisticsDialog's ---- */

    /** The title row: "Drawings" and Close, or ‹, the drawing's name and Close. */
    private LinearLayout head(Entry e) {
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (e != null) {
            TextView back = pill("‹", SURFACE, TEXT);
            back.setContentDescription(activity.getString(R.string.saipen_back));
            back.setOnClickListener(v -> showCards());
            head.addView(back, lpw(-2, -2, 0, 0, 0, 10, 0));
        }
        TextView title = text(e == null ? 24 : 21, true, TEXT);
        title.setText(e == null ? activity.getString(R.string.saipen_section_drawings) : e.title);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = pill(activity.getString(R.string.saipen_close), SURFACE, TEXT);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        return head;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String label, int background, int color) {
        TextView t = text(14, true, color);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(round(background, 18));
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

    private LinearLayout.LayoutParams lpw(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
