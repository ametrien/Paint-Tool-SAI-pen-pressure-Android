package com.winlator.saipen;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The timelapse frames our wintab32.dll writes inside SAI (timelapse_win.h,
 * C:\sai-timelapse\frames\NNNNNNNN.frame), and how they become videos: the
 * same rules as the Mac encoder (timelapse-encoder/EncoderCore.swift).
 *
 * - A frame is a 112-byte header (TL_FRAME_HDR) and BGRA pixels.
 * - One video per canvas, keyed by the canvas's address in SAI, not its name
 *   (a rename must not split a video, two canvases with one name must not
 *   merge).
 * - A new file when the size changes: a video has one size.
 * - Even width and height (H.264), rounding down.
 *
 * No Android types: the JVM tests check it with real frame files.
 */
public final class TimelapseFrames {
    private TimelapseFrames() {}

    public static final int HEADER_BYTES = 112;
    public static final String MAGIC = "SAITLF2";

    /** One frame file's header. */
    public static final class Header {
        public final int width, height, stride, format;
        public final long seq, tickMs, canvasId;
        public final String name;

        Header(int width, int height, int stride, int format, long seq, long tickMs, long canvasId, String name) {
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.format = format;
            this.seq = seq;
            this.tickMs = tickMs;
            this.canvasId = canvasId;
            this.name = name;
        }
    }

    /** A frame on disk. */
    public static final class Frame {
        public final File file;
        public final Header header;

        Frame(File file, Header header) {
            this.file = file;
            this.header = header;
        }
    }

    /** Frames of one canvas at one size: one video file. */
    public static final class Segment {
        public final long canvasId;
        public String name;
        public final int width, height;
        public final List<Frame> frames = new ArrayList<>();

        Segment(long canvasId, String name, int width, int height) {
            this.canvasId = canvasId;
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Reads a header; null for anything that is not a whole, sane frame (a
     * frame being written right now is an expected case, not an error).
     * Little-endian: magic[8], width, height, stride, format (u32), seq,
     * tick_ms, canvas_id (u64), name[64].
     */
    public static Header parse(byte[] b) {
        if (b == null || b.length < HEADER_BYTES) return null;
        int end = 0;
        while (end < 8 && b[end] != 0) end++;
        if (!MAGIC.equals(new String(b, 0, end, StandardCharsets.US_ASCII))) return null;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        long w = bb.getInt(8) & 0xffffffffL, h = bb.getInt(12) & 0xffffffffL, s = bb.getInt(16) & 0xffffffffL;
        if (w <= 0 || h <= 0 || w > 100000 || h > 100000 || s < w * 4) return null;
        int nameEnd = 48;
        while (nameEnd < 112 && b[nameEnd] != 0) nameEnd++;
        return new Header((int) w, (int) h, (int) s, bb.getInt(20), bb.getLong(24), bb.getLong(32), bb.getLong(40),
                          new String(b, 48, nameEnd - 48, StandardCharsets.UTF_8));
    }

    public static Header readHeader(File f) {
        byte[] b = new byte[HEADER_BYTES];
        try (InputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) return null;
                n += r;
            }
        }
        catch (IOException e) {
            return null;
        }
        Header h = parse(b);
        /* a frame still being written is shorter than its header says */
        if (h != null && f.length() < HEADER_BYTES + (long) h.stride * h.height) return null;
        return h;
    }

    /** All whole frames in the folder, oldest first. */
    public static List<Frame> list(File dir) {
        List<Frame> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".frame")) continue;
            Header h = readHeader(f);
            if (h != null) out.add(new Frame(f, h));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.header.seq, b.header.seq));
        return out;
    }

    /** Frames, oldest first, into videos: per canvas, a new one on a size change. */
    public static List<Segment> segments(List<Frame> frames) {
        Map<Long, List<Frame>> byCanvas = new LinkedHashMap<>();
        for (Frame f : frames) {
            List<Frame> l = byCanvas.get(f.header.canvasId);
            if (l == null) byCanvas.put(f.header.canvasId, l = new ArrayList<>());
            l.add(f);
        }
        List<Segment> out = new ArrayList<>();
        for (List<Frame> l : byCanvas.values()) {
            Segment cur = null;
            for (Frame f : l) {
                int w = even(f.header.width), h = even(f.header.height);
                if (cur == null || cur.width != w || cur.height != h) {
                    cur = new Segment(f.header.canvasId, f.header.name, w, h);
                    out.add(cur);
                }
                cur.frames.add(f);
                if (!f.header.name.isEmpty()) cur.name = f.header.name;   /* the last name wins */
            }
        }
        return out;
    }

    /** H.264 wants even sizes: round down, losing one row or column at most. */
    public static int even(int v) {
        return v - (v % 2);
    }

    /** "NewCanvas1 timelapse 2026-11-19 00-40.mp4", "... (2).mp4" for a
     *  canvas's second size. Characters a file name cannot hold go. */
    public static String fileName(String canvasName, String stamp, int part) {
        String n = canvasName == null ? "" : canvasName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (n.isEmpty()) n = "SAI";
        return n + " timelapse " + stamp + (part > 1 ? " (" + part + ")" : "") + ".mp4";
    }

    /**
     * One BGRA frame to YUV 4:2:0 (BT.601, limited range), the planes a video
     * encoder takes: y is w*h, u and v (w/2)*(h/2); w and h even. Each chroma
     * value is the average of its 2x2 block.
     */
    public static void bgraToYuv420(byte[] src, int offset, int stride, int w, int h,
                                    byte[] y, byte[] u, byte[] v) {
        for (int row = 0; row < h; row++) {
            int p = offset + row * stride;
            for (int col = 0; col < w; col++, p += 4) {
                int b = src[p] & 0xff, g = src[p + 1] & 0xff, r = src[p + 2] & 0xff;
                y[row * w + col] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        int cw = w / 2;
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < cw; col++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = offset + (row * 2 + dy) * stride + (col * 2 + dx) * 4;
                        b += src[p] & 0xff;
                        g += src[p + 1] & 0xff;
                        r += src[p + 2] & 0xff;
                    }
                }
                r = (r + 2) / 4;
                g = (g + 2) / 4;
                b = (b + 2) / 4;
                u[row * cw + col] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                v[row * cw + col] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
    }
}
