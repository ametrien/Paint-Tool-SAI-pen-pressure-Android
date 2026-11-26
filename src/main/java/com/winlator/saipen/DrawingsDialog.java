package com.winlator.saipen;

import android.app.AlertDialog;
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
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

import androidx.preference.PreferenceManager;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
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
 * (HoverVideo); a tap plays it on the whole screen, inside the app. A card whose drawing
 * has a timelapse carries a small ▶. On a page, ‹ › or a sideways swipe
 * goes to the previous or next drawing of the cards, as in BrowseDialog.
 *
 * Holding a card selects it; then a tap adds or takes away more, and
 * Delete removes them for good, after asking (DeviceDrawings.delete: a copy
 * inside the app goes too, or it would come back). Timelapses stay.
 *
 * The picture is the small one SAI keeps in the file (Sai2Thumb, 256 pixels
 * at most); nothing else of the drawing is read. Nothing is renamed here
 * (the owner's choice, 2026-11-26).
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
    private static final int DANGER = 0xFFD32F2F;
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
    private Map<File, Drawing> videoOf = new HashMap<>();
    private String query = "";
    private String folder = null;
    private Sort sort = Sort.NEWEST;
    /** The kinds the artist hid (DrawingFormats); all shown by default. */
    private Set<String> hiddenFormats;
    /** A second tap on the chosen sort flips it: oldest first, Z to A, smallest first. */
    private boolean reversed = false;
    /** The drawing whose page is open; null on the cards. */
    private Entry shown;
    /** Cards held to be deleted; selecting while not empty. */
    private final Set<File> selected = new LinkedHashSet<>();
    private boolean deleting;
    /** The title row of the cards: it changes while selecting. */
    private LinearLayout cardsHead;
    /** The first card on the screen when a page opened: Back returns there. */
    private int cardsAt;
    private boolean sharing;
    /** Open in SAI was tapped: SAI is on its way. */
    private boolean starting;

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
            if (key != KeyEvent.KEYCODE_BACK || (shown == null && selected.isEmpty())) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (shown != null) showCards();
                else clearSelection();
            }
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
            if (shown != null) v.post(() -> { if (shown != null && !closed) showDrawing(shown, 0); });
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
        cardsHead = new LinearLayout(activity);
        top.addView(cardsHead, lp(-1, -2, 0, 0, 0, 10));
        fillCardsHead();

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
            /* hidden behind a small button: a wide field was easy to hit by
               accident with the pen, and it then waited for typing */
            search.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
            top.addView(search, lp(-1, -2, 0, 0, 0, 10));
            InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            search.setOnFocusChangeListener((v, has) -> {
                if (has || search.getText().length() > 0) return;
                search.setVisibility(View.GONE);
                if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
            });
            search.setOnEditorActionListener((v, action, ev) -> {
                if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
                search.clearFocus();
                return true;
            });

            LinearLayout sortRow = new LinearLayout(activity);
            sortRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout bar = new LinearLayout(activity);
            bar.setBackground(round(SURFACE, 10));
            bar.setPadding(dp(3), dp(3), dp(3), dp(3));
            addSort(bar, Sort.NEWEST);
            addSort(bar, Sort.NAME);
            addSort(bar, Sort.SIZE);
            sortRow.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView find = text(16, true, TEXT2);
            find.setText("⌕");
            find.setGravity(Gravity.CENTER);
            find.setBackground(round(SURFACE, 10));
            find.setContentDescription(activity.getString(R.string.saipen_drawings_search));
            find.setOnClickListener(v -> {
                if (search.getVisibility() == View.VISIBLE && search.getText().length() == 0) {
                    search.clearFocus();
                    return;
                }
                search.setVisibility(View.VISIBLE);
                search.requestFocus();
                if (ime != null) ime.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            });
            sortRow.addView(find, lpw(dp(48), -1, 0, 8, 0, 0, 0));
            top.addView(sortRow, lp(-1, -2, 0, 0, 0, 10));

            List<File> files = new ArrayList<>();
            for (Entry e : all) files.add(e.file);
            List<String> formats = DrawingFormats.present(files);
            if (formats.size() > 1) {
                HorizontalScrollView hs = new HorizontalScrollView(activity);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout chips = new LinearLayout(activity);
                chips.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = text(13, false, TEXT3);
                label.setText(R.string.saipen_formats_show);
                chips.addView(label, lp(-2, -2, 0, 0, 8, 0));
                for (String k : formats) chips.addView(formatChip(k), lp(-2, -2, 0, 0, 6, 0));
                hs.addView(chips);
                top.addView(hs, lp(-1, -2, 0, 0, 0, 10));
            }

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
        /* scrolling the cards puts the search's keyboard away */
        grid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {
                if (state != SCROLL_STATE_TOUCH_SCROLL) return;
                View f = page.findFocus();
                if (f == null) return;
                InputMethodManager im = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
                f.clearFocus();
            }
            @Override public void onScroll(android.widget.AbsListView v, int first, int count, int total) {}
        });
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

    /** The sort button: its name, and on the chosen one an arrow for the
     *  direction, ↓ for the usual one (newest, A first, largest), ↑ flipped. */
    private String sortLabel(Sort s, boolean chosen, boolean reversed) {
        int name = s == Sort.NAME ? R.string.saipen_sort_name : s == Sort.SIZE ? R.string.saipen_sort_size
                 : R.string.saipen_sort_date;
        return activity.getString(name) + (chosen ? (reversed ? "  ↑" : "  ↓") : "");
    }

    /** What the chosen order means, under the buttons: "Newest first". */
    private int sortMeaning() {
        switch (sort) {
            case NAME: return reversed ? R.string.saipen_sort_name_za : R.string.saipen_sort_name_az;
            case SIZE: return reversed ? R.string.saipen_sort_size_small : R.string.saipen_sort_size_large;
            default: return reversed ? R.string.saipen_sort_oldest : R.string.saipen_sort_newest;
        }
    }

    private void addSort(LinearLayout bar, Sort s) {
        TextView t = text(14, true, s == sort ? Color.WHITE : TEXT2);
        t.setText(sortLabel(s, s == sort, reversed));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        if (s == sort) t.setBackground(round(ACCENT, 8));
        t.setOnClickListener(v -> {
            if (sort == s) {
                reversed = !reversed;
            } else {
                sort = s;
                reversed = false;
            }
            for (int i = 0; i < bar.getChildCount(); i++) {
                TextView o = (TextView) bar.getChildAt(i);
                boolean on = o == t;
                o.setBackground(on ? round(ACCENT, 8) : null);
                o.setTextColor(on ? Color.WHITE : TEXT2);
                o.setText(sortLabel(Sort.values()[i], on, reversed));
            }
            refill();
            grid.setSelection(0);
        });
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    /** A kind of file: tap to hide it, tap again to show it. Remembered, and
     *  the start screen's row follows. */
    private TextView formatChip(String kind) {
        boolean on = !hiddenFormats().contains(kind);
        TextView c = text(13, on, on ? Color.WHITE : TEXT2);
        c.setText("." + kind);
        c.setSingleLine();
        c.setBackground(round(on ? ACCENT : SURFACE, 16));
        c.setPadding(dp(14), dp(7), dp(14), dp(7));
        c.setOnClickListener(v -> {
            Set<String> hidden = hiddenFormats();
            if (!hidden.remove(kind)) hidden.add(kind);
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putString(DrawingFormats.PREF_HIDDEN, DrawingFormats.join(hidden)).apply();
            boolean nowOn = !hidden.contains(kind);
            c.setBackground(round(nowOn ? ACCENT : SURFACE, 16));
            c.setTextColor(nowOn ? Color.WHITE : TEXT2);
            c.setTypeface(null, nowOn ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            refill();
            grid.setSelection(0);
            activity.drawingsChanged();
        });
        return c;
    }

    private Set<String> hiddenFormats() {
        if (hiddenFormats == null) hiddenFormats = DrawingFormats.parse(
            PreferenceManager.getDefaultSharedPreferences(activity).getString(DrawingFormats.PREF_HIDDEN, ""));
        return hiddenFormats;
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
        list = DrawingIndex.view(all, query, folder, sort, reversed, hiddenFormats());
        /* only what can be seen stays selected: Delete never takes a hidden one */
        Set<File> visible = new HashSet<>();
        for (Entry e : list) visible.add(e.file);
        if (selected.retainAll(visible) || !selected.isEmpty()) fillCardsHead();
        String n = activity.getResources().getQuantityString(R.plurals.saipen_n_drawings, list.size(), list.size());
        summary.setText(list.isEmpty() ? "" : n + " · " + Formatter.formatShortFileSize(activity, DrawingIndex.totalSize(list))
                        + " · " + activity.getString(sortMeaning())
                        + " · " + activity.getString(R.string.saipen_drawings_hold_hint));
        summary.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setText(all.isEmpty() ? R.string.saipen_drawings_none : R.string.saipen_drawings_none_found);
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** One card, reused for another drawing as the grid scrolls. */
    private final class Card {
        final LinearLayout view = column();
        /* the drawing's picture; with a timelapse, that plays under a hovering pen */
        final HoverVideo picture = new HoverVideo(activity);
        final TextView badge = text(11, true, TEXT);
        /* the file's kind, so .sai2, .png and .jpg side by side tell apart */
        final TextView kind = text(11, true, TEXT);
        final TextView check = text(13, true, Color.WHITE);
        final TextView name = text(14, true, TEXT), line = text(12, false, TEXT3), where = text(12, false, TEXT3);
        Entry entry;

        Card() {
            view.setTag(this);
            view.setBackground(round(SURFACE, 14));
            /* a tap shows, as on the start screen's rows */
            view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, round(Color.WHITE, 14)));
            view.setPadding(dp(8), dp(8), dp(8), dp(10));
            picture.setBackground(round(0xFF24282C, 10));
            picture.setClipToOutline(true);
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
            kind.setBackground(round(0xCC1B1E21, 10));
            kind.setPadding(dp(7), dp(2), dp(7), dp(3));
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            kp.setMargins(dp(6), dp(6), dp(6), dp(6));
            frame.addView(kind, kp);
            /* while selecting: an empty ring, or a tick on the accent colour */
            check.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.START);
            cp.setMargins(dp(6), dp(6), dp(6), dp(6));
            frame.addView(check, cp);
            view.addView(frame, lp(-1, dp(CARD_PICTURE_DP), 0, 0, 0, 8));
            for (TextView t : new TextView[] {name, line, where}) {
                t.setSingleLine();
                t.setEllipsize(TextUtils.TruncateAt.END);
            }
            where.setEllipsize(TextUtils.TruncateAt.START);
            view.addView(name);
            view.addView(line, lp(-1, -2, 0, 2, 0, 0));
            view.addView(where);
            view.setOnClickListener(v -> {
                if (entry == null) return;
                if (selected.isEmpty()) showDrawing(entry, 0);
                else toggle(entry);
            });
            view.setOnLongClickListener(v -> {
                if (entry != null) toggle(entry);
                return true;
            });
        }

        void showSelection() {
            boolean selecting = !selected.isEmpty(), on = entry != null && selected.contains(entry.file);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setText(on ? "✓" : "");
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(on ? ACCENT : 0x991B1E21);
            ring.setStroke(dp(2), on ? ACCENT : TEXT);
            check.setBackground(ring);
            GradientDrawable bg = round(SURFACE, 14);
            if (on) bg.setStroke(dp(2), ACCENT);
            view.setBackground(bg);
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
            kind.setText("." + DrawingFormats.of(e.file));
            Drawing video = videoOf.get(e.file);
            badge.setVisibility(video != null ? View.VISIBLE : View.GONE);
            picture.setVideo(video == null ? null : TimelapseShelf.joined(video, 0));
            picture.setStill(pictures.get(e.file));
            load(e.file, dp(CARD_PICTURE_DP));
            showSelection();
        }
    }

    /* ---- selecting and deleting ---- */

    private void toggle(Entry e) {
        if (deleting) return;
        if (!selected.remove(e.file)) selected.add(e.file);
        fillCardsHead();
        showSelectionOnCards();
    }

    private void clearSelection() {
        selected.clear();
        fillCardsHead();
        showSelectionOnCards();
    }

    private void showSelectionOnCards() {
        if (grid == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            Object tag = grid.getChildAt(i).getTag();
            if (tag instanceof Card) ((Card) tag).showSelection();
        }
    }

    /** "Drawings" and Close; while selecting, how many, Select all, Delete
     *  and Cancel. */
    private void fillCardsHead() {
        if (cardsHead == null) return;
        cardsHead.removeAllViews();
        cardsHead.setGravity(Gravity.CENTER_VERTICAL);
        if (selected.isEmpty()) {
            LinearLayout h = head(null);
            cardsHead.addView(h, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        TextView title = text(21, true, TEXT);
        title.setText(activity.getResources().getQuantityString(R.plurals.saipen_n_selected, selected.size(), selected.size()));
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        cardsHead.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        if (selected.size() < list.size()) {
            TextView all = pill(activity.getString(R.string.saipen_select_all), SURFACE, TEXT);
            all.setOnClickListener(v -> {
                for (Entry e : list) selected.add(e.file);
                fillCardsHead();
                showSelectionOnCards();
            });
            cardsHead.addView(all, lpw(-2, -2, 0, 8, 0, 0, 0));
        }
        TextView delete = pill(activity.getString(R.string.saipen_delete), DANGER, Color.WHITE);
        delete.setOnClickListener(v -> askDelete());
        cardsHead.addView(delete, lpw(-2, -2, 0, 8, 0, 0, 0));
        TextView cancel = pill(activity.getString(android.R.string.cancel), SURFACE, TEXT);
        cancel.setOnClickListener(v -> clearSelection());
        cardsHead.addView(cancel, lpw(-2, -2, 0, 8, 0, 0, 0));
    }

    private void askDelete() {
        if (deleting || selected.isEmpty()) return;
        List<File> files = new ArrayList<>(selected);
        int n = files.size();
        new AlertDialog.Builder(activity, SaiLauncherActivity.DIALOG_THEME)
            .setTitle(activity.getResources().getQuantityString(R.plurals.saipen_drawings_delete_q, n, n))
            .setMessage(R.string.saipen_drawings_delete_what)
            .setPositiveButton(R.string.saipen_delete, (d, w) -> delete(files))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Deletes them (and their copies inside the app), then shows what is left. */
    private void delete(List<File> files) {
        deleting = true;
        File root = activity.containerRootDir();
        List<File> inApp = root == null ? Collections.emptyList() : SaiPenSetup.inAppDrawingFolders(root);
        boolean queued = run(io, () -> {
            List<File> gone = new ArrayList<>();
            for (File f : files) if (DeviceDrawings.delete(f, inApp)) gone.add(f);
            String[] paths = new String[gone.size()];
            for (int i = 0; i < paths.length; i++) paths[i] = gone.get(i).getPath();
            /* the Gallery and My Files forget them too */
            if (paths.length > 0) android.media.MediaScannerConnection.scanFile(activity, paths, null, null);
            activity.runOnUiThread(() -> {
                deleting = false;
                Set<File> goneSet = new HashSet<>(gone);
                List<Entry> left = new ArrayList<>();
                for (Entry e : all) if (!goneSet.contains(e.file)) left.add(e);
                all = left;
                for (File f : gone) {
                    pictures.remove(f);
                    videoOf.remove(f);
                }
                selected.clear();
                int failed = files.size() - gone.size();
                String msg = activity.getResources().getQuantityString(R.plurals.saipen_drawings_deleted, gone.size(), gone.size());
                if (failed > 0) msg += ". " + activity.getString(R.string.saipen_drawings_not_deleted, failed);
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                activity.drawingsChanged();
                if (closed) return;
                if (folder != null && !DrawingIndex.folders(all).contains(folder)) folder = null;
                showCards();
            });
        });
        if (!queued) deleting = false;
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
                            ((Card) tag).picture.setStill(b);
                        }
                    }
                }
                if (shown != null && shown.file.equals(f) && pagePicture != null) pagePicture.setImageBitmap(b);
            });
        });
    }

    /* ---- one drawing's page ---- */

    private ImageView pagePicture;

    /** The next (+1) or previous (-1) drawing of the cards, in place of this
     *  one; Back still goes to the cards, scrolled to where it ended. */
    private void step(int dir) {
        int i = list.indexOf(shown), to = i + dir;
        if (i < 0 || to < 0 || to >= list.size()) return;
        cardsAt = to;
        showDrawing(list.get(to), dir);
    }

    /** A drawing's page; from: -1/+1 when it came by ‹ › or a swipe (it slides in), 0 otherwise. */
    private void showDrawing(Entry e, int from) {
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
        /* a sideways swipe anywhere on the page: the next or previous drawing */
        GestureDetector swipe = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(60) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                step(dx < 0 ? 1 : -1);
                return true;
            }
        });
        FrameLayout swipes = new FrameLayout(activity) {
            @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                swipe.onTouchEvent(ev);
                return super.dispatchTouchEvent(ev);
            }
        };
        swipes.addView(scroll);
        page.addView(swipes, new LinearLayout.LayoutParams(-1, -1));
        if (from != 0) {
            swipes.setTranslationX(from * dp(80));
            swipes.setAlpha(0f);
            swipes.animate().translationX(0).alpha(1f).setDuration(160).start();
        }

        col.addView(head(e), lp(-1, -2, 0, 0, 0, 12));

        int at = list.indexOf(e);
        if (at >= 0 && list.size() > 1) {
            LinearLayout nav = new LinearLayout(activity);
            nav.setGravity(Gravity.CENTER_VERTICAL);
            TextView prev = pill("‹", SURFACE, TEXT), next = pill("›", SURFACE, TEXT);
            prev.setAlpha(at > 0 ? 1f : 0.3f);
            next.setAlpha(at < list.size() - 1 ? 1f : 0.3f);
            prev.setContentDescription(activity.getString(R.string.saipen_drawing_previous));
            next.setContentDescription(activity.getString(R.string.saipen_drawing_next));
            prev.setOnClickListener(v -> step(-1));
            next.setOnClickListener(v -> step(1));
            TextView where = text(13, false, TEXT2);
            where.setText(activity.getString(R.string.saipen_drawing_n_of, at + 1, list.size()));
            where.setGravity(Gravity.CENTER);
            nav.addView(prev);
            nav.addView(where, new LinearLayout.LayoutParams(0, -2, 1f));
            nav.addView(next);
            col.addView(nav, lp(-1, -2, 0, 0, 0, 12));
        }

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
        fact(facts, R.string.saipen_drawing_size, Formatter.formatShortFileSize(activity, e.size), true);
        fact(facts, R.string.saipen_drawing_format, "." + DrawingFormats.of(e.file), true);
        /* time on it, counted from strokes while SAI showed it (CanvasTime) */
        CanvasTime.Canvas spent = CanvasTime.load(new File(activity.getFilesDir(), CanvasTime.FILE))
            .get(e.folder.isEmpty() ? e.file.getName() : e.folder + "/" + e.file.getName());
        if (spent == null || spent.strokes == 0) {
            fact(facts, R.string.saipen_drawing_time, activity.getString(R.string.saipen_drawing_time_none), false);
        }
        else {
            fact(facts, R.string.saipen_drawing_time, minutes(spent.seconds) + " · "
                 + activity.getResources().getQuantityString(R.plurals.saipen_strokes, spent.strokes, spent.strokes), true);
            DateFormat day = DateFormat.getDateInstance(DateFormat.MEDIUM);
            String firstDay = day.format(new Date(spent.first)), lastDay = day.format(new Date(spent.last));
            fact(facts, R.string.saipen_drawing_when, firstDay.equals(lastDay) ? firstDay
                 : activity.getString(R.string.saipen_date_range, firstDay, lastDay), false);
        }
        info.addView(facts, lp(-1, -2, 0, 0, 0, 12));

        Drawing video = videoOf.get(e.file);
        if (video != null) info.addView(timelapseBlock(video), lp(-1, -2, 0, 0, 0, 12));

        TextView openIt = pill(activity.getString(R.string.saipen_drawing_open), ACCENT, Color.WHITE);
        /* the window stays, saying so, until SAI is really on its way: the
           leftover timelapse frames are packed first, and that can take
           seconds of nothing happening on the start screen */
        openIt.setOnClickListener(v -> {
            if (starting) return;
            if (activity.openDrawing(e.file, () -> { if (dialog.isShowing()) dialog.dismiss(); })) {
                starting = true;
                openIt.setText(R.string.saipen_starting);
                openIt.setAlpha(0.7f);
            }
        });
        info.addView(openIt, lp(-1, -2, 0, 0, 0, 8));

        LinearLayout shares = new LinearLayout(activity);
        TextView shareFile = pill(activity.getString(R.string.saipen_drawing_share), SURFACE, TEXT);
        TextView sharePic = pill(activity.getString(R.string.saipen_drawing_share_picture), SURFACE, TEXT);
        shareFile.setOnClickListener(v -> share(e, false, shareFile, sharePic));
        sharePic.setOnClickListener(v -> share(e, true, shareFile, sharePic));
        shares.addView(shareFile, lpw(0, -2, 1f, 0, 0, 4, 0));
        shares.addView(sharePic, lpw(0, -2, 1f, 4, 0, 0, 0));
        /* .sai and .tga have no picture Android can show */
        String kind = DrawingFormats.of(e.file);
        if (kind.equals("sai") || kind.equals("tga")) sharePic.setVisibility(View.GONE);
        info.addView(shares, lp(-1, -2, 0, 0, 0, 0));
    }

    /** The timelapse: its last frame, playing in place under a hovering
     *  pen; a tap or Watch plays it on the whole screen (VideoPlayerDialog). */
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

    /** "1 h 5 min", "12 min", "under a minute". */
    private String minutes(double seconds) {
        int m = (int) Math.round(seconds / 60);
        if (m < 1) return activity.getString(R.string.saipen_under_a_minute);
        return m < 60 ? activity.getString(R.string.saipen_minutes_short, m)
                      : activity.getString(R.string.saipen_hours_minutes_short, m / 60, m % 60);
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
                String kind = DrawingFormats.of(e.file);
                boolean isPicture = kind.equals("png") || kind.equals("jpg") || kind.equals("bmp");
                String type = "application/octet-stream";
                if (picture && isPicture) {
                    /* already a picture: the file itself */
                    out = new File(dir, e.file.getName());
                    Files.copy(e.file.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    type = kind.equals("png") ? "image/png" : kind.equals("bmp") ? "image/bmp" : "image/jpeg";
                }
                else if (picture) {
                    out = new File(dir, e.title + ".jpg");
                    byte[] jpeg = kind.equals("sai2") ? Sai2Thumb.jpeg(e.file) : PsdThumb.jpeg(e.file);
                    try (OutputStream o = new FileOutputStream(out)) {
                        o.write(jpeg);
                    }
                    type = "image/jpeg";
                }
                else {
                    out = new File(dir, e.file.getName());
                    Files.copy(e.file.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", out);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(type);
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
