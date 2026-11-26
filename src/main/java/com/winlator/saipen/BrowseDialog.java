package com.winlator.saipen;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.R;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Brushes and textures": everything in SAI's settings folder, browsed like
 * a shop. All shows a shelf per kind; the chips go to one kind; a tap on
 * anything shows how it looks (a sample stroke, the paper, the shapes
 * stamped along a curve) and what SAI keeps about it, with paper to try it
 * on with the pen (TryPad for tools, PicturePad for the pictures). Colours
 * says in plain words which of SAI's colour files these are and that SAI
 * Pen only reads them; the scratch pad says what can be told of it
 * (SaiScratchPad). Settings lists the rest of the folder, down to every file.
 *
 * Nothing is deleted here (SAI keeps its own lists of these, so deleting
 * behind its back could break them). The one change: on a picture kind's
 * page, pictures can be hidden from SAI and shown again (BrushVisibility),
 * so a big collection does not make SAI's lists thousands long.
 *
 * A page opens as soon as the folder's names are read; the pictures fill in
 * as their cells are shown (BrowseThumbs).
 *
 * Colours are StatisticsDialog's (the launcher's AppThemeDark).
 */
final class BrowseDialog implements BrowseThumbs.Listener {
    static final int ALL = 0, TOOLS = 1, BRUSH_TEX = 2, PAPER = 3, SHAPES = 4, SCATTER = 5, COLOURS = 6, SETTINGS = 7;
    private static final String[] CHIPS = {"All", "Tools", "Brush textures", "Paper", "Brush shapes", "Scatter", "Colours", "Settings"};
    private static final String[] KIND_ONE = {"Brush texture", "Paper texture", "Brush shape", "Scatter shape"};
    private static final int SHELF = 12;

    private static final int BG = StatisticsDialog.BG, SURFACE = StatisticsDialog.SURFACE,
                             TEXT = StatisticsDialog.TEXT, TEXT2 = StatisticsDialog.TEXT2,
                             TEXT3 = StatisticsDialog.TEXT3, ACCENT = StatisticsDialog.ACCENT,
                             LINE = StatisticsDialog.LINE;

    /** The page for one of SettingsContents' kinds (a tile on the card). */
    static int pageFor(int kind) {
        switch (kind) {
            case SettingsContents.BRUSH_TEXTURES: return BRUSH_TEX;
            case SettingsContents.PAPER_TEXTURES: return PAPER;
            case SettingsContents.BRUSH_SHAPES:   return SHAPES;
            case SettingsContents.SCATTER:        return SCATTER;
            case SettingsContents.TOOLS:          return TOOLS;
            default:                              return ALL;
        }
    }

    private static int kindOf(int page) { return page - BRUSH_TEX; }

    private final Activity a;
    private final File settings;
    private final Runnable info, add, openFolder;
    private final Dialog dialog;
    private final FrameLayout content;
    private final BrowseThumbs thumbs;
    private final Deque<Runnable> pages = new ArrayDeque<>();
    private final int startPage;
    private final Runnable changed;
    private BrowseIndex idx;
    private FrameLayout body;
    private String query = "";
    /* a picture page: the hidden ones instead of SAI's, and picking several */
    private boolean hiddenView, selecting;
    private final java.util.Set<File> selected = new java.util.HashSet<>();
    private TextView selectedCount;
    private int currentPage = -1;

    /** openFolder may be null: no file manager on the device. changed: after
     *  pictures were hidden or shown (the card's counts). */
    static void show(Activity a, File settings, int page, Runnable info, Runnable add, Runnable openFolder,
                     Runnable changed) {
        new BrowseDialog(a, settings, page, info, add, openFolder, changed).open();
    }

    private BrowseDialog(Activity a, File settings, int page, Runnable info, Runnable add, Runnable openFolder,
                         Runnable changed) {
        this.a = a;
        this.changed = changed;
        this.settings = settings;
        this.startPage = page;
        this.info = info;
        this.add = add;
        this.openFolder = openFolder;
        dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        content = new FrameLayout(a);
        content.setBackgroundColor(BG);
        thumbs = BrowseThumbs.get(a);
    }

    private void open() {
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(BG));
            w.setStatusBarColor(BG); w.setNavigationBarColor(BG);
            w.setLayout(-1, -1);
        }
        dialog.setOnKeyListener((d, key, e) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        });
        dialog.setOnDismissListener(d -> thumbs.unlisten(this));
        thumbs.listen(this);
        if (thumbs.ready()) {
            idx = thumbs.index();
            top(startPage);
        } else {
            thumbs.start(settings, false);
            loading();
        }
        dialog.show();
    }

    /** BrowseThumbs moved on: once the folder is read, the page. */
    @Override public void changed() {
        if (idx != null || !thumbs.ready()) return;
        idx = thumbs.index();
        top(startPage);
    }

    /** Only while the folder's names are read, the first time: a moment. */
    private void loading() {
        LinearLayout root = column();
        root.setGravity(Gravity.CENTER);
        ProgressBar spin = new ProgressBar(a);
        root.addView(spin, lp(-2, -2, 0, 0, 0, 12));
        TextView t = text(15, false, TEXT2); t.setText("Reading SAI's settings folder…");
        root.addView(t);
        swap(root);
    }

    /* ---- pages and going back ---- */

    /** A chip's page: the history starts again there. */
    private void top(int page) {
        pages.clear();
        query = "";
        hiddenView = false;
        selecting = false;
        selected.clear();
        Runnable r = () -> topPage(page);
        pages.push(r);
        r.run();
    }

    /** A page further in (a detail, a list); back returns. */
    private void sub(Runnable r) {
        pages.push(r);
        r.run();
    }

    private void back() {
        if (selecting && pages.size() == 1) { selecting = false; selected.clear(); pages.peek().run(); }
        else if (pages.size() > 1) { pages.pop(); pages.peek().run(); }
        else if (idx != null && pages.size() == 1 && !query.isEmpty()) { query = ""; pages.peek().run(); }
        else dialog.dismiss();
    }

    private void topPage(int page) {
        currentPage = page;
        boolean search = page != COLOURS && page != SETTINGS;
        frame("Brushes and textures", page, search);
        fillBody(page);
    }

    private void fillBody(int page) {
        body.removeAllViews();
        switch (page) {
            case ALL:      allBody(); break;
            case TOOLS:    toolsBody(); break;
            case COLOURS:  coloursBody(); break;
            case SETTINGS: settingsBody(); break;
            default:       picturesBody(kindOf(page)); break;
        }
    }

    /** Header, search, chips (page >= 0), and an empty body. */
    private void frame(String title, int page, boolean search) {
        LinearLayout root = column();
        root.setPadding(dp(16), dp(14), dp(16), 0);

        LinearLayout head = row();
        if (pages.size() > 1) {
            TextView back = pill("‹", false); back.setOnClickListener(v -> back());
            head.addView(back, lp(-2, -2, 0, 0, 10, 0));
        }
        TextView t = text(21, true, TEXT); t.setText(title);
        t.setSingleLine(); t.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView i = pill("i", false); i.setOnClickListener(v -> info.run());
        head.addView(i, lp(-2, -2, 8, 0, 8, 0));
        TextView close = pill("Close", false); close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        root.addView(head, lp(-1, -2, 0, 0, 0, 10));

        if (search) {
            EditText s = new EditText(a);
            s.setHint(page == ALL ? "Search tools, textures and shapes" : "Search " + CHIPS[page].toLowerCase(Locale.ROOT));
            s.setSingleLine();
            s.setText(query);
            s.setTextColor(TEXT); s.setHintTextColor(TEXT3); s.setTextSize(15);
            s.setBackground(round(SURFACE, 10));
            s.setPadding(dp(14), dp(10), dp(14), dp(10));
            s.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence x, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence x, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable e) { query = e.toString(); fillBody(page); }
            });
            root.addView(s, lp(-1, -2, 0, 0, 0, 10));
        }
        if (page >= 0) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = row();
            for (int c = 0; c < CHIPS.length; c++) {
                final int to = c;
                TextView chip = text(13, c == page, c == page ? Color.WHITE : TEXT2);
                chip.setText(CHIPS[c]);
                chip.setBackground(round(c == page ? ACCENT : SURFACE, 16));
                chip.setPadding(dp(14), dp(7), dp(14), dp(7));
                chip.setOnClickListener(v -> top(to));
                chips.addView(chip, lp(-2, -2, 0, 0, 6, 0));
            }
            hs.addView(chips);
            root.addView(hs, lp(-1, -2, 0, 0, 0, 12));
        }
        body = new FrameLayout(a);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        swap(root);
    }

    /* ---- what is shown: items ---- */

    /** A tool (kind -1) or a picture of one of SettingsContents' four kinds. */
    private static final class Item {
        final int kind;
        final File file;
        final SaiSettingsFiles.Tool tool;
        /** A picture put away out of SAI's sight (BrushVisibility). */
        final boolean hidden;
        Item(int kind, File file, SaiSettingsFiles.Tool tool) { this(kind, file, tool, false); }
        Item(int kind, File file, SaiSettingsFiles.Tool tool, boolean hidden) {
            this.kind = kind; this.file = file; this.tool = tool; this.hidden = hidden;
        }
        String name() { return tool != null ? tool.name : SettingsContents.name(file); }
    }

    private List<Item> toolItems(List<SaiSettingsFiles.Tool> tools) {
        List<Item> out = new ArrayList<>();
        for (SaiSettingsFiles.Tool t : tools) if (BrowseIndex.matches(t.name, query)) out.add(new Item(-1, null, t));
        return out;
    }

    private List<Item> pictureItems(int kind) { return pictureItems(kind, false); }

    private List<Item> pictureItems(int kind, boolean hidden) {
        List<Item> out = new ArrayList<>();
        for (File f : (hidden ? idx.hidden : idx.pictures).get(kind)) {
            if (BrowseIndex.matches(SettingsContents.name(f), query)) out.add(new Item(kind, f, null, hidden));
        }
        return out;
    }

    private String detailLine(Item it, boolean withKind) {
        if (it.tool != null) return it.tool.draws() ? "size " + size(it.tool.size) : it.tool.kind;
        return withKind ? KIND_ONE[it.kind] : "";
    }

    private BrowseThumbs.Job job(Item it) {
        return it.tool != null ? thumbs.tool(idx.settings, it.tool) : thumbs.picture(it.kind, it.file);
    }

    /** One cell: the sample, the name, a short line. */
    private LinearLayout cell(List<Item> list, int i, boolean withKind) {
        LinearLayout c = column();
        int size = thumbs.cell;
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackground(round(SamplePreview.PAPER, 10));
        img.setClipToOutline(true);
        c.addView(img, new LinearLayout.LayoutParams(size, size));
        TextView n = text(12, false, TEXT);
        n.setMaxLines(1); n.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(n, lpPx(size, -2, 0, dp(4), 0, 0));
        TextView d = text(11, false, TEXT2);
        d.setMaxLines(1); d.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(d, lpPx(size, -2, 0, 0, 0, 0));
        bind(c, list, i, withKind);
        return c;
    }

    /** list: the items around it, for swiping on its detail page. */
    private void bind(LinearLayout c, List<Item> list, int i, boolean withKind) {
        Item it = list.get(i);
        thumbs.load(job(it), (ImageView) c.getChildAt(0));
        ((TextView) c.getChildAt(1)).setText(it.name());
        String d = detailLine(it, withKind);
        ((TextView) c.getChildAt(2)).setText(d);
        c.getChildAt(2).setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
        boolean picked = selecting && it.file != null && selected.contains(it.file);
        c.setBackground(picked ? round((ACCENT & 0x00FFFFFF) | 0x55000000, 12) : null);
        c.getChildAt(0).setAlpha(selecting && !picked ? 0.55f : 1f);
        ((TextView) c.getChildAt(1)).setText(picked ? "✓ " + it.name() : it.name());
        ((TextView) c.getChildAt(1)).setTextColor(picked ? Color.WHITE : TEXT);
        if (it.file != null && it.tool == null && currentPage >= BRUSH_TEX && currentPage <= SCATTER) {
            c.setOnLongClickListener(v -> {
                if (selecting || pages.size() != 1) return false;
                selecting = true;
                selected.add(it.file);
                pages.peek().run();
                return true;
            });
        }
        c.setOnClickListener(v -> {
            if (!selecting || it.file == null) { sub(() -> detail(list, i, 0)); return; }
            if (!selected.remove(it.file)) selected.add(it.file);
            bind(c, list, i, withKind);
            countSelected();
        });
    }

    /** A scrolling grid of many items (pictures can be thousands). */
    private View grid(List<Item> items, boolean withKind) {
        if (items.isEmpty()) return nothing(query.isEmpty() ? "None yet." : "Nothing called \"" + query.trim() + "\".");
        GridView g = new GridView(a);
        g.setColumnWidth(thumbs.cell);
        g.setNumColumns(GridView.AUTO_FIT);
        g.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
        g.setVerticalSpacing(dp(14));
        g.setHorizontalSpacing(dp(8));
        g.setClipToPadding(false);
        g.setPadding(0, 0, 0, dp(20));
        g.setSelector(new ColorDrawable(Color.TRANSPARENT));
        g.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                if (convert == null) return cell(items, i, withKind);
                bind((LinearLayout) convert, items, i, withKind);
                return convert;
            }
        });
        return g;
    }

    /** A few items in a wrapping block inside a page that scrolls as a whole. */
    private GridLayout block(List<Item> items, boolean withKind) {
        GridLayout g = new GridLayout(a);
        int width = a.getResources().getDisplayMetrics().widthPixels - dp(32);
        int cols = Math.max(2, (width + dp(8)) / (thumbs.cell + dp(8)));
        g.setColumnCount(cols);
        int gap = cols > 1 ? (width - cols * thumbs.cell) / (cols - 1) : 0;
        for (int i = 0; i < items.size(); i++) {
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.setMargins(0, 0, i % cols == cols - 1 ? 0 : gap, dp(14));
            g.addView(cell(items, i, withKind), p);
        }
        return g;
    }

    /* ---- All ---- */

    private void allBody() {
        if (!query.trim().isEmpty()) {
            List<Item> found = toolItems(idx.tools());
            for (int k = 0; k < idx.pictures.size(); k++) found.addAll(pictureItems(k));
            body.addView(grid(found, true));
            return;
        }
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = column();
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        List<SaiSettingsFiles.Tool> tools = idx.tools();
        shelf(list, "Tools", toolItems(tools), TOOLS);
        String[] names = {"Brush textures", "Paper textures", "Brush shapes", "Scatter shapes"};
        for (int k = 0; k < idx.pictures.size(); k++) {
            List<Item> items = new ArrayList<>();
            for (File f : idx.pictures.get(k)) items.add(new Item(k, f, null));
            shelf(list, names[k], items, BRUSH_TEX + k);
        }

        shelfHead(list, "Colours", -1, COLOURS);
        LinearLayout dots = row();
        List<Integer> recent = idx.recentColours;
        if (recent.isEmpty()) {
            TextView none = text(13, false, TEXT2); none.setText("No recent colours yet.");
            dots.addView(none);
        }
        for (int c : BrowseIndex.first(recent, 14)) dots.addView(dot(c, 28), lp(28, 28, 0, 0, 8, 0));
        dots.setOnClickListener(v -> top(COLOURS));
        list.addView(dots, lp(-1, -2, 0, 0, 0, 8));

        shelfHead(list, "Settings", -1, SETTINGS);
        LinearLayout box = box();
        settingRow(box, "Tool sets, preferences, backups", "And every file in SAI's settings folder", "›", v -> top(SETTINGS));
        list.addView(box);

        LinearLayout actions = column();
        TextView addPill = pill(a.getString(R.string.saipen_add_pack), true);
        addPill.setOnClickListener(v -> { dialog.dismiss(); add.run(); });
        actions.addView(addPill, lp(-1, -2, 0, 0, 0, 8));
        if (openFolder != null) {
            TextView folder = pill(a.getString(R.string.saipen_open_brushes), false);
            folder.setOnClickListener(v -> openFolder.run());
            actions.addView(folder, lp(-1, -2, 0, 0, 0, 0));
        }
        list.addView(actions, lp(-1, -2, 0, 20, 0, 0));
        body.addView(scroll);
    }

    /** The first few of all; a detail page swipes on through all of them. */
    private void shelf(LinearLayout list, String title, List<Item> items, int page) {
        shelfHead(list, title, items.size(), page);
        if (items.isEmpty()) {
            TextView none = text(13, false, TEXT2); none.setText("None yet.");
            list.addView(none, lp(-1, -2, 0, 0, 0, 8));
            return;
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(a);
        for (int i = 0; i < Math.min(SHELF, items.size()); i++) r.addView(cell(items, i, false), lp(-2, -2, 0, 0, 10, 0));
        hs.addView(r);
        list.addView(hs, lp(-1, -2, 0, 0, 0, 6));
    }

    private void shelfHead(LinearLayout list, String title, int count, int page) {
        LinearLayout h = row();
        TextView t = text(16, true, TEXT);
        t.setText(count >= 0 ? title + " · " + String.format(Locale.getDefault(), "%,d", count) : title);
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView all = text(13, false, ACCENT); all.setText("See all ›");
        all.setPadding(dp(8), dp(6), 0, dp(6));
        all.setOnClickListener(v -> top(page));
        h.addView(all);
        list.addView(h, lp(-1, -2, 0, 14, 0, 6));
    }

    /* ---- Tools ---- */

    private void toolsBody() {
        if (!query.trim().isEmpty()) { body.addView(grid(toolItems(idx.tools()), false)); return; }
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = column();
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        if (idx.toolSets.isEmpty()) list.addView(nothing("No tools yet: they come with your settings from a PC."));
        String kind = null;
        for (SaiSettingsFiles.ToolSet s : idx.toolSets) {
            if (!s.layerKind.equals(kind)) {
                kind = s.layerKind;
                TextView h = text(17, true, TEXT); h.setText(layerName(kind));
                list.addView(h, lp(-1, -2, 0, list.getChildCount() == 0 ? 0 : 18, 0, 2));
            }
            TextView sn = text(13, false, TEXT2);
            sn.setText(s.name + " · " + s.tools.size() + (s.tools.size() == 1 ? " tool" : " tools"));
            list.addView(sn, lp(-1, -2, 0, 8, 0, 8));
            List<Item> items = new ArrayList<>();
            for (SaiSettingsFiles.Tool t : s.tools) items.add(new Item(-1, null, t));
            list.addView(block(items, false));
        }
        TextView note = text(12, false, TEXT3);
        note.setText("Samples are close to how SAI draws, not exact. Tools are made and changed in SAI itself.");
        list.addView(note, lp(-1, -2, 0, 8, 0, 0));
        body.addView(scroll);
    }

    /* ---- pictures of one kind ---- */

    private void picturesBody(int kind) {
        if (hiddenView && idx.hidden.get(kind).isEmpty() && !selecting) hiddenView = false;
        List<Item> items = pictureItems(kind, hiddenView);
        LinearLayout col = column();
        col.addView(pictureBar(kind, items), lp(-1, -2, 0, 0, 0, 10));
        View g = items.isEmpty() && hiddenView && query.trim().isEmpty()
            ? nothing("Nothing hidden. Tap Select to choose the ones SAI shouldn't list.") : grid(items, false);
        col.addView(g, new LinearLayout.LayoutParams(-1, 0, 1f));
        body.addView(col);
    }

    /**
     * Above a picture kind's grid: In SAI or Hidden, and Select. Selecting:
     * how many, all (of those shown, the search too), none, and the one
     * thing to do with them.
     */
    private View pictureBar(int kind, List<Item> items) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row();
        hs.addView(r);
        if (!selecting) {
            int inSai = idx.pictures.get(kind).size(), away = idx.hidden.get(kind).size();
            TextView shown = toggle(String.format(Locale.getDefault(), "In SAI · %,d", inSai), !hiddenView);
            shown.setOnClickListener(v -> { hiddenView = false; pages.peek().run(); });
            r.addView(shown, lp(-2, -2, 0, 0, 6, 0));
            TextView hid = toggle(String.format(Locale.getDefault(), "Hidden · %,d", away), hiddenView);
            hid.setOnClickListener(v -> { hiddenView = true; pages.peek().run(); });
            r.addView(hid, lp(-2, -2, 0, 0, 16, 0));
            TextView sel = pill("Select", false);
            sel.setOnClickListener(v -> { selecting = true; selected.clear(); pages.peek().run(); });
            r.addView(sel);
            return hs;
        }
        TextView done = pill("Done", false);
        done.setOnClickListener(v -> { selecting = false; selected.clear(); pages.peek().run(); });
        r.addView(done, lp(-2, -2, 0, 0, 10, 0));
        selectedCount = text(14, false, TEXT2);
        r.addView(selectedCount, lp(-2, -2, 0, 0, 12, 0));
        countSelected();
        TextView all = pill("All", false);
        all.setOnClickListener(v -> { for (Item it : items) selected.add(it.file); pages.peek().run(); });
        r.addView(all, lp(-2, -2, 0, 0, 6, 0));
        TextView none = pill("None", false);
        none.setOnClickListener(v -> { selected.clear(); pages.peek().run(); });
        r.addView(none, lp(-2, -2, 0, 0, 12, 0));
        boolean hide = !hiddenView;
        TextView act = pill(hide ? "Hide from SAI" : "Show in SAI", true);
        act.setOnClickListener(v -> {
            List<File> files = new ArrayList<>();
            for (Item it : items) if (selected.contains(it.file)) files.add(it.file);
            if (files.isEmpty()) return;
            change(kind, files, hide, () -> pages.peek().run());
        });
        r.addView(act);
        return hs;
    }

    private void countSelected() {
        if (selectedCount != null) selectedCount.setText(selected.isEmpty() ? "Tap to pick"
            : String.format(Locale.getDefault(), "%,d picked", selected.size()));
    }

    private TextView toggle(String label, boolean on) {
        TextView t = text(13, on, on ? Color.WHITE : TEXT2);
        t.setText(label);
        t.setBackground(round(on ? ACCENT : SURFACE, 16));
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        return t;
    }

    /* ---- hiding and showing ---- */

    /** Hides (or shows) pictures, then: after. Hiding ones a tool uses asks first. */
    private void change(int kind, List<File> files, boolean hide, Runnable after) {
        List<String> users = hide ? toolsUsing(kind, files) : new ArrayList<>();
        if (users.isEmpty()) { move(files, hide, after); return; }
        String names = TextUtils.join(", ", BrowseIndex.first(users, 6)) + (users.size() > 6 ? "…" : "");
        new android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Your tools use some of these")
            .setMessage((users.size() == 1 ? "1 tool uses" : users.size() + " tools use") + " what you picked: " + names
                + ".\n\nHidden, SAI can't find them, and those tools may draw without them until you show them again.")
            .setPositiveButton("Hide anyway", (d, w) -> move(files, hide, after))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** The tools whose texture or shape is one of these files. */
    private List<String> toolsUsing(int kind, List<File> files) {
        List<String> out = new ArrayList<>();
        if (kind != SettingsContents.BRUSH_TEXTURES && kind != SettingsContents.BRUSH_SHAPES) return out;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (File f : files) names.add(SettingsContents.name(f).toLowerCase(Locale.ROOT));
        for (SaiSettingsFiles.Tool t : idx.tools()) {
            String n = (kind == SettingsContents.BRUSH_TEXTURES ? t.texture : t.shape).toLowerCase(Locale.ROOT);
            if (names.contains(n) && !out.contains(t.name)) out.add(t.name);
        }
        return out;
    }

    private void move(List<File> files, boolean hide, Runnable after) {
        android.app.AlertDialog wait = new android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(hide ? "Hiding…" : "Showing…").setCancelable(false).show();
        new Thread(() -> {
            BrushVisibility.Result r = hide ? BrushVisibility.hide(settings, files) : BrushVisibility.show(settings, files);
            BrowseIndex fresh = BrowseIndex.read(settings);
            a.runOnUiThread(() -> {
                wait.dismiss();
                idx = fresh;
                selecting = false;
                selected.clear();
                thumbs.start(settings, false);
                if (changed != null) changed.run();
                if (dialog.isShowing()) after.run();
                moved(r, hide);
            });
        }, "brush-visibility").start();
    }

    private void moved(BrushVisibility.Result r, boolean hide) {
        String done = r.moved == 0 ? "" : String.format(Locale.getDefault(), hide
            ? "Hidden from SAI: %,d. SAI's lists change the next time it starts."
            : "Back in SAI: %,d. They show up the next time SAI starts.", r.moved);
        if (r.taken.isEmpty() && r.failed.isEmpty()) {
            android.widget.Toast.makeText(a, done, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder b = new StringBuilder(done);
        if (!r.taken.isEmpty()) {
            if (b.length() > 0) b.append("\n\n");
            b.append(hide ? "Stayed in SAI, because one of the same name is hidden already: "
                          : "Stayed hidden, because SAI has one of the same name now: ")
             .append(TextUtils.join(", ", BrowseIndex.first(r.taken, 8))).append(r.taken.size() > 8 ? "…" : "");
        }
        if (!r.failed.isEmpty()) {
            if (b.length() > 0) b.append("\n\n");
            b.append("Could not be moved: ").append(TextUtils.join(", ", BrowseIndex.first(r.failed, 8)))
             .append(r.failed.size() > 8 ? "…" : "");
        }
        new android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(b.toString()).setPositiveButton(android.R.string.ok, null).show();
    }

    /* ---- Colours ---- */

    private void coloursBody() {
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = column();
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        TextView intro = text(13, false, TEXT2);
        intro.setText(a.getString(R.string.saipen_colours_intro));
        list.addView(intro, lp(-1, -2, 0, 0, 0, 4));

        heading(list, "Recent colours", a.getString(R.string.saipen_colours_recent_about));
        if (idx.recentColours.isEmpty()) list.addView(nothing("None yet."));
        else list.addView(swatchGrid(idx.recentColours));

        heading(list, "Swatches", a.getString(R.string.saipen_colours_swatches_about));
        if (idx.swatches.isEmpty()) list.addView(nothing("None yet."));
        boolean anyColours = false;
        for (BrowseIndex.Swatch s : idx.swatches) {
            TextView n = text(14, !s.colours.isEmpty(), s.colours.isEmpty() ? TEXT2 : TEXT);
            n.setText(s.colours.isEmpty() ? a.getString(R.string.saipen_colours_swatch_empty, s.name)
                : a.getString(R.string.saipen_colours_swatch_n, s.name, s.colours.size()));
            list.addView(n, lp(-1, -2, 0, 4, 0, 6));
            if (!s.colours.isEmpty()) { list.addView(swatchGrid(s.colours)); anyColours = true; }
        }
        TextView edit = text(12, false, TEXT3);
        edit.setText(a.getString(anyColours ? R.string.saipen_colours_swatch_guess : R.string.saipen_colours_no_edit));
        list.addView(edit, lp(-1, -2, 0, 6, 0, 0));

        heading(list, a.getString(R.string.saipen_scratch_title), a.getString(R.string.saipen_scratch_about));
        LinearLayout box = box();
        if (idx.scratchPads.isEmpty()) settingRow(box, a.getString(R.string.saipen_scratch_none), null, null, null);
        for (File f : idx.scratchPads) {
            settingRow(box, SettingsContents.bareName(f), size(f.length()) + " · " + date(f.lastModified()), "›",
                       v -> sub(() -> scratchPage(f)));
        }
        list.addView(box);
        body.addView(scroll);
    }

    /** One scratch pad: what can be told of it, and why it can't be shown. */
    private void scratchPage(File f) {
        frame(a.getString(R.string.saipen_scratch_title) + " · " + SettingsContents.bareName(f), -1, false);
        ScrollView scroll = new ScrollView(a);
        LinearLayout col = column();
        col.setPadding(0, 0, 0, dp(24));
        scroll.addView(col);
        SaiScratchPad.Info info = SaiScratchPad.read(f);
        TextView t = text(14, false, TEXT);
        t.setText(a.getString(info.state == SaiScratchPad.EMPTY ? R.string.saipen_scratch_empty
            : info.state == SaiScratchPad.DRAWN ? R.string.saipen_scratch_drawn : R.string.saipen_scratch_unreadable));
        col.addView(t, lp(-1, -2, 0, 0, 0, 12));
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[] {a.getString(R.string.saipen_scratch_state), a.getString(
            info.state == SaiScratchPad.EMPTY ? R.string.saipen_scratch_state_empty
            : info.state == SaiScratchPad.DRAWN ? R.string.saipen_scratch_state_drawn : R.string.saipen_scratch_state_unknown)});
        if (!info.layers.isEmpty()) {
            facts.add(new String[] {a.getString(R.string.saipen_scratch_layers),
                info.layers.size() + " · " + TextUtils.join(", ", info.layers)});
        }
        facts.add(new String[] {a.getString(R.string.saipen_scratch_file), relative(f) + " · " + size(f.length())});
        facts.add(new String[] {a.getString(R.string.saipen_scratch_changed), date(f.lastModified())});
        col.addView(facts(facts));
        body.addView(scroll);
    }

    private View swatchGrid(List<Integer> colours) {
        GridLayout g = new GridLayout(a);
        int width = a.getResources().getDisplayMetrics().widthPixels - dp(32);
        int each = dp(64);
        int cols = Math.max(3, (width + dp(8)) / (each + dp(8)));
        g.setColumnCount(cols);
        for (int i = 0; i < colours.size(); i++) {
            int c = colours.get(i);
            LinearLayout cell = column();
            View sw = new View(a);
            GradientDrawable d = new GradientDrawable(); d.setColor(c); d.setCornerRadius(dp(10)); d.setStroke(1, LINE);
            sw.setBackground(d);
            cell.addView(sw, lp(64, 44, 0, 0, 0, 0));
            TextView hex = text(11, false, TEXT2);
            hex.setText(String.format("#%06X", c & 0xFFFFFF));
            cell.addView(hex, lp(-2, -2, 0, 3, 0, 0));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.setMargins(0, 0, dp(8), dp(10));
            g.addView(cell, p);
        }
        return g;
    }

    /* ---- Settings: the whole folder in plain words ---- */

    private void settingsBody() {
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = column();
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        TextView intro = text(13, false, TEXT2);
        intro.setText("Everything in SAI's settings folder on this device: Documents/" + SaiPenSetup.SETTINGS_PATH
            + ". All of it comes along when you bring your settings from a PC.");
        list.addView(intro, lp(-1, -2, 0, 0, 0, 4));

        section(list, "Your tools");
        LinearLayout box = box();
        String sets = idx.toolSets.isEmpty() ? "None yet"
            : idx.toolSets.get(0).name + " · " + idx.toolSets.get(0).tools.size() + " tools"
              + (idx.toolSets.size() > 1 ? ", and " + (idx.toolSets.size() - 1) + " more sets" : "");
        settingRow(box, "Tool sets", sets, "›", v -> top(TOOLS));
        settingRow(box, "Starting values of textures and shapes",
            idx.withSettings.isEmpty() ? "None" : idx.withSettings.size() + " pictures come with them (NAME.ini)",
            idx.withSettings.isEmpty() ? null : "›", idx.withSettings.isEmpty() ? null : v -> sub(this::withSettingsPage));
        list.addView(box);

        section(list, "Your colours");
        box = box();
        settingRow(box, "Recent colours", idx.recentColours.size() + " colours", "›", v -> top(COLOURS));
        int swatchColours = 0;
        for (BrowseIndex.Swatch s : idx.swatches) swatchColours += s.colours.size();
        settingRow(box, "Swatches", idx.swatches.isEmpty() ? "None" : idx.swatches.size() + " · "
            + (swatchColours == 0 ? "empty" : swatchColours + " colours"), "›", v -> top(COLOURS));
        settingRow(box, a.getString(R.string.saipen_scratch_title), idx.scratchPads.isEmpty() ? "None" : idx.scratchPads.size() + "", "›",
            idx.scratchPads.size() == 1 ? v -> sub(() -> scratchPage(idx.scratchPads.get(0))) : v -> top(COLOURS));
        list.addView(box);

        section(list, "How SAI works and looks");
        box = box();
        settingRow(box, "SAI's preferences",
            "Shortcut keys, canvas sizes and options. SAI keeps them scrambled (settings2.ssd), so they can't be shown here"
            + (idx.preferencesTime > 0 ? ". Changed " + date(idx.preferencesTime) : ""), null, null);
        settingRow(box, "Window colours",
            "SAI's white or dark look. Change it in SAI: Window > Window Color Scheme", null, null);
        list.addView(box);

        section(list, "Safety copies");
        box = box();
        settingRow(box, "SAI's own backups", idx.saiBackups.isEmpty() ? "None"
            : "Older copies SAI keeps in bak folders · " + idx.saiBackups.size() + " places",
            idx.saiBackups.isEmpty() ? null : "›", idx.saiBackups.isEmpty() ? null
            : v -> sub(() -> backupsPage("SAI's own backups", "SAI keeps an older copy here when it saves a list.", idx.saiBackups)));
        settingRow(box, "Before your imports", idx.importBackups.isEmpty()
            ? "None yet: made when you bring settings from a PC"
            : "Kept by SAI Pen in SAIv2 · " + idx.importBackups.size(),
            idx.importBackups.isEmpty() ? null : "›", idx.importBackups.isEmpty() ? null
            : v -> sub(() -> backupsPage("Before your imports", "Your settings as they were before each import from a PC, next to the settings folder.", idx.importBackups)));
        list.addView(box);

        TextView every = pill("Show every file in the folder", false);
        every.setOnClickListener(v -> sub(this::filesPage));
        list.addView(every, lp(-1, -2, 0, 18, 0, 0));
        body.addView(scroll);
    }

    private void withSettingsPage() {
        frame("Starting values", -1, false);
        List<Item> items = new ArrayList<>();
        for (File f : idx.withSettings) {
            int kind = 0;
            for (int k = 0; k < idx.pictures.size(); k++) if (idx.pictures.get(k).contains(f)) kind = k;
            items.add(new Item(kind, f, null));
        }
        body.addView(grid(items, true));
    }

    private void backupsPage(String title, String about, List<SaiSettingsFiles.Backup> list) {
        frame(title, -1, false);
        ScrollView scroll = new ScrollView(a);
        LinearLayout col = column();
        col.setPadding(0, 0, 0, dp(24));
        scroll.addView(col);
        TextView t = text(13, false, TEXT2); t.setText(about + " SAI Pen only shows them; nothing is restored from here.");
        col.addView(t, lp(-1, -2, 0, 0, 0, 10));
        LinearLayout box = box();
        for (SaiSettingsFiles.Backup b : list) {
            settingRow(box, b.path, b.files + (b.files == 1 ? " file" : " files")
                + (b.lastModified > 0 ? " · " + date(b.lastModified) : ""), null, null);
        }
        col.addView(box);
        body.addView(scroll);
    }

    private void filesPage() {
        frame("Every file", -1, false);
        TextView wait = text(13, false, TEXT2); wait.setText("Listing…");
        body.addView(wait);
        FrameLayout target = body;
        new Thread(() -> {
            List<SaiSettingsFiles.Entry> all = SaiSettingsFiles.allFiles(settings);
            long total = 0;
            for (SaiSettingsFiles.Entry e : all) total += e.size;
            long sum = total;
            a.runOnUiThread(() -> {
                if (body != target) return;
                target.removeAllViews();
                LinearLayout col = column();
                TextView t = text(13, false, TEXT2);
                t.setText(String.format(Locale.getDefault(), "%,d files, %s in all.", all.size(), size(sum)));
                col.addView(t, lp(-1, -2, 0, 0, 0, 8));
                ListView lv = new ListView(a);
                lv.setDivider(new ColorDrawable(LINE));
                lv.setDividerHeight(1);
                lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
                lv.setAdapter(new BaseAdapter() {
                    @Override public int getCount() { return all.size(); }
                    @Override public Object getItem(int i) { return all.get(i); }
                    @Override public long getItemId(int i) { return i; }
                    @Override public View getView(int i, View convert, ViewGroup parent) {
                        LinearLayout r = (LinearLayout) convert;
                        if (r == null) {
                            r = row();
                            r.setPadding(0, dp(9), 0, dp(9));
                            TextView p = text(13, false, TEXT);
                            p.setSingleLine(); p.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                            r.addView(p, new LinearLayout.LayoutParams(0, -2, 1f));
                            r.addView(text(12, false, TEXT2), lp(-2, -2, 12, 0, 0, 0));
                        }
                        ((TextView) r.getChildAt(0)).setText(all.get(i).path);
                        ((TextView) r.getChildAt(1)).setText(size(all.get(i).size));
                        return r;
                    }
                });
                col.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
                target.addView(col);
            });
        }, "browse-files").start();
    }

    /* ---- one thing in detail ---- */

    /** The next or previous item, in place of this one (back still returns to the list). */
    private void step(List<Item> list, int i, int dir) {
        int to = i + dir;
        if (to < 0 || to >= list.size()) return;
        pages.pop();
        pages.push(() -> detail(list, to, 0));
        detail(list, to, dir);
    }

    /** One item of list; from: -1/+1 when it came by a swipe (it slides in), 0 otherwise. */
    private void detail(List<Item> list, int i, int from) {
        Item it = list.get(i);
        frame(it.name(), -1, false);
        ScrollView scroll = new ScrollView(a);
        LinearLayout col = column();
        col.setPadding(0, 0, 0, dp(24));
        scroll.addView(col);

        if (list.size() > 1) {
            LinearLayout nav = row();
            TextView prev = pill("‹", false), next = pill("›", false);
            prev.setAlpha(i > 0 ? 1f : 0.3f);
            next.setAlpha(i < list.size() - 1 ? 1f : 0.3f);
            prev.setOnClickListener(v -> step(list, i, -1));
            next.setOnClickListener(v -> step(list, i, 1));
            TextView where = text(13, false, TEXT2);
            where.setText(String.format(Locale.getDefault(), "%,d of %,d · swipe for the next", i + 1, list.size()));
            where.setGravity(Gravity.CENTER);
            nav.addView(prev);
            nav.addView(where, new LinearLayout.LayoutParams(0, -2, 1f));
            nav.addView(next);
            col.addView(nav, lp(-1, -2, 0, 0, 0, 10));
        }

        TextView kind = text(13, false, TEXT2);
        col.addView(kind, lp(-1, -2, 0, 0, 0, 10));

        int w = a.getResources().getDisplayMetrics().widthPixels - dp(32);
        int h = Math.min(dp(240), Math.round(w * 0.55f));
        ImageView big = new ImageView(a);
        big.setScaleType(ImageView.ScaleType.FIT_XY);
        big.setBackground(round(SamplePreview.PAPER, 14));
        big.setClipToOutline(true);
        col.addView(big, new LinearLayout.LayoutParams(w, h));
        TextView sampleNote = text(11, false, TEXT3);
        col.addView(sampleNote, lp(-1, -2, 0, 4, 0, 12));

        if (it.tool != null) {
            SaiSettingsFiles.Tool t = it.tool;
            kind.setText("Tool · " + layerName(t.layerKind) + (t.linkedFrom != null ? " (the same tool as in " + layerName(t.linkedFrom).toLowerCase(Locale.ROOT) + ")" : ""));
            if (t.draws()) {
                thumbs.make(() -> SamplePreview.tool(idx.settings, t, thumbs.shape(), w, h), big);
                sampleNote.setText("A sample stroke, light to heavy to light: close to how SAI draws it, not exact.");
                col.addView(shapeChoice(() -> thumbs.make(() -> SamplePreview.tool(idx.settings, t, thumbs.shape(), w, h), big)),
                            lp(-2, -2, 0, 0, 0, 12));
                tryPad(col, t, w);
                List<String[]> facts = new ArrayList<>();
                facts.add(new String[] {"Size", size(t.size) + " px"});
                facts.add(new String[] {"Density", t.density + "%"});
                facts.add(new String[] {"Hardness", t.hardness + ""});
                facts.add(new String[] {"Brush shape", t.shape.isEmpty() ? "Plain" : t.shape});
                facts.add(new String[] {"Texture", t.texture.isEmpty() ? "None" : t.texture + " · " + t.textureStrength + "%"});
                facts.add(new String[] {"Kind", t.kind});
                col.addView(facts(facts));
                usedPictures(col, t);
            } else {
                big.setVisibility(View.GONE);
                sampleNote.setText("Not a brush (" + t.kind + "): nothing to draw a sample of.");
            }
            return_(scroll, list, i, from);
            return;
        }

        File f = it.file;
        kind.setText(KIND_ONE[it.kind] + (it.hidden ? " · hidden from SAI" : ""));
        thumbs.make(() -> BrowseThumbs.sample(it.kind, f, w, h), big);
        sampleNote.setText(it.kind == SettingsContents.PAPER_TEXTURES ? a.getString(R.string.saipen_sample_paper_texture)
            : it.kind == SettingsContents.BRUSH_TEXTURES ? "A stroke painted through this texture: close to SAI, not exact."
            : "Stamped along a stroke with its starting values: close to SAI, not exact.");
        picturePad(col, it, w);

        LinearLayout pic = row();
        ImageView raw = new ImageView(a);
        raw.setScaleType(ImageView.ScaleType.FIT_CENTER);
        raw.setBackground(round(0xFFE4E6E8, 10));
        raw.setClipToOutline(true);
        pic.addView(raw, lp(72, 72, 0, 0, 12, 0));
        thumbs.make(() -> SamplePreview.decode(f, dp(72)), raw);
        LinearLayout words = column();
        TextView what = text(14, true, TEXT); what.setText("The picture itself");
        TextView dims = text(12, false, TEXT2);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        dims.setText((o.outWidth > 0 ? o.outWidth + " × " + o.outHeight + " · " : "") + relative(f) + " · " + size(f.length()));
        words.addView(what); words.addView(dims);
        pic.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(pic, lp(-1, -2, 0, 0, 0, 14));

        Map<String, String> ini = SaiSettingsFiles.ini(SaiSettingsFiles.iniOf(f));
        TextView sv = text(15, true, TEXT); sv.setText("Starting values");
        col.addView(sv, lp(-1, -2, 0, 4, 0, 6));
        if (ini.isEmpty()) {
            TextView none = text(13, false, TEXT2);
            none.setText("None: SAI uses its own defaults when you pick it.");
            col.addView(none);
        } else {
            List<String[]> facts = new ArrayList<>();
            for (Map.Entry<String, String> e : ini.entrySet()) facts.add(new String[] {words(e.getKey()), e.getValue()});
            col.addView(facts(facts));
        }
        TextView flip = pill(it.hidden ? "Show in SAI" : "Hide from SAI", false);
        flip.setOnClickListener(v -> change(it.kind, java.util.Collections.singletonList(f), !it.hidden, this::back));
        col.addView(flip, lp(-1, -2, 0, 14, 0, 0));
        usedBy(col, it);
        return_(scroll, list, i, from);
    }

    /** The page, with a sideways swipe anywhere on it to the next or previous item. */
    private void return_(ScrollView scroll, List<Item> list, int i, int from) {
        android.view.GestureDetector swipe = new android.view.GestureDetector(a, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(60) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                step(list, i, dx < 0 ? 1 : -1);
                return true;
            }
        });
        FrameLayout page = new FrameLayout(a) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                /* drawing on a "Try it" pad is not a swipe to the next page */
                if (!TryPad.drawing) swipe.onTouchEvent(e);
                return super.dispatchTouchEvent(e);
            }
        };
        page.addView(scroll);
        body.addView(page);
        if (from != 0) {
            page.setTranslationX(from * dp(80));
            page.setAlpha(0f);
            page.animate().translationX(0).alpha(1f).setDuration(160).start();
        }
    }

    /** "Try it": paper to draw on with the pen, with this tool, and Clear. */
    private void tryPad(LinearLayout col, SaiSettingsFiles.Tool t, int w) {
        LinearLayout head = row();
        TextView title = text(15, true, TEXT); title.setText("Try it");
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TryPad pad = new TryPad(a, idx.settings, t);
        TextView clear = text(13, false, TEXT2); clear.setText("Clear");
        clear.setBackground(round(SURFACE, 16));
        clear.setPadding(dp(14), dp(7), dp(14), dp(7));
        clear.setOnClickListener(v -> pad.clear());
        head.addView(clear);
        col.addView(head, lp(-1, -2, 0, 2, 0, 6));
        pad.setBackground(round(SamplePreview.PAPER, 14));
        pad.setClipToOutline(true);
        col.addView(pad, new LinearLayout.LayoutParams(w, Math.min(dp(320), Math.round(w * 0.6f))));
        TextView note = text(11, false, TEXT3);
        note.setText("Draw with the pen: its pressure works as in this tool's settings, at the tool's size. Close to SAI, not exact.");
        col.addView(note, lp(-1, -2, 0, 4, 0, 14));
    }

    /** "Try it" for a picture: paper to draw on with the pen the way SAI
     *  would use it (PicturePad), Small, Medium, Large and Clear. */
    private void picturePad(LinearLayout col, Item it, int w) {
        String folder = folderOf(it);
        PicturePad pad = new PicturePad(a, it.kind, it.file, SaiSettingsFiles.ini(SaiSettingsFiles.iniOf(it.file)), folder);
        LinearLayout head = row();
        TextView title = text(15, true, TEXT); title.setText(a.getString(R.string.saipen_try_it));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        int[] labels = {R.string.saipen_try_small, R.string.saipen_try_medium, R.string.saipen_try_large};
        TextView[] chips = new TextView[labels.length];
        for (int s = 0; s < labels.length; s++) {
            final int size = s;
            TextView chip = text(13, false, TEXT2);
            chip.setText(a.getString(labels[s]));
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setOnClickListener(v -> {
                pad.size(size);
                for (int k = 0; k < chips.length; k++) shapeChip(chips[k], k == size);
            });
            chips[s] = chip;
            shapeChip(chip, s == PicturePad.MEDIUM);
            head.addView(chip, lp(-2, -2, 0, 0, 6, 0));
        }
        TextView clear = text(13, false, TEXT2); clear.setText(a.getString(R.string.saipen_try_clear));
        clear.setBackground(round(SURFACE, 16));
        clear.setPadding(dp(14), dp(7), dp(14), dp(7));
        clear.setOnClickListener(v -> pad.clear());
        head.addView(clear, lp(-2, -2, 6, 0, 0, 0));
        col.addView(head, lp(-1, -2, 0, 2, 0, 6));
        pad.setBackground(round(SamplePreview.PAPER, 14));
        pad.setClipToOutline(true);
        col.addView(pad, new LinearLayout.LayoutParams(w, Math.min(dp(320), Math.round(w * 0.6f))));
        TextView note = text(11, false, TEXT3);
        note.setText(a.getString(padNote(it.kind, folder)));
        col.addView(note, lp(-1, -2, 0, 4, 0, 14));
    }

    private static String folderOf(Item it) { return SettingsContents.folderOf(it.kind, it.file); }

    static int padNote(int kind, String folder) {
        switch (kind) {
            case SettingsContents.BRUSH_TEXTURES: return R.string.saipen_try_brush_texture;
            case SettingsContents.PAPER_TEXTURES: return R.string.saipen_try_paper_texture;
            case SettingsContents.SCATTER:        return R.string.saipen_try_scatter;
            default:
                if (folder.endsWith("bristle")) return R.string.saipen_try_bristle;
                if (folder.endsWith("blotmap")) return R.string.saipen_try_blotmap;
                return R.string.saipen_try_brshape;
        }
    }

    /** Line · S-curve · Heart: the tools' sample shape, here and in the
     *  cells, kept for next time; `redraw` draws this page's sample again. */
    private LinearLayout shapeChoice(Runnable redraw) {
        LinearLayout row = row();
        TextView[] chips = new TextView[PressureStroke.SHAPES.length];
        for (int s = 0; s < chips.length; s++) {
            final int shape = s;
            TextView chip = text(13, false, TEXT2);
            chip.setText(PressureStroke.SHAPES[s]);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setOnClickListener(v -> {
                if (thumbs.shape() == shape) return;
                thumbs.shape(shape);
                for (int k = 0; k < chips.length; k++) shapeChip(chips[k], k == shape);
                redraw.run();
            });
            chips[s] = chip;
            shapeChip(chip, s == thumbs.shape());
            row.addView(chip, lp(-2, -2, 0, 0, 6, 0));
        }
        return row;
    }

    private void shapeChip(TextView chip, boolean on) {
        chip.setTextColor(on ? Color.WHITE : TEXT2);
        chip.setBackground(round(on ? ACCENT : SURFACE, 16));
    }

    /** A tool's texture and shape, as cells to open. */
    private void usedPictures(LinearLayout col, SaiSettingsFiles.Tool t) {
        List<Item> used = new ArrayList<>();
        File tex = SamplePreview.picture(idx.settings, new String[] {"brushtex"}, t.texture);
        if (tex != null) used.add(new Item(SettingsContents.BRUSH_TEXTURES, tex, null));
        File shape = SamplePreview.picture(idx.settings, new String[] {"brushfom/blotmap", "brushfom/bristle", "brushfom/brshape"}, t.shape);
        if (shape != null) used.add(new Item(SettingsContents.BRUSH_SHAPES, shape, null));
        if (used.isEmpty()) return;
        TextView h = text(15, true, TEXT); h.setText("It uses");
        col.addView(h, lp(-1, -2, 0, 14, 0, 8));
        col.addView(block(used, true));
    }

    /** The tools that use this texture or shape. */
    private void usedBy(LinearLayout col, Item it) {
        String n = SettingsContents.name(it.file);
        List<Item> tools = new ArrayList<>();
        for (SaiSettingsFiles.Tool t : idx.tools()) {
            boolean uses = it.kind == SettingsContents.BRUSH_TEXTURES ? t.texture.equalsIgnoreCase(n)
                         : it.kind == SettingsContents.BRUSH_SHAPES && t.shape.equalsIgnoreCase(n);
            if (uses) tools.add(new Item(-1, null, t));
        }
        if (tools.isEmpty()) return;
        TextView h = text(15, true, TEXT); h.setText("Used by");
        col.addView(h, lp(-1, -2, 0, 14, 0, 8));
        col.addView(block(tools, false));
    }

    private View facts(List<String[]> facts) {
        GridLayout g = new GridLayout(a);
        boolean wide = a.getResources().getConfiguration().screenWidthDp >= 600;
        int cols = wide ? 3 : 2;
        g.setColumnCount(cols);
        for (int i = 0; i < facts.size(); i++) {
            LinearLayout c = column();
            c.setBackground(round(SURFACE, 12));
            c.setPadding(dp(12), dp(9), dp(12), dp(9));
            TextView k = text(11, false, TEXT2); k.setText(facts.get(i)[0]);
            TextView v = text(15, false, TEXT); v.setText(facts.get(i)[1]);
            v.setSingleLine(); v.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(k); c.addView(v);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0; p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            p.setMargins(0, 0, i % cols == cols - 1 ? 0 : dp(8), dp(8));
            g.addView(c, p);
        }
        return g;
    }

    /* ---- small pieces ---- */

    private String layerName(String kind) {
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "nrm":  return a.getString(R.string.saipen_tools_normal);
            case "ink":  return a.getString(R.string.saipen_tools_linework);
            case "shp2": return a.getString(R.string.saipen_tools_shape);
            default:     return a.getString(R.string.saipen_preview_group, kind);
        }
    }

    /** "AngleJitter" -> "Angle jitter". */
    private static String words(String key) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(key.charAt(i - 1))) b.append(' ').append(Character.toLowerCase(c));
            else b.append(c);
        }
        return b.toString();
    }

    private static String size(double px) {
        return px == Math.floor(px) ? String.valueOf((long) px) : String.format(Locale.getDefault(), "%.1f", px);
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String date(long t) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(t));
    }

    private String relative(File f) {
        String s = idx.settings.getPath(), p = f.getPath();
        return p.startsWith(s + "/") ? p.substring(s.length() + 1) : f.getName();
    }

    private void heading(LinearLayout list, String title, String about) {
        TextView t = text(17, true, TEXT); t.setText(title);
        list.addView(t, lp(-1, -2, 0, list.getChildCount() == 0 ? 0 : 18, 0, 2));
        TextView s = text(12, false, TEXT2); s.setText(about);
        list.addView(s, lp(-1, -2, 0, 0, 0, 10));
    }

    private void section(LinearLayout list, String title) {
        TextView t = text(13, true, TEXT2); t.setText(title);
        list.addView(t, lp(-1, -2, 0, 16, 0, 6));
    }

    private LinearLayout box() {
        LinearLayout b = column();
        b.setBackground(round(SURFACE, 14));
        b.setPadding(dp(14), dp(2), dp(14), dp(2));
        return b;
    }

    private void settingRow(LinearLayout box, String title, String sub, String end, View.OnClickListener click) {
        if (box.getChildCount() > 0) {
            View line = new View(a); line.setBackgroundColor(LINE);
            box.addView(line, new LinearLayout.LayoutParams(-1, 1));
        }
        LinearLayout r = row();
        r.setPadding(0, dp(11), 0, dp(11));
        LinearLayout words = column();
        TextView t = text(15, false, TEXT); t.setText(title);
        words.addView(t);
        if (sub != null) { TextView s = text(12, false, TEXT2); s.setText(sub); words.addView(s, lp(-1, -2, 0, 2, 0, 0)); }
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        if (end != null) { TextView e = text(18, false, ACCENT); e.setText(end); r.addView(e, lp(-2, -2, 12, 0, 0, 0)); }
        if (click != null) r.setOnClickListener(click);
        box.addView(r);
    }

    private View dot(int colour, int sizeDp) {
        View v = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL); d.setColor(colour); d.setStroke(1, LINE);
        v.setBackground(d);
        return v;
    }

    private View nothing(String why) {
        TextView t = text(14, false, TEXT2);
        t.setText(why);
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    private void swap(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(a); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView pill(String label, boolean accent) {
        TextView t = text(14, true, accent ? Color.WHITE : TEXT); t.setText(label); t.setGravity(Gravity.CENTER);
        t.setBackground(round(accent ? ACCENT : SURFACE, 10));
        t.setPadding(dp(16), dp(9), dp(16), dp(9)); t.setMinWidth(dp(44));
        return t;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }
    private TextView text(int size, boolean bold, int color) {
        TextView v = new TextView(a); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }
    /** Sizes in dp (-1/-2 for match/wrap), margins in dp. */
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? dp(w) : w, h > 0 ? dp(h) : h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
    /** Sizes and margins already in pixels. */
    private static LinearLayout.LayoutParams lpPx(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
    private int dp(int n) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n, a.getResources().getDisplayMetrics()));
    }
}
