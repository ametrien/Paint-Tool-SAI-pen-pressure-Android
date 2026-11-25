package com.winlator.saipen;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * At most one frame per refresh of the display. Winlator asked for a frame
 * on every change of any window (GLSurfaceView.requestRender), ~120 a second
 * on a 60 Hz display; the speed log showed frames waiting ~29 ms in Android
 * after the GPU had finished them. Untested guess: the extra frames keep
 * Android's queue full.
 *
 * The refresh comes from the display itself (Choreographer on Android), so
 * it follows 60, 90, 120 or 144 Hz and a display that changes its rate.
 *
 * The first request after a refresh draws at once (no added wait); more
 * requests before the next refresh become one frame at that refresh, with
 * whatever is newest then. With no requests the refresh callback stops.
 *
 * Mode file files/saipen-frames: "vsync" (default) or "free" (as Winlator,
 * a frame for every request), so both can be compared without a new APK.
 *
 * No Android here: the JVM tests drive it through Host.
 */
public final class FramePacer {
    public interface Host {
        /** really ask for a frame (GLSurfaceView.requestRender) */
        void render();
        /** call onRefresh() at the display's next refresh, once */
        void atNextRefresh();
    }

    public static final String MODE_FILE = "saipen-frames";
    public static final String VSYNC = "vsync", FREE = "free";

    private final Host host;
    private final boolean paced;
    private boolean drewSinceRefresh, waiting, armed;
    private long requests, frames;

    public FramePacer(Host host, boolean paced) {
        this.host = host;
        this.paced = paced;
    }

    public static boolean readPaced(File filesDir) {
        try {
            File f = new File(filesDir, MODE_FILE);
            if (!f.isFile()) return true;
            String m = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            return !m.equals(FREE);
        }
        catch (Exception e) {
            return true;
        }
    }

    public boolean isPaced() {
        return paced;
    }

    /** Someone wants a frame; any thread. */
    public void request() {
        boolean drawNow;
        boolean arm = false;
        synchronized (this) {
            requests++;
            if (!paced) drawNow = true;
            else if (!drewSinceRefresh) {
                drewSinceRefresh = true;
                drawNow = true;
            }
            else {
                waiting = true;
                drawNow = false;
            }
            if (drawNow) frames++;
            if (paced && !armed) {
                armed = true;
                arm = true;
            }
        }
        if (drawNow) host.render();
        if (arm) host.atNextRefresh();
    }

    /** The display refreshed (the main thread's Choreographer). */
    public void onRefresh() {
        boolean draw;
        synchronized (this) {
            armed = false;
            draw = waiting;
            waiting = false;
            drewSinceRefresh = draw;
            if (draw) {
                frames++;
                armed = true;          /* the next refresh resets the budget again */
            }
        }
        if (draw) {
            host.render();
            host.atNextRefresh();
        }
    }

    /** "N requests, M frames" so far, for the speed log. */
    public synchronized String counts() {
        return requests + " requests, " + frames + " frames";
    }
}
