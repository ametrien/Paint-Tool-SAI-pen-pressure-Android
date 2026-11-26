package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What can be told about SAI's scratch pad file (settings/scratchpad/
 * ScratchPad1.saispad) without SAI. Worked out from the files on a PC and on
 * the Tab S6 Lite (2026-11-26, both 180 bytes, never drawn on); there is no
 * published description:
 *
 *   0x00  "SAI-SPAD---TYPE0"
 *   0x10  numbers whose meaning is not known (256, 199, 878, ...)
 *   0x40  the chunk table, as in a .sai2 drawing (Sai2Thumb): 16 bytes an
 *         entry, tag[4], u32, u64 file offset; it ends where the first
 *         chunk starts
 *   "layr" chunk: a layer; somewhere in it "name", u32 byte count, u16
 *         letter count, the name in UTF-16 ("Layer")
 *   "lpix" chunk: that layer's pixels. An empty pad has four zero bytes.
 *
 * Unlike a .sai2 drawing, the pad has no "thum" chunk: no small picture to
 * show. What SAI puts in "lpix" once something is drawn is not known, so a
 * pad with something on it can only be told apart from an empty one.
 *
 * All numbers little-endian. No Android types: the JVM tests run it on the Mac.
 */
public final class SaiScratchPad {
    private SaiScratchPad() {}

    static final byte[] MAGIC = "SAI-SPAD---TYPE0".getBytes(StandardCharsets.US_ASCII);
    static final int TABLE = 0x40;
    /** The pad is a small sheet; more than this is not read. */
    static final int MAX = 64 << 20;

    public static final int UNREADABLE = 0, EMPTY = 1, DRAWN = 2;

    public static final class Info {
        /** UNREADABLE, EMPTY or DRAWN. */
        public final int state;
        public final List<String> layers;
        /** Bytes of pixel data, all layers together. */
        public final long pixelBytes;
        Info(int state, List<String> layers, long pixelBytes) {
            this.state = state; this.layers = layers; this.pixelBytes = pixelBytes;
        }
    }

    public static Info read(File f) {
        if (f == null || !f.isFile() || f.length() > MAX) return new Info(UNREADABLE, new ArrayList<>(), 0);
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) in.length()];
            in.readFully(b);
            return parse(b);
        } catch (IOException e) {
            return new Info(UNREADABLE, new ArrayList<>(), 0);
        }
    }

    static Info parse(byte[] b) {
        List<String> layers = new ArrayList<>();
        if (b.length < TABLE + 16 || !startsWith(b, MAGIC)) return new Info(UNREADABLE, layers, 0);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        List<long[]> chunks = new ArrayList<>();   // {tag start in table, offset}
        /* the table ends where the first chunk starts (on the pad, the
         * layer chunk follows right after it, with a tag of its own) */
        long first = b.length;
        for (int at = TABLE; at + 16 <= first && isTag(b, at); at += 16) {
            long off = buf.getLong(at + 8);
            if (off < at + 16 || off > b.length) return new Info(UNREADABLE, layers, 0);
            chunks.add(new long[] {at, off});
            first = Math.min(first, off);
        }
        if (chunks.isEmpty()) return new Info(UNREADABLE, layers, 0);
        java.util.Collections.sort(chunks, (x, y) -> Long.compare(x[1], y[1]));
        long pixels = 0;
        boolean drawn = false, anyPixels = false;
        for (long[] c : chunks) {
            int at = (int) c[0];
            int start = (int) c[1];
            int end = b.length;
            for (long[] o : chunks) if (o[1] > start && o[1] < end) end = (int) o[1];
            if (tag(b, at, "layr")) {
                String name = name(b, start, end);
                layers.add(name != null ? name : "");
            } else if (tag(b, at, "lpix")) {
                anyPixels = true;
                pixels += end - start;
                for (int i = start; i < end; i++) if (b[i] != 0) { drawn = true; break; }
            }
        }
        if (!anyPixels) return new Info(UNREADABLE, layers, 0);
        return new Info(drawn ? DRAWN : EMPTY, layers, pixels);
    }

    /** The "name" inside a layer chunk: u32 bytes, u16 letters, UTF-16. */
    static String name(byte[] b, int start, int end) {
        for (int i = start; i + 10 <= end; i++) {
            if (b[i] != 'n' || b[i + 1] != 'a' || b[i + 2] != 'm' || b[i + 3] != 'e') continue;
            int letters = (b[i + 8] & 0xff) | (b[i + 9] & 0xff) << 8;
            int from = i + 10;
            if (letters <= 0 || letters > 256 || from + letters * 2 > end) return null;
            return new String(b, from, letters * 2, StandardCharsets.UTF_16LE);
        }
        return null;
    }

    private static boolean tag(byte[] b, int at, String t) {
        for (int k = 0; k < 4; k++) if (b[at + k] != t.charAt(k)) return false;
        return true;
    }

    private static boolean isTag(byte[] b, int at) {
        for (int k = 0; k < 4; k++) {
            int c = b[at + k];
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] b, byte[] p) {
        if (b.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (b[i] != p[i]) return false;
        return true;
    }
}
