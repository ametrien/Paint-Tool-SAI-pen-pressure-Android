package com.winlator.saipen;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Holding cards to delete them, the same in All drawings and All timelapses:
 * holding a card selects it, then a tap adds or takes away more; while
 * selecting, the title row says how many, with Select all, Delete and
 * Cancel; Delete asks once for them all. Each card carries a ring in its
 * picture's corner (a tick on the accent colour when chosen) and an accent
 * edge. Which cards there are and what deleting does stay with the window.
 */
final class CardSelection<K> {
    static final int DANGER = 0xFFD32F2F;
    private static final int SURFACE = StatisticsDialog.SURFACE, TEXT = StatisticsDialog.TEXT,
                             ACCENT = StatisticsDialog.ACCENT;

    interface Host<K> {
        /** Every card that can be seen now: Select all takes these. */
        List<K> cards();
        /** The selection changed: the title row and the cards show it again. */
        void selectionChanged();
        /** Delete was tapped and the artist said yes. */
        void delete(List<K> chosen);
    }

    private final Activity activity;
    private final Host<K> host;
    /** Its title: "Delete %d drawings?". */
    private final int askPlural;
    /** Under it: what goes and that it can't be undone. */
    private final int askMessage;
    private final Set<K> selected = new LinkedHashSet<>();
    /** A delete is under way: nothing changes the selection meanwhile. */
    boolean busy;

    CardSelection(Activity activity, Host<K> host, int askPlural, int askMessage) {
        this.activity = activity;
        this.host = host;
        this.askPlural = askPlural;
        this.askMessage = askMessage;
    }

    boolean isEmpty() { return selected.isEmpty(); }
    boolean contains(K k) { return selected.contains(k); }
    int size() { return selected.size(); }

    /** A tap while selecting, or a hold. */
    void toggle(K k) {
        if (busy) return;
        if (!selected.remove(k)) selected.add(k);
        host.selectionChanged();
    }

    void clear() {
        selected.clear();
        host.selectionChanged();
    }

    /** Only what can be seen stays selected: Delete never takes a hidden one.
     *  True when the title row must be drawn again. */
    boolean keepOnly(Collection<K> visible) {
        return selected.retainAll(visible) || !selected.isEmpty();
    }

    /** Forgets the selection without telling anyone (after a delete). */
    void forget() {
        selected.clear();
    }

    /* ---- on a card ---- */

    /** The ring for a card's picture, in its top left corner (FrameLayout). */
    TextView newCheck() {
        TextView check = new TextView(activity);
        check.setTextSize(13);
        check.setTextColor(Color.WHITE);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(View.GONE);
        return check;
    }

    static final int CHECK_DP = 26;

    /** Shows on a card whether it is chosen: an empty ring or a tick while
     *  selecting, nothing otherwise; the accent edge when chosen. */
    void show(View card, TextView check, K k) {
        boolean selecting = !selected.isEmpty(), on = k != null && selected.contains(k);
        check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        check.setText(on ? "✓" : "");
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(on ? ACCENT : 0x991B1E21);
        ring.setStroke(dp(2), on ? ACCENT : TEXT);
        check.setBackground(ring);
        GradientDrawable bg = round(SURFACE, 14);
        if (on) bg.setStroke(dp(2), ACCENT);
        card.setBackground(bg);
    }

    /* ---- the title row while selecting ---- */

    /** How many, Select all (while some are left), Delete and Cancel. */
    void fillHead(LinearLayout head) {
        head.removeAllViews();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setTextSize(21);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText(activity.getResources().getQuantityString(R.plurals.saipen_n_selected, selected.size(), selected.size()));
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        List<K> cards = host.cards();
        if (selected.size() < cards.size()) {
            TextView all = pill(activity.getString(R.string.saipen_select_all), SURFACE, TEXT);
            all.setOnClickListener(v -> {
                selected.addAll(host.cards());
                host.selectionChanged();
            });
            head.addView(all, gap());
        }
        TextView delete = pill(activity.getString(R.string.saipen_delete), DANGER, Color.WHITE);
        delete.setOnClickListener(v -> ask());
        head.addView(delete, gap());
        TextView cancel = pill(activity.getString(android.R.string.cancel), SURFACE, TEXT);
        cancel.setOnClickListener(v -> clear());
        head.addView(cancel, gap());
    }

    /** Asks once for them all; yes hands them to the window. */
    private void ask() {
        if (busy || selected.isEmpty()) return;
        List<K> chosen = new ArrayList<>(selected);
        int n = chosen.size();
        new AlertDialog.Builder(activity, SaiLauncherActivity.DIALOG_THEME)
            .setTitle(activity.getResources().getQuantityString(askPlural, n, n))
            .setMessage(askMessage)
            .setPositiveButton(R.string.saipen_delete, (d, w) -> host.delete(chosen))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /* ---- the look: DrawingsDialog's ---- */

    private TextView pill(String label, int background, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(14);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(round(background, 18));
        return t;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(8), 0, 0, 0);
        return p;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
