package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PsdThumbTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};

    /** A .psd up to its image resources: `before` (an odd-sized resource
     *  with a name, to test the padding), then the thumbnail if `thumb`. */
    private static byte[] psd(int version, boolean thumb) throws IOException {
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        DataOutputStream r = new DataOutputStream(res);
        r.writeBytes("8BIM"); r.writeShort(0x03ED);
        r.writeByte(3); r.writeBytes("abc");                 // name: 1 + 3 bytes, already even
        r.writeInt(5); r.write(new byte[]{9, 9, 9, 9, 9}); r.writeByte(0);   // odd size, padded
        r.writeBytes("8BIM"); r.writeShort(0x0409);           // the old BGR thumbnail: skipped
        r.writeByte(0); r.writeByte(0);
        r.writeInt(2); r.writeShort(7);
        if (thumb) {
            r.writeBytes("8BIM"); r.writeShort(0x040C);
            r.writeByte(0); r.writeByte(0);
            r.writeInt(28 + JPEG.length);
            r.writeInt(1); r.writeInt(160); r.writeInt(120); r.writeInt(480);
            r.writeInt(57600); r.writeInt(JPEG.length); r.writeShort(24); r.writeShort(1);
            r.write(JPEG); r.writeByte(0);                    // 35 bytes: padded
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(all);
        o.writeBytes("8BPS"); o.writeShort(version); o.write(new byte[6]);
        o.writeShort(3); o.writeInt(1200); o.writeInt(1600); o.writeShort(8); o.writeShort(3);
        o.writeInt(4); o.writeInt(0);                         // colour mode data
        o.writeInt(res.size()); o.write(res.toByteArray());
        o.writeInt(0); o.write(new byte[64]);                 // layers, image data: not read
        return all.toByteArray();
    }

    private File file(byte[] b) throws IOException {
        File f = tmp.newFile("x.psd");
        Files.write(f.toPath(), b);
        return f;
    }

    @Test
    public void findsTheJpegPastOtherResources() throws Exception {
        assertArrayEquals(JPEG, PsdThumb.jpeg(file(psd(1, true))));
    }

    @Test
    public void psbToo() throws Exception {
        assertArrayEquals(JPEG, PsdThumb.jpeg(file(psd(2, true))));
    }

    @Test
    public void noThumbnail() throws Exception {
        try {
            PsdThumb.jpeg(file(psd(1, false)));
            fail();
        }
        catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no thumbnail"));
        }
    }

    @Test
    public void notAPsd() throws Exception {
        try {
            PsdThumb.jpeg(file("SAI-CANVAS-TYPE0".getBytes()));
            fail();
        }
        catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not a .psd"));
        }
    }
}
