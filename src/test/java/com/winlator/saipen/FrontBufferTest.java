package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FrontBufferTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private void write(String text) throws IOException {
        Files.write(new File(tmp.getRoot(), FrontBuffer.MODE_FILE).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void theModeFile() throws IOException {
        File d = tmp.getRoot();
        assertEquals(FrontBuffer.Mode.STANDARD, FrontBuffer.readMode(d));         /* no file */
        write(" Off\n");
        assertEquals(FrontBuffer.Mode.STANDARD, FrontBuffer.readMode(d));
        write("drawing");
        assertEquals(FrontBuffer.Mode.WHILE_DRAWING, FrontBuffer.readMode(d));
        write("on");
        assertEquals(FrontBuffer.Mode.FRONT, FrontBuffer.readMode(d));
        write("nonsense");
        assertEquals(FrontBuffer.Mode.STANDARD, FrontBuffer.readMode(d));         /* the default */
        assertNull(FrontBuffer.Mode.of("nonsense"));
    }

    @Test
    public void whatTheScreenWritesIsReadBack() {
        File d = tmp.getRoot();
        for (FrontBuffer.Mode m : FrontBuffer.Mode.values()) {
            assertTrue(FrontBuffer.writeMode(d, m));
            assertEquals(m, FrontBuffer.readMode(d));
        }
    }

    @Test
    public void withoutAChoiceItIsTheFirstRunDefault() {
        assertEquals("the owner's choice: Standard by default",
            FrontBuffer.Mode.STANDARD, FrontBuffer.readMode(tmp.getRoot()));
    }

    @Test
    public void whileDrawingFollowsThePenAndHoldsAfterIt() {
        assertTrue(FrontBuffer.wantSingle(true, 1000, 99_000));                  /* touching, however long */
        assertTrue(FrontBuffer.wantSingle(false, 1000, 1000 + FrontBuffer.HOLD_MS - 1));   /* just left */
        assertFalse(FrontBuffer.wantSingle(false, 1000, 1000 + FrontBuffer.HOLD_MS));
        assertFalse(FrontBuffer.wantSingle(false, -1, 5000));                    /* no pen yet */
    }
}
