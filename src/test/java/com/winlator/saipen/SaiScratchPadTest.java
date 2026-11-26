package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaiScratchPadTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** ScratchPad1.saispad as SAI made it (PC and tablet, 2026-11-26): never drawn on. */
    static final String EMPTY_PAD =
        "5341492d535041442d2d2d545950453000010000c70000006e030000b1581c0002000000"
        + "000000000000000000000000000000000000000000000000000000006c61797202000000"
        + "60000000000000006c70697802000000b0000000000000006c6179720200000000000000"
        + "010000006e6f726d000000000000000000000000000000000000000000000000"
        + "6e6f726d64000000000001006e616d650c00000005004c00610079006500720000000000"
        + "00000000";

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    @Test
    public void theRealEmptyPad() throws Exception {
        byte[] b = hex(EMPTY_PAD);
        assertEquals(180, b.length);
        File f = tmp.newFile("ScratchPad1.saispad");
        Files.write(f.toPath(), b);
        SaiScratchPad.Info info = SaiScratchPad.read(f);
        assertEquals(SaiScratchPad.EMPTY, info.state);
        assertEquals(Arrays.asList("Layer"), info.layers);
        assertEquals(4, info.pixelBytes);
    }

    @Test
    public void somethingInThePixelsIsADrawing() {
        byte[] b = Arrays.copyOf(hex(EMPTY_PAD), 180 + 64);
        for (int i = 0xb0; i < b.length; i++) b[i] = (byte) (i * 7);
        SaiScratchPad.Info info = SaiScratchPad.parse(b);
        assertEquals(SaiScratchPad.DRAWN, info.state);
        assertEquals(68, info.pixelBytes);
    }

    @Test
    public void notAPadOrCutShort() throws Exception {
        assertEquals(SaiScratchPad.UNREADABLE, SaiScratchPad.parse("SAI-CANVAS-TYPE0".getBytes(StandardCharsets.US_ASCII)).state);
        assertEquals(SaiScratchPad.UNREADABLE, SaiScratchPad.parse(Arrays.copyOf(hex(EMPTY_PAD), 0x50)).state);
        /* a chunk said to be past the end */
        byte[] b = hex(EMPTY_PAD);
        b[0x58] = (byte) 0xff; b[0x59] = 0x7f;
        assertEquals(SaiScratchPad.UNREADABLE, SaiScratchPad.parse(b).state);
        assertEquals(SaiScratchPad.UNREADABLE, SaiScratchPad.read(new File(tmp.getRoot(), "missing.saispad")).state);
    }
}
