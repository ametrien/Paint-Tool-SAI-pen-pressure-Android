package com.winlator.saipen;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;

/**
 * Drawing straight into the buffer the display shows (front-buffered
 * rendering, as stylus apps do for ink), instead of handing Android a new
 * frame that waits in its queue and compositor. The speed log showed ~26 ms
 * between the GPU finishing a frame and the glass; a 240 fps video put the
 * ink behind the pen at 100 ms with it against 137 ms without.
 *
 * The display may take the buffer while a frame is being drawn. GLRenderer
 * draws a frame in layers (Wine's desktop, then SAI's windows, then the
 * cursor), and the owner saw the picture now and then jump or vanish for a
 * moment (0.4.28-front). Untested guess: those were half-drawn frames. So
 * with the front buffer the frame is drawn offscreen and copied onto the
 * screen in one blit (GLRenderer, offscreen()): every pixel changes once,
 * to its finished value.
 *
 * Needs EGL_KHR_mutable_render_buffer (a config that can switch to single
 * buffer) and EGL_ANDROID_front_buffer_auto_refresh (the compositor takes
 * the buffer at every refresh, with or without a swap). Without them the
 * app draws as before.
 *
 * Three choices on SAI Pen's screen ("Screen drawing"), in files/saipen-frontbuffer:
 * - "on": Front buffer, always;
 * - "drawing": Front buffer while drawing: single buffer while the pen is
 *   near the glass or touching it and for HOLD_MS after, the usual queue
 *   otherwise (menus, zoom, scrolling). It switches on hover, before the
 *   touch, because a switch takes effect only at the next swap;
 * - "off": Standard.
 * No file: FirstRun.SCREEN_DRAWING.
 */
public final class FrontBuffer {
    private static final String TAG = "SaiPen";
    public static final String MODE_FILE = "saipen-frontbuffer";
    static final int EGL_MUTABLE_RENDER_BUFFER_BIT_KHR = 0x1000;
    static final int EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID = 0x314C;
    static final int EGL_OPENGL_ES3_BIT_KHR = 0x40;
    /** Front buffer while drawing: stays this long after the pen was last near. */
    public static final long HOLD_MS = 500;

    public enum Mode {
        FRONT("on"), WHILE_DRAWING("drawing"), STANDARD("off");

        public final String word;

        Mode(String word) {
            this.word = word;
        }

        public static Mode of(String word) {
            String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
            for (Mode m : values()) if (m.word.equals(w)) return m;
            return null;
        }
    }

    /** For the speed log. */
    public static volatile String state = "not tried";
    private static volatile Mode mode = Mode.STANDARD;
    private static volatile boolean mutableConfig;
    /** The surface can be single buffered (both extensions, a config, and it said yes). */
    private static volatile boolean capable;
    /** The surface is single buffered now (GL thread). */
    private static boolean single;
    private static volatile boolean touching;
    private static volatile long lastPenMs = -1;
    private static volatile int switches;

    private FrontBuffer() {}

    public static Mode readMode(File filesDir) {
        Mode fallback = Mode.of(FirstRun.SCREEN_DRAWING);
        if (fallback == null) fallback = Mode.STANDARD;
        try {
            File f = new File(filesDir, MODE_FILE);
            if (!f.isFile()) return fallback;
            Mode m = Mode.of(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return m != null ? m : fallback;
        }
        catch (Exception e) {
            return fallback;
        }
    }

    /** SAI Pen's screen: the choice, read at SAI's next start. */
    public static boolean writeMode(File filesDir, Mode m) {
        try {
            Files.write(new File(filesDir, MODE_FILE).toPath(), (m.word + "\n").getBytes(StandardCharsets.UTF_8));
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    /** Front buffer while drawing: should the surface be single buffered now? */
    static boolean wantSingle(boolean touching, long lastPenMs, long now) {
        return touching || (lastPenMs >= 0 && now - lastPenMs < HOLD_MS);
    }

    /** The pen is near the glass or touches it (TouchpadView: hover and touch events). */
    public static void pen(boolean nowTouching) {
        touching = nowTouching;
        lastPenMs = SystemClock.uptimeMillis();
    }

    /** RGBA 8888, no depth or stencil, as Winlator's setEGLConfigChooser(8, 8, 8, 8, 0, 0);
     *  one that can be single buffered if wanted and there is one. */
    public static GLSurfaceView.EGLConfigChooser chooser(Mode wanted) {
        return (egl, display) -> {
            EGLConfig c = null;
            if (wanted != Mode.STANDARD) c = pick(egl, display, EGL10.EGL_WINDOW_BIT | EGL_MUTABLE_RENDER_BUFFER_BIT_KHR);
            mutableConfig = c != null;
            if (c == null) c = pick(egl, display, EGL10.EGL_WINDOW_BIT);
            if (c == null) throw new IllegalArgumentException("no RGBA 8888 config");
            return c;
        };
    }

    private static EGLConfig pick(EGL10 egl, javax.microedition.khronos.egl.EGLDisplay display, int surfaceType) {
        int[] attribs = {
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL10.EGL_SURFACE_TYPE, surfaceType,
            EGL10.EGL_NONE
        };
        int[] n = new int[1];
        if (!egl.eglChooseConfig(display, attribs, null, 0, n) || n[0] <= 0) return null;
        EGLConfig[] configs = new EGLConfig[n[0]];
        if (!egl.eglChooseConfig(display, attribs, configs, n[0], n)) return null;
        int[] v = new int[1];
        for (EGLConfig c : configs) {
            if (size(egl, display, c, EGL10.EGL_RED_SIZE, v) == 8 && size(egl, display, c, EGL10.EGL_GREEN_SIZE, v) == 8
                && size(egl, display, c, EGL10.EGL_BLUE_SIZE, v) == 8 && size(egl, display, c, EGL10.EGL_ALPHA_SIZE, v) == 8
                && size(egl, display, c, EGL10.EGL_DEPTH_SIZE, v) == 0 && size(egl, display, c, EGL10.EGL_STENCIL_SIZE, v) == 0)
                return c;
        }
        return null;
    }

    private static int size(EGL10 egl, javax.microedition.khronos.egl.EGLDisplay d, EGLConfig c, int what, int[] v) {
        return egl.eglGetConfigAttrib(d, c, what, v) ? v[0] : -1;
    }

    /** Draw the frame offscreen and blit it (GLRenderer): whenever the
     *  surface is or may become single buffered. */
    public static boolean offscreen() {
        return capable;
    }

    /** The surface is (re)made, on the GL thread with it current. */
    public static void surfaceReady(Mode wanted) {
        mode = wanted;
        capable = false;
        single = false;
        if (wanted == Mode.STANDARD) {
            state = "Standard (chosen)";
            return;
        }
        EGLDisplay d = EGL14.eglGetCurrentDisplay();
        EGLSurface s = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        String ext = EGL14.eglQueryString(d, EGL10.EGL_EXTENSIONS);
        boolean mutable = ext != null && ext.contains("EGL_KHR_mutable_render_buffer");
        boolean auto = ext != null && ext.contains("EGL_ANDROID_front_buffer_auto_refresh");
        if (!mutable || !auto) {
            state = "not on this device (mutable_render_buffer " + (mutable ? "yes" : "no")
                    + ", front_buffer_auto_refresh " + (auto ? "yes" : "no") + ")";
        }
        else if (!mutableConfig) {
            state = "not on this device (no config that can be single buffered)";
        }
        else if (!EGL14.eglSurfaceAttrib(d, s, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL14.EGL_TRUE)) {
            state = "refused (auto refresh, EGL error 0x" + Integer.toHexString(EGL14.eglGetError()) + ")";
        }
        else if (wanted == Mode.FRONT && !EGL14.eglSurfaceAttrib(d, s, EGL14.EGL_RENDER_BUFFER, EGL14.EGL_SINGLE_BUFFER)) {
            state = "refused (single buffer, EGL error 0x" + Integer.toHexString(EGL14.eglGetError()) + ")";
        }
        else {
            capable = true;
            single = wanted == Mode.FRONT;
            state = wanted == Mode.FRONT ? "Front buffer (single buffer, auto refresh, drawn offscreen)"
                                         : "Front buffer while drawing (switches with the pen, drawn offscreen)";
        }
        Log.i(TAG, "front buffer: " + state);
    }

    /** Before each frame, GL thread: Front buffer while drawing switches here. */
    public static void beforeFrame() {
        if (!capable || mode != Mode.WHILE_DRAWING) return;
        boolean want = wantSingle(touching, lastPenMs, SystemClock.uptimeMillis());
        if (want == single) return;
        if (EGL14.eglSurfaceAttrib(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW),
                                   EGL14.EGL_RENDER_BUFFER, want ? EGL14.EGL_SINGLE_BUFFER : EGL14.EGL_BACK_BUFFER)) {
            single = want;
            switches++;
        }
    }

    /** For the speed log at the end of a session, null if it never switches. */
    public static String switchesWords() {
        return mode == Mode.WHILE_DRAWING ? switches + " switches between the front buffer and the queue" : null;
    }
}
