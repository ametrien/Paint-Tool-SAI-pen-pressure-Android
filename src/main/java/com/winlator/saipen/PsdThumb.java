package com.winlator.saipen;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * The small JPEG picture Photoshop (and SAI, when it saves a .psd) keeps in a
 * .psd or .psb, for the Drawings cards. From Adobe's published file format:
 *
 *   26-byte header: "8BPS", u16 version (1 psd, 2 psb), 6 reserved, u16
 *   channels, u32 height, u32 width, u16 depth, u16 colour mode
 *   colour mode data: u32 length + data
 *   image resources: u32 length, then blocks: "8BIM", u16 id, a Pascal name
 *   padded to an even length (the length byte counts), u32 size, data padded
 *   to an even size
 *   resource 1036 (0x040C), the thumbnail: 28 bytes (u32 format, 1 = JPEG,
 *   then width, height, row bytes, total size, compressed size, u16 bits,
 *   u16 planes), then the JPEG file itself
 *
 * Big-endian. Only the start of the file is read. No Android types.
 */
public final class PsdThumb {
    private PsdThumb() {}

    static final int THUMBNAIL = 0x040C;
    /** A thumbnail is a few tens of KB; anything far bigger is not one. */
    static final int MAX_THUMB = 4 << 20;

    /** The thumbnail of `file` as JPEG bytes. */
    public static byte[] jpeg(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536))) {
            return jpeg(in);
        }
    }

    static byte[] jpeg(DataInputStream in) throws IOException {
        if (in.readInt() != 0x38425053) throw new IOException("not a .psd or .psb");   // "8BPS"
        int version = in.readUnsignedShort();
        if (version != 1 && version != 2) throw new IOException("unknown PSD version " + version);
        skip(in, 6 + 2 + 4 + 4 + 2 + 2);
        skip(in, u32(in));                                   // colour mode data
        long left = u32(in);                                 // image resources
        while (left >= 12) {
            if (in.readInt() != 0x3842494D) throw new IOException("broken image resources");  // "8BIM"
            int id = in.readUnsignedShort();
            int nameLen = in.readUnsignedByte();
            int name = (nameLen + 1 + 1) & ~1;               // with its length byte, made even
            skip(in, name - 1);
            long size = u32(in);
            long padded = (size + 1) & ~1L;
            left -= 4 + 2 + name + 4 + padded;
            if (id == THUMBNAIL) {
                if (size < 28 || size > MAX_THUMB) throw new IOException("bad thumbnail size " + size);
                int format = in.readInt();
                skip(in, 24);
                if (format != 1) throw new IOException("thumbnail is not a JPEG");
                byte[] jpeg = new byte[(int) size - 28];
                in.readFully(jpeg);
                return jpeg;
            }
            skip(in, padded);
        }
        throw new IOException("no thumbnail in the file");
    }

    private static long u32(DataInputStream in) throws IOException {
        return in.readInt() & 0xffffffffL;
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }
}
