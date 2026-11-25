package com.winlator.saipen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Packs the DLL's raw frames (C:\sai-timelapse\frames\NNNNNNNN.frame, 4 MB
 * for a 1024 x 1024 canvas) into small files, one folder per SAI start:
 * C:\sai-timelapse\packed\2026-11-23 19-24-05\NNNNNNNN.zframe.
 *
 * Why (2026-11-23, PLAN-TIMELAPSE-0.4.31.md):
 * - 4 MB a frame filled the 512 MB budget after 128 frames, and the DLL then
 *   deleted every second frame again and again: a 25 minute drawing kept 79
 *   frames, its sketch was lost. Packed, the owner's frames of that day were
 *   9 to 13 times smaller (measured on frames from her videos, which carry
 *   the video's noise; SAI's own frames are cleaner and pack better).
 * - The DLL numbers frames from 1 at every SAI start and replaced older
 *   frames with the same number. Packed frames are out of its folder at once,
 *   in a folder of their own per SAI start, so nothing is ever replaced.
 *
 * The packing loses nothing: the pixels come back byte for byte.
 *
 * A packed file: the frame's own 112-byte header, unchanged, then the pixels
 * as one deflate stream, each row stored as its difference from the row above
 * (PNG's "up" filter: paper and flat colour become zeros, which pack best).
 *
 * No Android types: the JVM tests check it with real files.
 */
public final class TimelapsePack {
    private TimelapsePack() {}

    public static final String PACKED = ".zframe";
    private static final String WRITING = ".ztmp";

    /** C:\sai-timelapse\packed in the container. */
    public static File packedDir(File containerRoot) {
        return new File(containerRoot, ".wine/drive_c/sai-timelapse/packed");
    }

    /* ---- one frame ---- */

    /** Raw pixels (rows of `stride` bytes) to the packed form. */
    public static byte[] packPixels(byte[] px, int stride, int height) {
        int bytes = stride * height;
        byte[] up = new byte[bytes];
        System.arraycopy(px, 0, up, 0, Math.min(stride, bytes));
        for (int i = stride; i < bytes; i++) up[i] = (byte) (px[i] - px[i - stride]);
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(up, 0, bytes);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, bytes / 8));
            byte[] chunk = new byte[64 * 1024];
            while (!d.finished()) {
                int n = d.deflate(chunk);
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
        finally {
            d.end();
        }
    }

    /** The packed form back to raw pixels, into `into` (stride * height bytes). */
    public static void unpackPixels(byte[] packed, int offset, int length, byte[] into, int stride, int height)
            throws IOException {
        int bytes = stride * height;
        Inflater inf = new Inflater();
        try {
            inf.setInput(packed, offset, length);
            int got = 0;
            while (got < bytes) {
                int n = inf.inflate(into, got, bytes - got);
                if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                got += n;
            }
            if (got != bytes) throw new IOException("packed frame too short: " + got + " of " + bytes);
        }
        catch (DataFormatException e) {
            throw new IOException("packed frame damaged", e);
        }
        finally {
            inf.end();
        }
        for (int i = stride; i < bytes; i++) into[i] = (byte) (into[i] + into[i - stride]);
    }

    /**
     * Packs one raw frame into `sessionDir` and deletes the raw file, only
     * after the packed one is whole on disk. Never replaces a packed frame.
     * Returns the packed file, or null when the raw one went away meanwhile
     * (the DLL thins its own folder) or could not be read whole.
     */
    public static File packOne(TimelapseFrames.Frame raw, File sessionDir) throws IOException {
        TimelapseFrames.Header h = raw.header;
        int bytes = h.stride * h.height;
        byte[] all;
        try {
            all = readAll(raw.file, TimelapseFrames.HEADER_BYTES + (long) bytes);
        }
        catch (IOException e) {
            if (!raw.file.exists()) return null;
            throw e;
        }
        if (all == null) return null;
        byte[] px = Arrays.copyOfRange(all, TimelapseFrames.HEADER_BYTES, TimelapseFrames.HEADER_BYTES + bytes);
        byte[] packed = packPixels(px, h.stride, h.height);

        if (!sessionDir.isDirectory() && !sessionDir.mkdirs()) throw new IOException("cannot create " + sessionDir);
        File tmp = new File(sessionDir, String.format(Locale.ROOT, "%08d", h.seq) + WRITING);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(all, 0, TimelapseFrames.HEADER_BYTES);
            out.write(packed);
            out.getFD().sync();
        }
        File dest = freeName(sessionDir, h.seq);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("cannot rename " + tmp + " to " + dest);
        }
        raw.file.delete();
        return dest;
    }

    /** NNNNNNNN.zframe, or NNNNNNNN-2.zframe if that is taken (it should
     *  never be: a folder is one SAI start, the DLL's numbers only rise). */
    private static File freeName(File dir, long seq) {
        String base = String.format(Locale.ROOT, "%08d", seq);
        File f = new File(dir, base + PACKED);
        for (int i = 2; f.exists(); i++) f = new File(dir, base + "-" + i + PACKED);
        return f;
    }

    /* ---- the frames folder ---- */

    /** What one packing pass did, for the log. */
    public static final class Result {
        public int frames;
        public long rawBytes, packedBytes;

        @Override public String toString() {
            return frames + " frame(s), " + (rawBytes >> 10) + " KB to " + (packedBytes >> 10) + " KB";
        }
    }

    /** Packs every whole raw frame, oldest first. The DLL's own .tmp files
     *  (a frame being written) are left alone. */
    public static Result packAll(File framesDir, File sessionDir) throws IOException {
        Result r = new Result();
        for (TimelapseFrames.Frame f : TimelapseFrames.list(framesDir)) {
            long rawLen = f.file.length();
            File p = packOne(f, sessionDir);
            if (p == null) continue;
            r.frames++;
            r.rawBytes += rawLen;
            r.packedBytes += p.length();
        }
        return r;
    }

    /** A new folder for one SAI start: "2026-11-23 19-24-05", the time it
     *  began, so folders sort in drawing order. "(2)" if taken. */
    public static File newSessionDir(File packedRoot, Date start) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(start);
        File f = new File(packedRoot, stamp);
        for (int i = 2; f.exists(); i++) f = new File(packedRoot, stamp + " (" + i + ")");
        return f;
    }

    /** Session folders, oldest first. */
    public static List<File> sessions(File packedRoot) {
        List<File> out = new ArrayList<>();
        File[] dirs = packedRoot.listFiles(File::isDirectory);
        if (dirs == null) return out;
        Collections.addAll(out, dirs);
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** Every packed frame, per session, each session in frame order. */
    public static List<TimelapseFrames.Frame> list(File packedRoot) {
        List<TimelapseFrames.Frame> out = new ArrayList<>();
        for (File s : sessions(packedRoot)) out.addAll(listSession(s));
        return out;
    }

    public static List<TimelapseFrames.Frame> listSession(File sessionDir) {
        List<TimelapseFrames.Frame> out = new ArrayList<>();
        File[] files = sessionDir.listFiles((d, n) -> n.endsWith(PACKED));
        if (files == null) return out;
        for (File f : files) {
            TimelapseFrames.Header h = TimelapseFrames.readHeaderOnly(f);
            if (h != null) out.add(new TimelapseFrames.Frame(f, h, sessionDir.getName()));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.header.seq, b.header.seq));
        return out;
    }

    public static int count(File packedRoot) {
        int n = 0;
        for (File s : sessions(packedRoot)) {
            String[] names = s.list((d, name) -> name.endsWith(PACKED));
            if (names != null) n += names.length;
        }
        return n;
    }

    public static long bytes(File packedRoot) {
        long n = 0;
        for (File s : sessions(packedRoot)) {
            File[] files = s.listFiles((d, name) -> name.endsWith(PACKED));
            if (files != null) for (File f : files) n += f.length();
        }
        return n;
    }

    /** Session folders with no frames left go (after a video, or thinning). */
    public static void removeEmptySessions(File packedRoot) {
        for (File s : sessions(packedRoot)) {
            File[] left = s.listFiles();
            if (left == null) continue;
            boolean any = false;
            for (File f : left) {
                if (f.getName().endsWith(WRITING)) f.delete();   /* a pack cut short */
                else any = true;
            }
            if (!any) s.delete();
        }
    }

    /* ---- the space they take ---- */

    /**
     * Past `budget` bytes, thins the session with the most frames: every
     * second frame in frame order (never its first or its last), again until
     * under budget or nothing more can go. Spread like this, a long drawing
     * keeps its whole span, only coarser, and one session never empties
     * another. Returns the frames deleted.
     *
     * Packed, a budget of 1 GB holds thousands of frames: this is a safety
     * net, not something a drawing session should meet.
     */
    public static int enforceBudget(File packedRoot, long budget) {
        int deleted = 0;
        long total = bytes(packedRoot);
        while (total > budget) {
            List<TimelapseFrames.Frame> biggest = null;
            for (File s : sessions(packedRoot)) {
                List<TimelapseFrames.Frame> l = listSession(s);
                if (biggest == null || l.size() > biggest.size()) biggest = l;
            }
            if (biggest == null || biggest.size() < 3) break;
            int before = deleted;
            for (int i = 1; i < biggest.size() - 1; i += 2) {
                File f = biggest.get(i).file;
                long len = f.length();
                if (f.delete()) {
                    deleted++;
                    total -= len;
                }
            }
            if (deleted == before) break;
        }
        return deleted;
    }

    /* ---- reading ---- */

    /** A frame's pixels, raw or packed, into `into` (stride * height bytes). */
    public static void readPixels(TimelapseFrames.Frame f, byte[] into) throws IOException {
        TimelapseFrames.Header h = f.header;
        int bytes = h.stride * h.height;
        if (f.file.getName().endsWith(PACKED)) {
            byte[] all = readAll(f.file, -1);
            if (all == null || all.length <= TimelapseFrames.HEADER_BYTES) throw new IOException("short frame " + f.file);
            unpackPixels(all, TimelapseFrames.HEADER_BYTES, all.length - TimelapseFrames.HEADER_BYTES, into, h.stride, h.height);
        }
        else {
            byte[] all = readAll(f.file, TimelapseFrames.HEADER_BYTES + (long) bytes);
            if (all == null) throw new IOException("short frame " + f.file);
            System.arraycopy(all, TimelapseFrames.HEADER_BYTES, into, 0, bytes);
        }
    }

    /** The file's first `length` bytes (all of it for -1); null if shorter. */
    private static byte[] readAll(File f, long length) throws IOException {
        long len = length < 0 ? f.length() : length;
        if (len > Integer.MAX_VALUE - 16) throw new IOException("frame too large " + f);
        byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int got = 0;
            while (got < b.length) {
                int r = in.read(b, got, b.length - got);
                if (r < 0) return null;
                got += r;
            }
        }
        return b;
    }
}
