package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The video made while SAI runs, as the Mac encoder's --watch does: once a
 * canvas has CHUNK packed frames more than it needs to keep, they are
 * encoded into a piece of video (a "chunk") and deleted, so a long drawing
 * never piles up frames. After SAI closes, TimelapseVideo encodes what is
 * left and joins it after the chunks, then files the whole as before.
 *
 * Per SAI start (the packed session folder) and canvas and size:
 *   live/<canvas id hex>_<w>x<h>/
 *     cNNNNN_n<frames>_s<last seq>.mp4   finished chunks, in order
 *     first.zframe                       the canvas's first frame: where the
 *                                        session began (DrawingLibrary)
 *     last.zframe                        the last frame encoded so far
 *     name.txt                           the canvas name SAI last gave it
 *
 * Safe if the app dies at any point: a chunk is written as .part and
 * renamed only when whole; its frames go only after that; and frames the
 * name says are in a chunk already (seq up to its s<last seq>) are dropped,
 * not encoded twice. If the encoder can't be had (another app uses it), no
 * chunk is made and the frames wait for the video after SAI, as before.
 *
 * Runs on TimelapseRecorder's thread: lowest priority, on the slow cores;
 * the encoding itself is the device's hardware encoder.
 */
public final class TimelapseLive {
    /** Frames in a chunk: 10 s of video at 12 frames a second. */
    public static final int CHUNK = 120;
    static final String DIR = "live";
    private static final Pattern CHUNK_NAME = Pattern.compile("c(\\d+)_n(\\d+)_s(\\d+)\\.mp4");

    /** Encodes `frames` (one canvas, one size) into `out`. */
    public interface Encoder {
        void encode(List<TimelapseFrames.Frame> frames, int width, int height, File out) throws IOException;
    }

    private TimelapseLive() {}

    static File dir(File sessionDir, long canvasId, int width, int height) {
        return new File(new File(sessionDir, DIR), Long.toHexString(canvasId) + "_" + width + "x" + height);
    }

    /** The live folder of a segment of frames still packed; it may not exist. */
    public static File dirOf(File packedRoot, TimelapseFrames.Segment s) {
        return dir(new File(packedRoot, s.session), s.canvasId, s.width, s.height);
    }

    /** Every live folder of a SAI start. */
    public static List<File> dirs(File sessionDir) {
        List<File> out = new ArrayList<>();
        File[] d = new File(sessionDir, DIR).listFiles(File::isDirectory);
        if (d != null) Collections.addAll(out, d);
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** Its finished chunks, in order. */
    public static List<File> chunks(File dir) {
        List<File> out = new ArrayList<>();
        File[] f = dir.listFiles((d, n) -> CHUNK_NAME.matcher(n).matches());
        if (f != null) Collections.addAll(out, f);
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static long field(File chunk, int group) {
        Matcher m = CHUNK_NAME.matcher(chunk.getName());
        return m.matches() ? Long.parseLong(m.group(group)) : 0;
    }

    /** Frames already in its chunks. */
    public static int encodedFrames(File dir) {
        int n = 0;
        for (File c : chunks(dir)) n += (int) field(c, 2);
        return n;
    }

    /** The last frame number in its chunks; -1 for none. */
    public static long encodedUpTo(File dir) {
        long s = -1;
        for (File c : chunks(dir)) s = Math.max(s, field(c, 3));
        return s;
    }

    public static File first(File dir) {
        return new File(dir, "first" + TimelapsePack.PACKED);
    }

    public static File last(File dir) {
        return new File(dir, "last" + TimelapsePack.PACKED);
    }

    /** The canvas name SAI last gave it, "" if none known. */
    public static String name(File dir) {
        try {
            File f = new File(dir, "name.txt");
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
        }
        catch (IOException e) {
            return "";
        }
    }

    /** A packed frame file read back as a frame of `session`, or null. */
    public static TimelapseFrames.Frame frame(File f, String session) {
        if (!f.isFile()) return null;
        TimelapseFrames.Header h = TimelapseFrames.readHeaderOnly(f);
        return h == null ? null : new TimelapseFrames.Frame(f, h, session);
    }

    /**
     * While SAI runs: every canvas of this SAI start with more than CHUNK
     * frames waiting gets its oldest CHUNK encoded into a chunk, and those
     * frames go. At least one frame always stays packed, so the last state
     * is there for the video after SAI. Returns the frames encoded.
     */
    public static int encodeReady(File sessionDir, Encoder enc) throws IOException {
        int encoded = 0;
        for (TimelapseFrames.Segment s : TimelapseFrames.segments(TimelapsePack.listSession(sessionDir))) {
            File dir = dir(sessionDir, s.canvasId, s.width, s.height);
            List<TimelapseFrames.Frame> frames = dropEncoded(s.frames, dir);
            while (frames.size() > CHUNK) {
                List<TimelapseFrames.Frame> chunk = new ArrayList<>(frames.subList(0, CHUNK));
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                File first = first(dir);
                if (!first.isFile()) Files.copy(chunk.get(0).file.toPath(), first.toPath());
                int index = chunks(dir).size() + 1;
                long lastSeq = chunk.get(chunk.size() - 1).header.seq;
                String base = String.format(java.util.Locale.ROOT, "c%05d_n%d_s%d.mp4", index, chunk.size(), lastSeq);
                File part = new File(dir, base + ".part");
                part.delete();
                enc.encode(chunk, s.width, s.height, part);
                File done = new File(dir, base);
                if (!part.isFile() || part.length() == 0 || !part.renameTo(done)) {
                    part.delete();
                    throw new IOException("chunk not written: " + base);
                }
                Files.copy(chunk.get(chunk.size() - 1).file.toPath(), last(dir).toPath(), StandardCopyOption.REPLACE_EXISTING);
                String name = "";
                for (TimelapseFrames.Frame f : chunk) if (!f.header.name.isEmpty()) name = f.header.name;
                if (!name.isEmpty()) Files.write(new File(dir, "name.txt").toPath(), name.getBytes(StandardCharsets.UTF_8));
                for (TimelapseFrames.Frame f : chunk) f.file.delete();
                encoded += chunk.size();
                frames = new ArrayList<>(frames.subList(CHUNK, frames.size()));
            }
        }
        return encoded;
    }

    /** `frames` without those a chunk of `dir` already holds; those are
     *  deleted (the app died between a chunk and deleting its frames). */
    static List<TimelapseFrames.Frame> dropEncoded(List<TimelapseFrames.Frame> frames, File dir) {
        long upTo = encodedUpTo(dir);
        if (upTo < 0) return new ArrayList<>(frames);
        List<TimelapseFrames.Frame> out = new ArrayList<>();
        for (TimelapseFrames.Frame f : frames) {
            if (f.header.seq <= upTo) f.file.delete();
            else out.add(f);
        }
        return out;
    }

    /** Frames waiting in chunks, in every SAI start: for "a video is due". */
    public static int pendingFrames(File packedRoot) {
        int n = 0;
        for (File s : TimelapsePack.sessions(packedRoot)) for (File d : dirs(s)) n += encodedFrames(d);
        return n;
    }

    /** A live folder and all in it. */
    public static void remove(File dir) {
        File[] f = dir.listFiles();
        if (f != null) for (File x : f) x.delete();
        dir.delete();
        File parent = dir.getParentFile();
        String[] left = parent == null ? null : parent.list();
        if (left != null && left.length == 0) parent.delete();
    }
}
