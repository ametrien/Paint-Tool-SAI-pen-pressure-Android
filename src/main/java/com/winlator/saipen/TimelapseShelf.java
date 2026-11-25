package com.winlator.saipen;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Pending;
import com.winlator.saipen.DrawingLibrary.Piece;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the timelapses of each drawing live (DrawingLibrary decides which
 * drawing a session belongs to, the Mac app's way):
 *
 * - Documents/SAI Drawings/Timelapses/NewCanvas1 2026-11-23 1924.mp4: one
 *   video per drawing, every session of it in order. This is what the
 *   Gallery and the Files app show.
 * - .../Timelapses/.pieces/<drawing>/<session>.mp4: each session's own
 *   video, the archive the joined one is made from (append only: a session
 *   filed into the wrong drawing moves as a file, no video is un-merged).
 *   .nomedia keeps them out of the Gallery. Outside the app, so a
 *   reinstall keeps them.
 * - .pieces/drawings.txt: which session is which drawing.
 *
 * Joining copies the encoded frames (MediaExtractor into MediaMuxer): no
 * re-encoding, quick, nothing lost.
 */
public final class TimelapseShelf {
    private static final String TAG = "SaiPen";
    private TimelapseShelf() {}

    public static File root() {
        return SaiPenSetup.timelapseFolder();
    }

    public static File piecesRoot() {
        return new File(root(), ".pieces");
    }

    public static File libraryFile() {
        return new File(piecesRoot(), "drawings.txt");
    }

    public static File folder(Drawing d) {
        return new File(piecesRoot(), d.folder);
    }

    public static File pieceFile(Drawing d, Piece p) {
        return new File(folder(d), p.file);
    }

    public static File still(Drawing d) {
        return new File(folder(d), "still.jpg");
    }

    public static File joined(Drawing d, int run) {
        return new File(root(), DrawingLibrary.joinedName(d, run));
    }

    public static synchronized DrawingLibrary load() {
        try {
            return DrawingLibrary.load(libraryFile());
        }
        catch (IOException e) {
            Log.e(TAG, "timelapse: the drawings list is damaged, starting a new one beside it", e);
            libraryFile().renameTo(new File(piecesRoot(), "drawings-damaged-" + System.currentTimeMillis() + ".txt"));
            return new DrawingLibrary();
        }
    }

    public static synchronized void save(DrawingLibrary lib) throws IOException {
        File dir = piecesRoot();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File nomedia = new File(dir, ".nomedia");
        if (!nomedia.exists()) nomedia.createNewFile();
        lib.save(libraryFile());
    }

    /**
     * Files one encoded session: into its drawing, the piece moved into the
     * drawing's folder, the still updated, the drawing's video joined again.
     * `closing` is the session's last frame (BGRA), for the still.
     */
    public static Drawing add(DrawingLibrary lib, File encoded, String title, long startedAt, int frames,
                              int width, int height, DrawingLibrary.Signature opening,
                              DrawingLibrary.Signature closing, byte[] lastPixels, int stride) throws IOException {
        Set<String> all = new HashSet<>();
        for (Drawing d : lib.drawings) for (Piece p : d.pieces) all.add(p.file);
        Piece piece = new Piece(DrawingLibrary.pieceName(startedAt, all), startedAt, frames, width, height, opening, closing);
        Drawing d = lib.file(piece, title);
        File dest = pieceFile(d, piece);
        move(encoded, dest);
        if (lastIs(d, piece)) writeStill(still(d), lastPixels, width, height, stride);
        save(lib);
        rebuild(d);
        return d;
    }

    private static boolean lastIs(Drawing d, Piece p) {
        List<Piece> o = d.ordered();
        return o.get(o.size() - 1) == p;
    }

    /** Joins the drawing's pieces into its video(s) again. */
    public static List<File> rebuild(Drawing d) throws IOException {
        List<File> out = new ArrayList<>();
        List<List<Piece>> runs = DrawingLibrary.sizeRuns(d);
        for (int i = 0; i < runs.size(); i++) {
            List<File> files = new ArrayList<>();
            for (Piece p : runs.get(i)) {
                File f = pieceFile(d, p);
                if (f.isFile()) files.add(f);
            }
            if (files.isEmpty()) continue;
            File target = joined(d, i);
            join(files, target);
            out.add(target);
        }
        /* a run that is gone (pieces moved away) leaves no stale video */
        for (int i = runs.size(); i < runs.size() + 8; i++) {
            File stale = joined(d, i);
            if (stale.isFile()) stale.delete();
        }
        return out;
    }

    /** "Yes, it continues that drawing." */
    public static Drawing accept(DrawingLibrary lib, Pending q) throws IOException {
        Drawing from = lib.drawing(q.drawingId);
        Piece piece = lib.pendingPiece(q);
        if (from == null || piece == null) {
            lib.pending.remove(q);
            save(lib);
            return from;
        }
        File oldFile = pieceFile(from, piece);
        File oldStill = still(from);
        Drawing to = lib.accept(q);
        move(oldFile, pieceFile(to, piece));
        if (lastIs(to, piece) && oldStill.isFile()) move(oldStill, still(to));
        if (lib.drawing(from.id) == null) {
            for (int i = 0; i < 8; i++) {
                File j = joined(from, i);
                if (j.isFile()) j.delete();
            }
            deleteFolder(folder(from));
        }
        save(lib);
        rebuild(to);
        return to;
    }

    public static void refuse(DrawingLibrary lib, Pending q) throws IOException {
        lib.refuse(q);
        save(lib);
    }

    /** Deletes a drawing's timelapse: its video(s), its pieces, its still. */
    public static void delete(DrawingLibrary lib, Drawing d) throws IOException {
        for (int i = 0; i < 8; i++) {
            File j = joined(d, i);
            if (j.isFile()) j.delete();
        }
        deleteFolder(folder(d));
        lib.remove(d);
        save(lib);
    }

    /* ---- files ---- */

    private static void move(File from, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (from.renameTo(to)) return;
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        from.delete();
    }

    private static void deleteFolder(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    /** The still for the timelapse window: the drawing as last left,
     *  at most 480 px. */
    static void writeStill(File out, byte[] bgra, int w, int h, int stride) {
        if (bgra == null) return;
        try {
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                int s = y * stride, d = y * w * 4;
                for (int x = 0; x < w; x++, s += 4, d += 4) {
                    rgba[d] = bgra[s + 2];
                    rgba[d + 1] = bgra[s + 1];
                    rgba[d + 2] = bgra[s];
                    rgba[d + 3] = (byte) 0xff;
                }
            }
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
            float k = Math.min(1f, 480f / Math.max(w, h));
            Bitmap small = k < 1f ? Bitmap.createScaledBitmap(full, Math.max(1, Math.round(w * k)),
                                                              Math.max(1, Math.round(h * k)), true) : full;
            File dir = out.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(out)) {
                small.compress(Bitmap.CompressFormat.JPEG, 85, o);
            }
            if (small != full) small.recycle();
            full.recycle();
        }
        catch (Throwable t) {
            Log.w(TAG, "timelapse: still not written", t);
        }
    }

    /** One MP4 from several of one size, the encoded frames copied over, each
     *  piece starting one frame after the last one ended. */
    static void join(List<File> pieces, File out) throws IOException {
        File part = new File(out.getPath() + ".part");
        part.delete();
        if (pieces.size() == 1) {
            Files.copy(pieces.get(0).toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        else {
            MediaMuxer muxer = new MediaMuxer(part.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean ok = false;
            try {
                int track = -1;
                long offset = 0;
                ByteBuffer buf = ByteBuffer.allocate(4 << 20);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                for (File f : pieces) {
                    MediaExtractor ex = new MediaExtractor();
                    try {
                        ex.setDataSource(f.getPath());
                        int t = -1;
                        for (int i = 0; i < ex.getTrackCount(); i++) {
                            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("video/")) { t = i; break; }
                        }
                        if (t < 0) continue;
                        ex.selectTrack(t);
                        if (track < 0) {
                            track = muxer.addTrack(ex.getTrackFormat(t));
                            muxer.start();
                        }
                        long last = 0;
                        while (true) {
                            int size = ex.readSampleData(buf, 0);
                            if (size < 0) break;
                            long pts = ex.getSampleTime();
                            int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            info.set(0, size, offset + pts, flags);
                            muxer.writeSampleData(track, buf, info);
                            last = Math.max(last, pts);
                            ex.advance();
                        }
                        offset += last + 1_000_000L / TimelapseVideo.FPS;
                    }
                    finally {
                        ex.release();
                    }
                }
                ok = track >= 0;
            }
            finally {
                try { muxer.stop(); } catch (RuntimeException e) { ok = false; }
                muxer.release();
                if (!ok) part.delete();
            }
            if (!ok) throw new IOException("could not join " + out.getName());
        }
        out.delete();
        if (!part.renameTo(out)) throw new IOException("cannot rename " + part);
    }
}
