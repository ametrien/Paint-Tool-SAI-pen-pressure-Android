package com.winlator.saipen;

import java.util.ArrayDeque;

/**
 * When the app's frames reached the glass, for the speed log: Android's own
 * frame timestamps (EGL_ANDROID_get_frame_timestamps, native code in
 * Winlator's gpu_helper.c). Only reads times; how frames are drawn does not
 * change.
 *
 * GLRenderer, on its GL thread: surfaceReady() when the surface is (re)made,
 * onFrame() at the end of every frame, before GLSurfaceView swaps it. A
 * frame's times are known a few frames later, so frames wait in order until
 * Android knows what became of them. The clock is CLOCK_MONOTONIC, the same
 * as SystemClock.uptimeMillis.
 *
 * No Android here: the JVM tests drive it through Egl.
 */
public final class GlassTimes {
    /** Android's side. times: out[0] the GPU finished, out[1] the display
     *  showed it, ns; -2 not known yet, -1 never. false: frame unknown. */
    public interface Egl {
        boolean enable();
        long nextFrameId();
        boolean times(long frameId, long[] out);
    }

    public interface Out {
        /** gpuDone and shown in ms, -1 unknown or never */
        void frame(long frameStart, long frameEnd, boolean drawing, long gpuDone, long shown);
    }

    /** Frames waiting for their times, at most; past it the oldest is given up. */
    public static final int MAX_WAITING = 64;
    static final long PENDING = -2;

    private static final class Waiting {
        final long id, start, end;
        final boolean drawing;

        Waiting(long id, long start, long end, boolean drawing) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.drawing = drawing;
        }
    }

    private final Egl egl;
    private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();
    private final long[] buf = new long[2];
    private boolean on;
    private int givenUp;

    public GlassTimes(Egl egl) {
        this.egl = egl;
    }

    /** The surface is new: turn the timestamps on for it. Frames of the old
     *  one are given up. True if this device has them. */
    public boolean surfaceReady() {
        givenUp += waiting.size();
        waiting.clear();
        on = egl.enable();
        return on;
    }

    public boolean isOn() {
        return on;
    }

    /** Frames given up on (Android forgot them, or too many waited). */
    public int givenUp() {
        return givenUp;
    }

    /** The end of a frame, before its swap. out gets every frame whose times are now known. */
    public void onFrame(long start, long end, boolean drawing, Out out) {
        if (!on) return;
        poll(out);
        long id = egl.nextFrameId();
        if (id < 0) return;
        if (waiting.size() >= MAX_WAITING) {
            waiting.pollFirst();
            givenUp++;
        }
        waiting.addLast(new Waiting(id, start, end, drawing));
    }

    private void poll(Out out) {
        while (!waiting.isEmpty()) {
            Waiting w = waiting.peekFirst();
            buf[0] = PENDING;
            buf[1] = PENDING;
            if (!egl.times(w.id, buf)) {
                waiting.pollFirst();
                givenUp++;
                continue;
            }
            if (buf[1] == PENDING) return;   /* frames reach the display in order */
            waiting.pollFirst();
            if (out != null) out.frame(w.start, w.end, w.drawing, ms(buf[0]), ms(buf[1]));
        }
    }

    private static long ms(long ns) {
        return ns >= 0 ? ns / 1_000_000L : -1;
    }

    /** The real Egl, Winlator's libwinlator.so. */
    public static Egl android() {
        return new Egl() {
            @Override
            public boolean enable() {
                try {
                    System.loadLibrary("winlator");
                    return nativeEnable() == 1;
                }
                catch (UnsatisfiedLinkError e) {
                    return false;
                }
            }

            @Override
            public long nextFrameId() {
                return nativeNextFrameId();
            }

            @Override
            public boolean times(long frameId, long[] out) {
                return nativeTimes(frameId, out) == 1;
            }
        };
    }

    private static native int nativeEnable();

    private static native long nativeNextFrameId();

    private static native int nativeTimes(long frameId, long[] out);
}
