package com.winlator.saipen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * A timelapse's still that plays the video in place while the pen (or a
 * mouse) hovers over it: muted, looped, fitted like the still. When the pen
 * leaves, or touches down, the video stops and the still is back. One
 * MediaPlayer at most, made on hover and released on leave, so a page that
 * is only looked at costs nothing.
 *
 * A TextureView, not a VideoView: it scrolls and clips with the page.
 */
final class HoverVideo extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "SaiPen";

    private final ImageView still;
    private final TextureView video;
    private File file;
    private MediaPlayer player;
    private Surface surface;
    /** The pen is over it: play once the surface and the player are ready. */
    private boolean wanted;
    /** The player failed on this file: no new try on every hover move. */
    private boolean broken;

    HoverVideo(Context c) {
        super(c);
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(still, new LayoutParams(-1, -1));
        video = new TextureView(c);
        video.setSurfaceTextureListener(this);
        video.setAlpha(0f);
        addView(video, new LayoutParams(-1, -1, Gravity.CENTER));
        setOnHoverListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (!wanted) start();
                    return true;
                case MotionEvent.ACTION_HOVER_EXIT:
                    stop();
                    return true;
                default:
                    return false;
            }
        });
    }

    void setStill(Bitmap b) {
        still.setImageBitmap(b);
    }

    void setVideo(File f) {
        stop();
        file = f;
        broken = false;
    }

    private void start() {
        wanted = true;
        if (broken || file == null || !file.isFile() || player != null) return;
        if (surface == null) return;   /* onSurfaceTextureAvailable starts it */
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(file.getPath());
            p.setSurface(surface);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnVideoSizeChangedListener((mp, w, h) -> fit(w, h));
            p.setOnPreparedListener(mp -> {
                if (player != mp || !wanted) return;
                mp.start();
                video.setAlpha(1f);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "timelapse preview: player error " + what + "/" + extra);
                broken = true;
                stop();
                return true;
            });
            p.prepareAsync();
        }
        catch (Exception e) {
            Log.w(TAG, "timelapse preview: " + e.getMessage());
            broken = true;
            stop();
        }
    }

    void stop() {
        wanted = false;
        video.setAlpha(0f);
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.release();
            }
            catch (RuntimeException ignored) {
            }
        }
    }

    /** The video as big as fits, its own shape kept, centred: like the still. */
    private void fit(int w, int h) {
        int boxW = getWidth() - getPaddingLeft() - getPaddingRight();
        int boxH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0 || boxW <= 0 || boxH <= 0) return;
        float scale = Math.min(boxW / (float) w, boxH / (float) h);
        LayoutParams lp = new LayoutParams(Math.round(w * scale), Math.round(h * scale), Gravity.CENTER);
        video.setLayoutParams(lp);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (wanted) start();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stop();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
