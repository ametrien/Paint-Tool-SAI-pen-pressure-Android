package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * tests/fixtures/sai2-thumb.sai2 is made by tools/make-sai2-fixture.py: a
 * 37 x 21 thumbnail with red, green, blue and white quarters, stored the way
 * SAI Ver.2 stores it. Real files from SAI (46 drawings, square, portrait and
 * landscape) were checked by hand with tools/sai2-thumb.py; they are not in
 * the repository.
 */
public class Sai2ThumbTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final int W = 37, H = 21;

    private byte[] fixture() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sai2-thumb.sai2")) {
            if (in == null) throw new AssertionError("sai2-thumb.sai2 not on the test classpath");
            return in.readAllBytes();
        }
    }

    private File write(byte[] d) throws IOException {
        File f = tmp.newFile("t.sai2");
        Files.write(f.toPath(), d);
        return f;
    }

    /** The desktop JVM's JPEG reader. The tests compile against android.jar,
     *  which has no javax.imageio, but they run on a desktop JVM that has it. */
    private static final class Picture {
        private final Object image;
        private Picture(Object image) { this.image = image; }

        static Picture read(byte[] jpeg) throws Exception {
            Object img = Class.forName("javax.imageio.ImageIO")
                .getMethod("read", InputStream.class).invoke(null, new ByteArrayInputStream(jpeg));
            assertNotNull("not a JPEG Java can read", img);
            return new Picture(img);
        }

        int getWidth() throws Exception { return (int) image.getClass().getMethod("getWidth").invoke(image); }
        int getHeight() throws Exception { return (int) image.getClass().getMethod("getHeight").invoke(image); }
        int getRGB(int x, int y) throws Exception {
            return (int) image.getClass().getMethod("getRGB", int.class, int.class).invoke(image, x, y);
        }
    }

    private static void assertNear(String where, int want, int rgb) {
        int[] w = {want >> 16 & 255, want >> 8 & 255, want & 255};
        int[] g = {rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255};
        for (int i = 0; i < 3; i++)
            assertTrue(where + ": want " + Integer.toHexString(want) + " got " + Integer.toHexString(rgb & 0xffffff),
                       Math.abs(w[i] - g[i]) <= 8);
    }

    @Test
    public void thumbnailComesOutAsTheRightPicture() throws Exception {
        byte[] jpeg = Sai2Thumb.jpeg(write(fixture()));
        Picture img = Picture.read(jpeg);
        assertEquals(W, img.getWidth());
        assertEquals(H, img.getHeight());
        // the middle of each quarter, and corners in the partial edge blocks
        assertNear("red", 0xff0000, img.getRGB(4, 4));
        assertNear("green", 0x00ff00, img.getRGB(30, 4));
        assertNear("blue", 0x0000ff, img.getRGB(4, 17));
        assertNear("white", 0xffffff, img.getRGB(30, 17));
        assertNear("last pixel", 0xffffff, img.getRGB(W - 1, H - 1));
        assertNear("first pixel of the last block row", 0x0000ff, img.getRGB(0, 16));
    }

    @Test
    public void startsAndEndsLikeAJpeg() throws Exception {
        byte[] j = Sai2Thumb.jpeg(write(fixture()));
        assertEquals(0xff, j[0] & 255);
        assertEquals(0xd8, j[1] & 255);
        assertEquals(0xff, j[j.length - 2] & 255);
        assertEquals(0xd9, j[j.length - 1] & 255);
    }

    @Test
    public void notASaiFile() throws Exception {
        byte[] d = fixture();
        d[0] = 'X';
        expectFailure(d);
    }

    @Test
    public void noThumbnailChunk() throws Exception {
        byte[] d = fixture();
        d[0x40] = 'x'; d[0x41] = 'x'; d[0x42] = 'x'; d[0x43] = 'x';   // "thum" -> "xxxx"
        expectFailure(d);
    }

    @Test
    public void otherEncoding() throws Exception {
        byte[] d = fixture();
        int thum = (int) Sai2Thumb.thumRange(d, d.length)[0];
        d[thum + 8] = 'p';   // "jssf" -> "pssf"
        expectFailure(d);
    }

    @Test
    public void cutShortFile() throws Exception {
        byte[] d = fixture();
        byte[] cut = new byte[d.length - 40];
        System.arraycopy(d, 0, cut, 0, cut.length);
        expectFailure(cut);
    }

    @Test
    public void heightThatDoesNotMatchTheRows() throws Exception {
        byte[] d = fixture();
        int thum = (int) Sai2Thumb.thumRange(d, d.length)[0];
        d[thum + 4] = 40;   // 21 -> 40 high: 5 block rows, the file has 3
        expectFailure(d);
    }

    /** SAI's files have no end marker after the last row, only 0 to 3 zero
     *  bytes (all four seen in 48 real files). Version 1 of this class waited
     *  for a zero length and failed on every one of them. */
    @Test
    public void noEndMarkerAndAnyPadding() throws Exception {
        byte[] d = fixture();
        long[] r = Sai2Thumb.thumRange(d, d.length);
        byte[] thum = java.util.Arrays.copyOfRange(d, (int) r[0], (int) r[1]);
        int end = 0x92;
        for (int row = 0; row < (H + 7) / 8; row++) end += 2 + ((thum[end] & 255) | (thum[end + 1] & 255) << 8);
        for (int pad = 0; pad <= 3; pad++) {
            byte[] padded = java.util.Arrays.copyOf(thum, end + pad);   // zeros after the rows
            Picture img = Picture.read(Sai2Thumb.toJpeg(padded));
            assertEquals("padding " + pad, W, img.getWidth());
            assertNear("padding " + pad + ", white", 0xffffff, img.getRGB(30, 17));
        }
    }

    @Test
    public void lastRowCutShort() throws Exception {
        byte[] d = fixture();
        long[] r = Sai2Thumb.thumRange(d, d.length);
        byte[] thum = java.util.Arrays.copyOfRange(d, (int) r[0], (int) r[1]);
        int end = 0x92;
        for (int row = 0; row < (H + 7) / 8; row++) end += 2 + ((thum[end] & 255) | (thum[end + 1] & 255) << 8);
        try {
            Sai2Thumb.toJpeg(java.util.Arrays.copyOf(thum, end - 1));
            fail("read a thumbnail with its last row cut short");
        }
        catch (IOException expected) {}
    }

    @Test
    public void thumbnailEndsAtTheNextChunk() throws Exception {
        byte[] d = fixture();
        long[] r = Sai2Thumb.thumRange(d, d.length + 1000L);
        assertEquals(d.length, r[1]);   // the "intg" entry points at the end of the thumbnail
        assertArrayEquals(new long[]{0x60, d.length}, r);
    }

    private void expectFailure(byte[] d) throws Exception {
        File f = write(d);
        try {
            Sai2Thumb.jpeg(f);
            fail("read a broken file without complaint");
        }
        catch (IOException expected) {}
    }
}
