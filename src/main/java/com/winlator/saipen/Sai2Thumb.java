package com.winlator.saipen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The small picture SAI Ver.2 keeps inside every .sai2 file, as an ordinary
 * JPEG that Android's BitmapFactory can open. Worked out from real files
 * (there is no published description):
 *
 *   0x00  "SAI-CANVAS-TYPE0"
 *   0x40  the chunk table, 16 bytes an entry: tag[4], u32, u64 file offset;
 *         it ends at the first entry whose tag is not four letters
 *   "thum" chunk (up to the next chunk):
 *         u32 width, u32 height, "jssf", u16 width, u16 height, u16 (3),
 *         64-byte luma and 64-byte chroma quantisation tables (zigzag order),
 *         then for each row of 8x8 blocks ((height + 7) / 8 of them): u16
 *         length + that row's JPEG scan data. No end marker: after the last
 *         row come 0 to 3 zero bytes (padding to a multiple of 4)
 *
 * The scan is baseline JPEG, YCbCr 4:4:4, with the standard Huffman tables of
 * the JPEG standard (Annex K), and the DC prediction starts over on each
 * block row. So the JPEG is: the headers put back, a restart interval of one
 * block row, and a restart marker between the rows.
 *
 * All numbers in the file are little-endian. tools/make-sai2-fixture.py
 * makes a test file the same way.
 */
public final class Sai2Thumb {
    private Sai2Thumb() {}

    static final byte[] MAGIC = "SAI-CANVAS-TYPE0".getBytes(StandardCharsets.US_ASCII);
    static final int TABLE = 0x40;
    /** A thumbnail is a few tens of KB; anything far bigger is not one. */
    static final int MAX_THUMB = 4 << 20;

    /** The thumbnail of `file` as JPEG bytes. */
    public static byte[] jpeg(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long length = f.length();
            byte[] head = new byte[(int) Math.min(length, 4096)];
            f.readFully(head);
            long[] range = thumRange(head, length);
            long size = range[1] - range[0];
            if (size > MAX_THUMB) throw new IOException("thumbnail chunk too big: " + size);
            byte[] thum = new byte[(int) size];
            f.seek(range[0]);
            f.readFully(thum);
            return toJpeg(thum);
        }
    }

    /** Where the "thum" chunk is: {start, end}. `head` is the start of the
     *  file, `length` its whole length. */
    static long[] thumRange(byte[] head, long length) throws IOException {
        if (head.length < TABLE || !startsWith(head, MAGIC)) throw new IOException("not a SAI Ver.2 canvas");
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        long start = -1;
        java.util.List<Long> offsets = new java.util.ArrayList<>();
        for (int at = TABLE; at + 16 <= head.length && isTag(head, at); at += 16) {
            long offset = b.getLong(at + 8);
            offsets.add(offset);
            if (head[at] == 't' && head[at + 1] == 'h' && head[at + 2] == 'u' && head[at + 3] == 'm') start = offset;
        }
        if (start < 0) throw new IOException("no thumbnail in the file");
        long end = length;
        for (long o : offsets) if (o > start && o < end) end = o;
        if (start >= length) throw new IOException("thumbnail past the end of the file");
        return new long[]{start, end};
    }

    /** The "thum" chunk -> a JPEG file. */
    static byte[] toJpeg(byte[] thum) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(thum).order(ByteOrder.LITTLE_ENDIAN);
        if (thum.length < 0x92 + 2) throw new IOException("thumbnail chunk too short");
        int w = b.getInt(0), h = b.getInt(4);
        if (thum[8] != 'j' || thum[9] != 's' || thum[10] != 's' || thum[11] != 'f')
            throw new IOException("unknown thumbnail encoding");
        if (w <= 0 || h <= 0 || w > 0xffff || h > 0xffff) throw new IOException("bad thumbnail size " + w + "x" + h);
        int rows = (h + 7) / 8;

        ByteArrayOutputStream out = new ByteArrayOutputStream(thum.length + 1024);
        out.write(0xff); out.write(0xd8);                                   // SOI
        segment(out, 0xdb, concat(new byte[]{0}, slice(thum, 0x12, 64),     // DQT
                                  new byte[]{1}, slice(thum, 0x52, 64)));
        segment(out, 0xc0, new byte[]{8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 3,   // SOF0
                                      1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1});
        for (byte[] table : HUFFMAN) segment(out, 0xc4, table);             // DHT
        int mcus = (w + 7) / 8;
        segment(out, 0xdd, new byte[]{(byte) (mcus >> 8), (byte) mcus});    // DRI
        segment(out, 0xda, new byte[]{3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0});  // SOS

        /* exactly `rows` rows: the file has no end marker (what follows is
         * 0 to 3 zero bytes, in the 48 real files looked at) */
        int at = 0x92;
        for (int row = 0; row < rows; row++) {
            if (at + 2 > thum.length) throw new IOException("thumbnail cut short at row " + row + " of " + rows);
            int n = b.getShort(at) & 0xffff;
            at += 2;
            if (n == 0) throw new IOException("empty row " + row + " of " + rows);
            if (at + n > thum.length) throw new IOException("thumbnail row " + row + " past the end");
            if (row > 0) { out.write(0xff); out.write(0xd0 + (row - 1) % 8); }  // RSTn
            out.write(thum, at, n);
            at += n;
        }
        out.write(0xff); out.write(0xd9);                                   // EOI
        return out.toByteArray();
    }

    /* JPEG Annex K tables: class/id, 16 counts, the values. */
    private static final byte[][] HUFFMAN = {
        table(0x00, "00010501010101010100000000000000", "000102030405060708090a0b"),
        table(0x10, "0002010303020403050504040000017d",
              "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa"),
        table(0x01, "00030101010101010101010000000000", "000102030405060708090a0b"),
        table(0x11, "00020102040403040705040400010277",
              "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa"),
    };

    private static byte[] table(int classId, String counts, String values) {
        return concat(new byte[]{(byte) classId}, hex(counts), hex(values));
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] body) {
        int n = body.length + 2;
        out.write(0xff); out.write(marker); out.write(n >> 8); out.write(n);
        out.write(body, 0, body.length);
    }

    private static boolean isTag(byte[] d, int at) {
        for (int i = 0; i < 4; i++) {
            int c = d[at + i];
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] d, byte[] prefix) {
        if (d.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (d[i] != prefix[i]) return false;
        return true;
    }

    private static byte[] slice(byte[] d, int from, int n) {
        byte[] r = new byte[n];
        System.arraycopy(d, from, r, 0, n);
        return r;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }
}
