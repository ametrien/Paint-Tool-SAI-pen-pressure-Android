package com.winlator.saipen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.util.TypedValue;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The browser's previews, made ahead of time so its pages open whole and at
 * once: the index (BrowseIndex) and a sample for every tool and picture,
 * kept as small PNGs in the app's cache folder.
 *
 * The pages do not wait for them: they open as soon as the index is read,
 * and a cell whose preview is not made yet makes it when it is shown.
 *
 * WHEN: started while SAI Pen's own screen is shown (the card's refresh),
 * at low priority, and stopped when that screen goes away (SAI starting):
 * it must never take the CPU from SAI. A brush collection (2,195 pictures)
 * takes a while the first time; after that only new or changed files are
 * made.
 */
final class BrowseThumbs {
    interface Listener { void changed(); }

    private static BrowseThumbs one;

    static synchronized BrowseThumbs get(Context c) {
        if (one == null) one = new BrowseThumbs(c.getApplicationContext());
        return one;
    }

    /** A cell's preview, square, in pixels. */
    final int cell;
    private final File dir;
    private final LruCache<String, Bitmap> memory;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final android.content.SharedPreferences prefs;
    /** The tools' sample shape, the person's choice (PressureStroke). */
    private volatile int shape;

    private volatile BrowseIndex index;
    private volatile int done, total;
    private volatile boolean ready, stop, again, urgent;
    private volatile int tid;
    private File settings;
    private Thread thread;

    private BrowseThumbs(Context c) {
        cell = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 104, c.getResources().getDisplayMetrics()));
        prefs = c.getSharedPreferences("browse", Context.MODE_PRIVATE);
        shape = PressureStroke.shape(prefs.getInt("sample_shape", PressureStroke.S_CURVE));
        dir = new File(c.getCacheDir(), "browse-previews");
        dir.mkdirs();
        int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 8, 48 * 1024);
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
    }

    /* ---- getting ready ---- */

    /** Reads the settings and makes what is missing; again when running. */
    synchronized void start(File settings, boolean urgent) {
        this.settings = settings;
        stop = false;
        if (urgent) raise();
        if (thread != null && thread.isAlive()) { again = true; return; }
        thread = new Thread(this::run, "browse-previews");
        thread.start();
    }

    /** SAI Pen's screen went away: stop (what is made so far is kept). */
    void pause() { stop = true; }

    private void raise() {
        urgent = true;
        int t = tid;
        if (t != 0) {
            try { Process.setThreadPriority(t, Process.THREAD_PRIORITY_DEFAULT); }
            catch (RuntimeException ignored) {}
        }
    }

    int shape() { return shape; }

    /** Another sample shape for tools: kept, and the tools' cells made
     *  again in it as they are shown (the old ones go at the next run). */
    void shape(int s) {
        s = PressureStroke.shape(s);
        if (s == shape) return;
        shape = s;
        prefs.edit().putInt("sample_shape", s).apply();
    }

    /** The index is read: a page can open (previews follow as they are shown). */
    boolean ready() { return index != null; }
    BrowseIndex index() { return index; }
    int done() { return done; }
    int total() { return total; }

    void listen(Listener l) { listeners.add(l); }
    void unlisten(Listener l) { listeners.remove(l); }

    private void changed() { ui.post(() -> { for (Listener l : listeners) l.changed(); }); }

    private void run() {
        tid = Process.myTid();
        Process.setThreadPriority(urgent ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
        do {
            again = false;
            File s;
            synchronized (this) { s = settings; }
            BrowseIndex idx = BrowseIndex.read(s);
            /* the text is all a page needs to open; pictures come as they are shown */
            index = idx;
            List<Job> jobs = jobs(idx);
            total = jobs.size();
            done = 0;
            changed();
            Set<String> keep = new HashSet<>();
            for (Job j : jobs) {
                if (stop) return;
                String name = fileName(j.key);
                keep.add(name);
                File f = new File(dir, name);
                if (!f.isFile()) save(j.make(), f);
                done++;
                if (done % 16 == 0) changed();
            }
            /* previews of what is no longer there */
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
            ready = true;
            changed();
        } while (again && !stop);
    }

    /* ---- one preview ---- */

    interface Maker { Bitmap make(); }

    static final class Job {
        final String key;
        final Maker maker;
        Job(String key, Maker maker) { this.key = key; this.maker = maker; }
        Bitmap make() { try { return maker.make(); } catch (RuntimeException | OutOfMemoryError e) { return null; } }
    }

    private List<Job> jobs(BrowseIndex idx) {
        List<Job> out = new ArrayList<>();
        for (SaiSettingsFiles.Tool t : idx.tools()) out.add(tool(idx.settings, t));
        for (int k = 0; k < idx.pictures.size(); k++) for (File f : idx.pictures.get(k)) out.add(picture(k, f));
        return out;
    }

    Job tool(File settings, SaiSettingsFiles.Tool t) {
        /* v2: the stroke follows pressure (earlier ones were even) */
        int sh = shape;
        String key = "tool2|" + sh + "|" + t.name + "|" + t.kind + "|" + t.size + "|" + t.density + "|" + t.hardness
            + "|" + t.shape + "|" + t.texture + "|" + t.textureStrength + "|" + t.pressure.key() + "|" + cell;
        return new Job(key, () -> SamplePreview.tool(settings, t, sh, cell, cell));
    }

    Job picture(int kind, File f) {
        String key = "pic|" + kind + "|" + f.getPath() + "|" + f.length() + "|" + f.lastModified() + "|" + cell;
        return new Job(key, () -> sample(kind, f, cell, cell));
    }

    /** The sample for a picture of one kind, at any size (the detail page's is bigger). */
    static Bitmap sample(int kind, File f, int w, int h) {
        switch (kind) {
            case SettingsContents.BRUSH_TEXTURES: return SamplePreview.brushTexture(f, w, h);
            case SettingsContents.PAPER_TEXTURES: return SamplePreview.paperTexture(f, w, h);
            default: return SamplePreview.stamps(f, SaiSettingsFiles.ini(SaiSettingsFiles.iniOf(f)),
                                                 kind == SettingsContents.SCATTER, w, h);
        }
    }

    /** Puts the job's preview into view: from memory, the cache folder, or made now. */
    void load(Job j, ImageView view) {
        view.setTag(j.key);
        Bitmap b = memory.get(j.key);
        view.setImageBitmap(b);
        if (b != null) return;
        loader.execute(() -> {
            /* scrolled past before its turn came: not made now */
            if (!j.key.equals(view.getTag())) return;
            File f = new File(dir, fileName(j.key));
            Bitmap got = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (got == null) { got = j.make(); save(got, f); }
            Bitmap shown = got;
            ui.post(() -> {
                if (shown != null) memory.put(j.key, shown);
                if (j.key.equals(view.getTag())) view.setImageBitmap(shown);
            });
        });
    }

    /** Something bigger, made once for a detail page, off the screen's thread. */
    void make(Maker m, ImageView view) {
        Object tag = new Object();
        view.setTag(tag);
        loader.execute(() -> {
            Bitmap b;
            try { b = m.make(); } catch (RuntimeException | OutOfMemoryError e) { b = null; }
            Bitmap shown = b;
            ui.post(() -> { if (view.getTag() == tag) view.setImageBitmap(shown); });
        });
    }

    private static void save(Bitmap b, File f) {
        if (b == null) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (java.io.IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static String fileName(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 12; i++) s.append(String.format("%02x", d[i]));
            return s.append(".png").toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode()) + ".png";
        }
    }
}
