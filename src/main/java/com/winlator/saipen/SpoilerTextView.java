package com.winlator.saipen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A line of text with one part hidden in the manner of Telegram's spoilers:
 * covered by drifting, twinkling specks until a tap clears them in a circle
 * growing out from the finger, the specks pushed aside at its edge. Used for
 * the licence number on SAI Pen's screen, so a screenshot of the screen does
 * not show it.
 *
 * Ported from the owner's own spoiler on the owner's lyrics site (Spoiler.jsx,
 * written from scratch; nothing taken from Telegram's GPL clients), with the
 * same numbers: the same density of specks per dp of covered text, 0.7 s to
 * clear, an 18 dp soft edge. How it works: the text is drawn as usual, then
 * the hidden part's line boxes are painted over in the screen's background
 * colour with the specks on top, in a layer; revealing erases that layer in
 * a widening circle, so the text shows through where it has cleared.
 *
 * Hidden again whenever the text changes, so each visit to the screen starts
 * covered. With animations off in Android's settings the specks stand still
 * and a tap reveals at once.
 */
public class SpoilerTextView extends androidx.appcompat.widget.AppCompatTextView {
    private static final float SPECKS_PER_DP2 = 0.22f;
    private static final int MAX_SPECKS = 3500;
    private static final long REVEAL_MS = 700;
    private static final float EDGE_DP = 18;

    private static final class Speck {
        float x, y, vx, vy, age, life, size;
        int rect;
        boolean dead;
    }

    /* primitives only above the fields that need constructing: TextView's
     * constructor calls onTextChanged before these initialisers run */
    private int start = -1, end = -1;
    private boolean revealed = true;
    private boolean settingSpoiler;
    private boolean measured;
    private long lastFrame;
    private long revealStart = -1;
    private float revealX, revealY, revealMax;
    private float area;
    private String spoilerText;

    private final List<RectF> rects = new ArrayList<>();
    private final RectF bounds = new RectF();
    private float[] cumulative = new float[0];
    private Speck[] specks = new Speck[0];
    private final Paint cover = new Paint();
    private final Paint ink = new Paint();
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    public SpoilerTextView(Context context) {
        super(context);
    }

    public SpoilerTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SpoilerTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Shows `text` with [start, end) hidden; -1 hides nothing. The same
     *  text and part again keeps it as it is, revealed or not. */
    public void setSpoiler(CharSequence text, int start, int end) {
        String words = text.toString();
        boolean same = words.equals(spoilerText) && start == this.start && end == this.end;
        if (same) return;
        settingSpoiler = true;
        setText(text);
        settingSpoiler = false;
        spoilerText = words;
        this.start = start;
        this.end = end;
        revealed = start < 0 || end <= start || end > text.length();
        revealStart = -1;
        measured = false;
        invalidate();
    }

    /**
     * A plain setText with other words: nothing hidden in someone else's
     * text. THE TRAP: AppCompat sets the same text again by itself once its
     * emoji support has loaded, a moment after the screen opens; taking that
     * for new text uncovered the licence number at once (seen on the Tab S6
     * Lite, 2026-11-20). The same words keep the spoiler.
     */
    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (settingSpoiler || this.start < 0) return;
        if (spoilerText != null && text != null && spoilerText.equals(text.toString())) {
            measured = false;
            return;
        }
        this.start = -1;
        this.end = -1;
        revealed = true;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) measured = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        measured = false;
    }

    private float dp() {
        return getResources().getDisplayMetrics().density;
    }

    private boolean still() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    /** The hidden part's line boxes, and specks spread over them. */
    private void measure() {
        measured = true;
        rects.clear();
        Layout layout = getLayout();
        if (layout == null) return;
        CharSequence text = getText();
        int s = Math.min(start, text.length()), e = Math.min(end, text.length());
        if (e <= s) return;
        float dp = dp();
        float left = getTotalPaddingLeft(), top = getTotalPaddingTop();
        for (int line = layout.getLineForOffset(s); line <= layout.getLineForOffset(e - 1); line++) {
            int ls = Math.max(s, layout.getLineStart(line));
            int le = Math.min(e, layout.getLineEnd(line));
            if (le <= ls) continue;
            float x = layout.getPrimaryHorizontal(ls);
            float w = getPaint().measureText(text, ls, le);
            rects.add(new RectF(left + x - dp, top + layout.getLineTop(line),
                                left + x + w + dp, top + layout.getLineBottom(line)));
        }
        if (rects.isEmpty()) return;
        bounds.set(rects.get(0));
        for (RectF r : rects) bounds.union(r);
        cumulative = new float[rects.size()];
        area = 0;
        for (int i = 0; i < rects.size(); i++) cumulative[i] = area += rects.get(i).width() * rects.get(i).height();
        int n = Math.min(MAX_SPECKS, Math.round(area / (dp * dp) * SPECKS_PER_DP2));
        specks = new Speck[n];
        for (int i = 0; i < n; i++) {
            Speck p = place(new Speck());
            p.age = random.nextFloat() * p.life;
            specks[i] = p;
        }
        cover.setColor(backdrop());
    }

    private Speck place(Speck p) {
        float k = random.nextFloat() * area;
        int i = 0;
        while (i < cumulative.length - 1 && k > cumulative[i]) i++;
        RectF r = rects.get(i);
        float dp = dp();
        p.x = r.left + random.nextFloat() * r.width();
        p.y = r.top + random.nextFloat() * r.height();
        p.rect = i;
        double a = random.nextDouble() * Math.PI * 2;
        float v = (3 + random.nextFloat() * 11) * dp;
        p.vx = (float) Math.cos(a) * v;
        p.vy = (float) Math.sin(a) * v;
        p.life = 0.6f + random.nextFloat() * 1.4f;
        p.size = (random.nextFloat() < 0.25f ? 1.4f : 1f) * dp;
        p.dead = false;
        return p;
    }

    /** The colour behind the text, made opaque: it has to hide the words. */
    private int backdrop() {
        for (View v = this; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getBackground() instanceof ColorDrawable) {
                int c = ((ColorDrawable) v.getBackground()).getColor();
                if (Color.alpha(c) > 0) return c | 0xFF000000;
            }
        }
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)
            && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data | 0xFF000000;
        }
        return 0xFF1E1E1E;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (revealed) return;
        if (!measured) measure();
        if (rects.isEmpty()) return;

        long now = SystemClock.uptimeMillis();
        boolean still = still();
        float dt = lastFrame == 0 || still ? 0 : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float dp = dp(), edge = EDGE_DP * dp;
        float radius = revealStart < 0 ? -1
            : revealMax * easeOut(Math.min(1f, (now - revealStart) / (float) REVEAL_MS));

        int layer = canvas.saveLayer(bounds.left - edge * 2, bounds.top - edge * 2,
                                     bounds.right + edge * 2, bounds.bottom + edge * 2, null);
        for (RectF r : rects) canvas.drawRect(r, cover);
        ink.setColor(getCurrentTextColor());
        for (Speck p : specks) {
            if (p.dead) continue;
            p.age += dt;
            if (radius >= 0) {
                /* the clearing edge shoves the specks outward as it passes */
                float dx = p.x - revealX, dy = p.y - revealY;
                float d = Math.max(1f, (float) Math.hypot(dx, dy));
                if (d < radius - edge) {
                    p.dead = true;
                    continue;
                }
                if (d < radius + edge * 2) {
                    p.vx += dx / d * 900 * dp * dt;
                    p.vy += dy / d * 900 * dp * dt;
                }
            }
            else if (p.age > p.life) {
                place(p).age = 0;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            if (radius < 0 && !rects.get(p.rect).contains(p.x, p.y)) {
                place(p).age = 0;
                continue;
            }
            float alpha = still ? 0.7f : Math.max(0f, (float) Math.sin(Math.PI * p.age / p.life)) * 0.95f;
            ink.setAlpha(Math.round(alpha * Color.alpha(getCurrentTextColor())));
            canvas.drawRect(p.x, p.y, p.x + p.size, p.y + p.size, ink);
        }
        if (radius >= 0) {
            float r = Math.max(1f, radius);
            float inner = Math.max(0f, (radius - edge) / r);
            clear.setShader(new RadialGradient(revealX, revealY, r, new int[] {Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                                               new float[] {0f, inner, 1f}, Shader.TileMode.CLAMP));
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            canvas.drawCircle(revealX, revealY, r, clear);
            if (radius >= revealMax) revealed = true;
        }
        canvas.restoreToCount(layer);
        /* runs only while drawn: scrolled out of sight, it rests */
        if (!revealed && (!still || radius >= 0)) postInvalidateOnAnimation();
    }

    private static float easeOut(float t) {
        return 1 - (float) Math.pow(1 - t, 3);
    }

    private boolean onHidden(float x, float y) {
        float slop = 8 * dp();
        for (RectF r : rects) {
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (revealed || revealStart >= 0 || rects.isEmpty() || !onHidden(e.getX(), e.getY())) {
            return super.onTouchEvent(e);
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            reveal(e.getX(), e.getY());
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        if (!revealed && revealStart < 0 && !rects.isEmpty()) reveal(bounds.centerX(), bounds.centerY());
        return super.performClick();
    }

    private void reveal(float x, float y) {
        if (still()) {
            revealed = true;
            invalidate();
            return;
        }
        revealX = x;
        revealY = y;
        revealMax = Math.max(Math.max((float) Math.hypot(x - bounds.left, y - bounds.top),
                                      (float) Math.hypot(bounds.right - x, y - bounds.top)),
                             Math.max((float) Math.hypot(x - bounds.left, bounds.bottom - y),
                                      (float) Math.hypot(bounds.right - x, bounds.bottom - y)))
                    + EDGE_DP * dp();
        revealStart = SystemClock.uptimeMillis();
        lastFrame = revealStart;
        invalidate();
    }
}
