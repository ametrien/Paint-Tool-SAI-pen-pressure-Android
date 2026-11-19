package com.winlator.saipen;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns the frames from C:\sai-timelapse\frames into MP4 videos (H.264, the
 * phone's own encoder), one per canvas and size (TimelapseFrames), into
 * Documents/SAI Drawings. Frames that made it into a video are deleted, as
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

    /** Encodes every video; returns the files made. Frames of a video that
     *  failed stay for the next try. */
    public static List<File> makeAll(File framesDir, File outDir, Progress progress) throws IOException {
        List<TimelapseFrames.Segment> segments = TimelapseFrames.segments(TimelapseFrames.list(framesDir));
        int total = 0, done = 0;
        for (TimelapseFrames.Segment s : segments) total += s.frames.size();
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);

        String stamp = new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(new Date());
        List<File> made = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (TimelapseFrames.Segment s : segments) {
            File out;
            int part = 1;
            do {
                out = new File(outDir, TimelapseFrames.fileName(s.name, stamp, part++));
            } while (out.exists() || used.contains(out.getName()));
            used.add(out.getName());

            final int before = done;
            final int all = total;
            encode(s, out, n -> progress.onFrame(before + n, all));
            done += s.frames.size();
            made.add(out);
            for (TimelapseFrames.Frame f : s.frames) f.file.delete();
            Log.i(TAG, "timelapse: " + out.getName() + ", " + s.frames.size() + " frames, " + out.length() + " bytes");
        }
        return made;
    }

    private interface FrameDone {
        void done(int n);
    }

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
                            readPixels(f.file, pixels, bytes);
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

    private static void readPixels(File f, byte[] into, int bytes) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            long skip = TimelapseFrames.HEADER_BYTES;
            while (skip > 0) {
                long k = in.skip(skip);
                if (k <= 0) throw new IOException("short frame " + f);
                skip -= k;
            }
            int got = 0;
            while (got < bytes) {
                int r = in.read(into, got, bytes - got);
                if (r < 0) throw new IOException("short frame " + f);
                got += r;
            }
        }
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
