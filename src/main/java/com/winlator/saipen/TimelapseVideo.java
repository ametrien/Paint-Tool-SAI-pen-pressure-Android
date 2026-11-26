package com.winlator.saipen;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the frames from C:\sai-timelapse\frames into MP4 videos (H.264, the
 * phone's own encoder), one per canvas and size (TimelapseFrames), into
 * Documents/SAI Drawings/Timelapses. Frames that made it into a video are deleted, as
 * the Mac encoder does.
 */
public final class TimelapseVideo {
    private static final String TAG = "SaiPen";

    /** The Mac encoder's speed. */
    public static final int FPS = 12;
    /* No held picture at the end: players stop on the last frame anyway,
     * and a 3 s, then 1 s still ending looked like a fault to the owner
     * (2026-11-19). Each frame, the last too, lasts 1/FPS. */

    public interface Progress {
        void onFrame(int done, int total);
    }

    private TimelapseVideo() {}

    /** C:\sai-timelapse\frames in the container. */
    public static File framesDir(File containerRoot) {
        return new File(containerRoot, ".wine/drive_c/sai-timelapse/frames");
    }

    public static int countFrames(File framesDir) {
        File[] files = framesDir.listFiles((d, n) -> n.endsWith(".frame"));
        return files == null ? 0 : files.length;
    }

    /** Frames waiting for a video: raw ones and packed ones. */
    public static int countAll(File containerRoot) {
        File packed = TimelapsePack.packedDir(containerRoot);
        return countFrames(framesDir(containerRoot)) + TimelapsePack.count(packed) + TimelapseLive.pendingFrames(packed);
    }

    /**
     * "Discard the frames, no video": every frame waiting for a video goes,
     * raw and packed, with their session folders, so the next timelapse
     * starts from scratch. Videos already made are not touched. Never while
     * SAI runs. Returns how many files could not be deleted.
     */
    public static int discardAll(File containerRoot) {
        int left = deleteFiles(framesDir(containerRoot));
        File packedRoot = TimelapsePack.packedDir(containerRoot);
        for (File s : TimelapsePack.sessions(packedRoot)) {
            for (File live : TimelapseLive.dirs(s)) TimelapseLive.remove(live);
            left += deleteFiles(s);
            s.delete();
        }
        return left;
    }

    private static int deleteFiles(File dir) {
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return 0;
        int left = 0;
        for (File f : files) if (!f.delete()) left++;
        return left;
    }

    /** What makeAll did. */
    public static final class Made {
        /** The drawings' videos made or joined again. */
        public final List<File> videos = new ArrayList<>();
        /** Canvases skipped because nothing was drawn on them. */
        public final List<String> skipped = new ArrayList<>();
        /** The sessions filed now (DrawingLibrary piece names), for the
         *  question "does this continue that drawing?". */
        public final List<String> filed = new ArrayList<>();
        public DrawingLibrary library;
    }

    /**
     * Raw frames left in the DLL's folder (SAI Pen was stopped while SAI
     * ran, or a version before 0.4.31) are packed into a session of their
     * own, so a new SAI start cannot write over them. Call it before SAI
     * starts and before making videos, never while SAI runs.
     */
    public static TimelapsePack.Result packLeftovers(File containerRoot) throws IOException {
        File frames = framesDir(containerRoot);
        if (countFrames(frames) == 0) return new TimelapsePack.Result();
        File packed = TimelapsePack.packedDir(containerRoot);
        TimelapsePack.Result r = TimelapsePack.packAll(frames, TimelapsePack.newSessionDir(packed, new Date()));
        Log.i(TAG, "timelapse: packed frames left from before: " + r);
        return r;
    }

    /** Encodes every video; returns the files made. Frames of a video that
     *  failed stay for the next try. */
    public static Made makeAll(File containerRoot, Progress progress) throws IOException {
        return makeAll(containerRoot, progress, null);
    }

    /** As above; `names` (may be null) knows what a new canvas was saved as,
     *  for a session whose frames all still say "NewCanvas1" (CanvasTime). */
    public static Made makeAll(File containerRoot, Progress progress, CanvasTime names) throws IOException {
        packLeftovers(containerRoot);
        File packedRoot = TimelapsePack.packedDir(containerRoot);
        List<TimelapseFrames.Segment> segments = TimelapseFrames.segments(TimelapsePack.list(packedRoot));
        /* frames a chunk made while SAI ran already holds go (TimelapseLive) */
        java.util.Set<String> withFrames = new java.util.HashSet<>();
        for (TimelapseFrames.Segment s : segments) {
            File live = TimelapseLive.dirOf(packedRoot, s);
            if (live.isDirectory()) {
                List<TimelapseFrames.Frame> rest = TimelapseLive.dropEncoded(s.frames, live);
                s.frames.clear();
                s.frames.addAll(rest);
            }
            withFrames.add(live.getAbsolutePath());
        }
        /* chunks with no frame left after them: their last frame stands in */
        java.util.Set<TimelapseFrames.Segment> chunksOnly = new java.util.HashSet<>();
        for (File session : TimelapsePack.sessions(packedRoot)) {
            for (File live : TimelapseLive.dirs(session)) {
                if (withFrames.contains(live.getAbsolutePath()) || TimelapseLive.chunks(live).isEmpty()) continue;
                TimelapseFrames.Frame last = TimelapseLive.frame(TimelapseLive.last(live), session.getName());
                if (last == null) continue;
                TimelapseFrames.Segment s = new TimelapseFrames.Segment(session.getName(), last.header.canvasId,
                    TimelapseLive.name(live), TimelapseFrames.even(last.header.width), TimelapseFrames.even(last.header.height));
                s.frames.add(last);
                segments.add(s);
                chunksOnly.add(s);
            }
        }
        segments.removeIf(s -> s.frames.isEmpty());
        Made made = new Made();
        List<TimelapseFrames.Segment> todo = new ArrayList<>();
        for (TimelapseFrames.Segment s : segments) {
            File live = TimelapseLive.dirOf(packedRoot, s);
            TimelapseFrames.Frame began = live.isDirectory() ? TimelapseLive.frame(TimelapseLive.first(live), s.session) : null;
            TimelapseFrames.Segment ends = s;
            if (began != null) {
                ends = new TimelapseFrames.Segment(s.session, s.canvasId, s.name, s.width, s.height);
                ends.frames.add(began);
                ends.frames.add(s.frames.get(s.frames.size() - 1));
            }
            if (TimelapseFrames.nothingDrawn(ends, TimelapsePack::readPixels)) {
                made.skipped.add(s.name);
                if (!chunksOnly.contains(s)) for (TimelapseFrames.Frame f : s.frames) f.file.delete();
                if (live.isDirectory()) TimelapseLive.remove(live);
                Log.i(TAG, "timelapse: " + s.name + " (" + s.session + "), " + s.frames.size()
                      + " frame(s), nothing drawn: no video");
            }
            else todo.add(s);
        }
        int total = 0, done = 0;
        for (TimelapseFrames.Segment s : todo) if (!chunksOnly.contains(s)) total += s.frames.size();
        File pieces = TimelapseShelf.piecesRoot();
        if (!todo.isEmpty() && !pieces.isDirectory() && !pieces.mkdirs()) throw new IOException("cannot create " + pieces);

        DrawingLibrary lib = TimelapseShelf.load();
        made.library = lib;
        Set<File> videos = new LinkedHashSet<>();
        for (TimelapseFrames.Segment s : todo) {
            File out = new File(pieces, "encoding.mp4");
            File live = TimelapseLive.dirOf(packedRoot, s);
            List<File> chunks = live.isDirectory() ? TimelapseLive.chunks(live) : new ArrayList<>();
            boolean onlyChunks = chunksOnly.contains(s);
            final int before = done;
            final int all = total;
            if (chunks.isEmpty()) {
                encode(s, out, n -> progress.onFrame(before + n, all));
            }
            else {
                /* the chunks made while SAI ran, then what was left after them */
                List<File> parts = new ArrayList<>(chunks);
                File rest = new File(pieces, "encoding-rest.mp4");
                if (!onlyChunks) {
                    encode(s, rest, n -> progress.onFrame(before + n, all));
                    parts.add(rest);
                }
                TimelapseShelf.join(parts, out);
                rest.delete();
            }
            if (!onlyChunks) done += s.frames.size();
            int frameCount = (onlyChunks ? 0 : s.frames.size()) + (live.isDirectory() ? TimelapseLive.encodedFrames(live) : 0);

            /* the fingerprints of where this session began and ended: how
             * the next session finds its drawing (DrawingLibrary) */
            TimelapseFrames.Frame first = live.isDirectory() ? TimelapseLive.frame(TimelapseLive.first(live), s.session) : null;
            if (first == null) first = s.frames.get(0);
            TimelapseFrames.Frame last = s.frames.get(s.frames.size() - 1);
            byte[] a = new byte[first.header.stride * first.header.height];
            TimelapsePack.readPixels(first, a);
            DrawingLibrary.Signature opening = DrawingLibrary.Signature.of(a, first.header.width, first.header.height, first.header.stride);
            byte[] b = new byte[last.header.stride * last.header.height];
            TimelapsePack.readPixels(last, b);
            DrawingLibrary.Signature closing = DrawingLibrary.Signature.of(b, last.header.width, last.header.height, last.header.stride);

            String name = s.name.isEmpty() && live.isDirectory() ? TimelapseLive.name(live) : s.name;
            String title = titleOf(name, startOf(s), names);
            DrawingLibrary.Drawing d = TimelapseShelf.add(lib, out, title, startOf(s), frameCount, s.width, s.height,
                                                          opening, closing, b, last.header.stride);
            made.filed.add(d.pieces.get(d.pieces.size() - 1).file);
            for (int i = 0; i < 8; i++) {
                File j = TimelapseShelf.joined(d, i);
                if (j.isFile()) videos.add(j);
            }
            if (!onlyChunks) for (TimelapseFrames.Frame f : s.frames) f.file.delete();
            if (live.isDirectory()) TimelapseLive.remove(live);
            Log.i(TAG, "timelapse: " + name + ", " + frameCount + " frames (" + chunks.size() + " made while drawing), into the drawing "
                  + d.folder + " (" + d.pieces.size() + " session(s))");
        }
        made.videos.addAll(videos);
        TimelapsePack.removeEmptySessions(packedRoot);
        return made;
    }

    /** When the SAI start began, from its folder name; now if unreadable. */
    /** The session's title: the frames' canvas name, or, for a new canvas
     *  saved under a name after the session began (so no frame saw it), that
     *  name (CanvasTime.savedAs). */
    static String titleOf(String name, long startedAt, CanvasTime names) {
        if (names == null || !DrawingLibrary.isDefaultTitle(name)) return name;
        String saved = names.savedAs(name, startedAt);
        return saved != null ? saved : name;
    }

    static long startOf(TimelapseFrames.Segment s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).parse(s.session.substring(0, 19)).getTime();
        }
        catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private interface FrameDone {
        void done(int n);
    }

    /** For TimelapseLive while SAI runs: one chunk of a canvas's frames. */
    static final TimelapseLive.Encoder LIVE = (frames, width, height, out) -> {
        TimelapseFrames.Segment s = new TimelapseFrames.Segment("", 0, "", width, height);
        s.frames.addAll(frames);
        encode(s, out, n -> { });
    };

    private static void encode(TimelapseFrames.Segment s, File out, FrameDone onFrame) throws IOException {
        int w = s.width, h = s.height;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        /* drawings are mostly flat colour: 4 bits a pixel a second is plenty */
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, w * h * 4));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer muxer = null;
        boolean ok = false;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            byte[] y = new byte[w * h], u = new byte[w * h / 4], v = new byte[w * h / 4];
            byte[] pixels = null;
            int n = s.frames.size();
            int inputs = n;
            int sent = 0;
            int[] track = {-1};
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                if (!inputDone) {
                    int idx = codec.dequeueInputBuffer(10_000);
                    if (idx >= 0) {
                        if (sent == inputs) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                        else {
                            TimelapseFrames.Frame f = s.frames.get(sent);
                            TimelapseFrames.Header hd = f.header;
                            int bytes = hd.stride * hd.height;
                            if (pixels == null || pixels.length < bytes) pixels = new byte[bytes];
                            TimelapsePack.readPixels(f, pixels);
                            TimelapseFrames.bgraToYuv420(pixels, 0, hd.stride, w, h, y, u, v);
                            Image image = codec.getInputImage(idx);
                            fill(image, y, u, v, w, h);
                            long pts = sent * 1_000_000L / FPS;
                            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0);
                            sent++;
                            onFrame.done(sent);
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track[0] = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                }
                else if (o >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(o);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0 && track[0] >= 0) muxer.writeSampleData(track[0], data, info);
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            ok = track[0] >= 0;
        }
        finally {
            try { codec.stop(); } catch (RuntimeException ignored) {}
            codec.release();
            if (muxer != null) {
                try { muxer.stop(); } catch (RuntimeException e) { ok = false; }
                muxer.release();
            }
            if (!ok) out.delete();
        }
        if (!ok) throw new IOException("the video encoder gave nothing for " + out.getName());
    }

    /** Copies the planes into the encoder's image, whatever its layout
     *  (planar or interleaved chroma: pixel and row strides say which). */
    private static void fill(Image image, byte[] y, byte[] u, byte[] v, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0], y, w, h);
        copyPlane(planes[1], u, w / 2, h / 2);
        copyPlane(planes[2], v, w / 2, h / 2);
    }

    private static void copyPlane(Image.Plane plane, byte[] src, int w, int h) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        for (int row = 0; row < h; row++) {
            int base = row * rowStride;
            if (pixelStride == 1) {
                buf.position(base);
                buf.put(src, row * w, w);
            }
            else {
                for (int col = 0; col < w; col++) buf.put(base + col * pixelStride, src[row * w + col]);
            }
        }
    }
}
