package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BrowseIndexTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private void write(String path, String text) throws Exception {
        File f = new File(tmp.getRoot(), path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void readsEveryKindAndEachToolOnce() throws Exception {
        write("brushtex/canvas.bmp", "x");
        write("brushfom/brshape/Snow.bmp", "x");
        write("brushfom/brshape/Snow.ini", "Density = 50\n");
        write("custool/nrm/1.saitdat", "name=U:Pencil\ncursize=I:90\n.\n");
        write("custool/nrm/_1.saitgrp", "name=U:Basic\n.\n0=1\n.\n");
        write("custool/nrm/_2.saitgrp", "name=U:Mine\n.\n0=1\n.\n");
        write("settings2.ssd", "x");
        BrowseIndex idx = BrowseIndex.read(tmp.getRoot());
        assertEquals(2, idx.pictureCount());
        assertEquals(2, idx.toolSets.size());
        assertEquals(1, idx.tools().size());
        assertEquals(1, idx.withSettings.size());
        assertTrue(idx.preferencesTime > 0);
    }

    @Test
    public void searchMatchesEveryWord() {
        assertTrue(BrowseIndex.matches("Milky way", "way milk"));
        assertTrue(BrowseIndex.matches("Pencil", " "));
        assertFalse(BrowseIndex.matches("Pencil", "pen brush"));
    }
}
