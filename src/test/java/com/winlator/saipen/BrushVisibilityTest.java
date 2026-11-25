package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BrushVisibilityTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File settings() throws IOException {
        return tmp.newFolder("SAIv2", "settings");
    }

    private static File put(File dir, String path, String text) throws IOException {
        File f = new File(dir, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void hiddenFolderIsBesideSettings() throws IOException {
        File s = settings();
        assertEquals(new File(s.getParentFile(), "hidden-from-sai"), BrushVisibility.hiddenFolder(s));
    }

    @Test
    public void hideMovesPictureAndItsIniOutOfSaisSight() throws IOException {
        File s = settings();
        File p = put(s, "brushfom/brshape/cream2.bmp", "shape");
        put(s, "brushfom/brshape/cream2.ini", "Opacity = 50");
        put(s, "brushfom/brshape/other.bmp", "other");

        BrushVisibility.Result r = BrushVisibility.hide(s, Collections.singletonList(p));

        assertEquals(1, r.moved);
        assertFalse(p.exists());
        assertFalse(new File(s, "brushfom/brshape/cream2.ini").exists());
        File hidden = BrushVisibility.hiddenFolder(s);
        assertEquals("shape", read(new File(hidden, "brushfom/brshape/cream2.bmp")));
        assertEquals("Opacity = 50", read(new File(hidden, "brushfom/brshape/cream2.ini")));
        /* SAI now lists only the other one; the browser lists the hidden one */
        assertEquals(1, SettingsContents.pictures(s, SettingsContents.BRUSH_SHAPES).size());
        assertEquals(1, BrushVisibility.hidden(s, SettingsContents.BRUSH_SHAPES).size());
        assertEquals("cream2.bmp", BrushVisibility.hidden(s, SettingsContents.BRUSH_SHAPES).get(0).getName());
    }

    @Test
    public void showPutsThemBack() throws IOException {
        File s = settings();
        File a = put(s, "brushtex/papery.bmp", "a");
        File b = put(s, "papertex/canvas.bmp", "b");
        BrushVisibility.hide(s, Arrays.asList(a, b));

        List<File> hiddenTex = BrushVisibility.hidden(s, SettingsContents.BRUSH_TEXTURES);
        BrushVisibility.Result r = BrushVisibility.show(s, hiddenTex);

        assertEquals(1, r.moved);
        assertEquals("a", read(a));
        assertFalse(b.exists());
        assertTrue(BrushVisibility.hidden(s, SettingsContents.BRUSH_TEXTURES).isEmpty());
        assertEquals(1, BrushVisibility.hidden(s, SettingsContents.PAPER_TEXTURES).size());
    }

    @Test
    public void neverOverwritesOneOfTheSameName() throws IOException {
        File s = settings();
        File p = put(s, "brushtex/papery.bmp", "first");
        BrushVisibility.hide(s, Collections.singletonList(p));
        /* a pack brought the same name in again meanwhile */
        put(s, "brushtex/papery.bmp", "second");

        BrushVisibility.Result r = BrushVisibility.show(s, BrushVisibility.hidden(s, SettingsContents.BRUSH_TEXTURES));

        assertEquals(0, r.moved);
        assertEquals(Collections.singletonList("papery.bmp"), r.taken);
        assertEquals("second", read(p));
        assertEquals("first", read(new File(BrushVisibility.hiddenFolder(s), "brushtex/papery.bmp")));
    }

    @Test
    public void anIniInTheWayKeepsThePictureWhereItIs() throws IOException {
        File s = settings();
        File p = put(s, "brushfom/blotmap/x.bmp", "x");
        put(s, "brushfom/blotmap/x.ini", "mine");
        put(BrushVisibility.hiddenFolder(s), "brushfom/blotmap/x.ini", "old");

        BrushVisibility.Result r = BrushVisibility.hide(s, Collections.singletonList(p));

        assertEquals(0, r.moved);
        assertEquals(1, r.taken.size());
        assertTrue(p.exists());
        assertEquals("mine", read(new File(s, "brushfom/blotmap/x.ini")));
    }

    @Test
    public void onlyFilesUnderTheRightRootMove() throws IOException {
        File s = settings();
        File elsewhere = put(tmp.getRoot(), "Downloads/a.bmp", "a");
        BrushVisibility.Result r = BrushVisibility.hide(s, Collections.singletonList(elsewhere));
        assertEquals(0, r.moved);
        assertEquals(1, r.failed.size());
        assertTrue(elsewhere.exists());
        /* a hidden one handed to hide (not show) is not under settings either */
        File h = put(BrushVisibility.hiddenFolder(s), "brushtex/h.bmp", "h");
        assertEquals(0, BrushVisibility.hide(s, Collections.singletonList(h)).moved);
        assertTrue(h.exists());
    }

    @Test
    public void relativePaths() {
        File root = new File("/x/settings");
        assertEquals("brushtex/a.bmp", BrushVisibility.relative(root, new File("/x/settings/brushtex/a.bmp")));
        assertNull(BrushVisibility.relative(root, new File("/x/settings-before-1/brushtex/a.bmp")));
        assertNull(BrushVisibility.relative(root, new File("/x/settings")));
    }

    @Test
    public void aPackDoesNotBringBackWhatWasHidden() throws IOException {
        File s = settings();
        File p = put(s, "brushtex/papery.bmp", "old");
        BrushVisibility.hide(s, Collections.singletonList(p));

        File zip = new File(tmp.getRoot(), "pack.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String n : new String[] {"pack/brushtex/papery.bmp", "pack/brushtex/new.bmp"}) {
                z.putNextEntry(new ZipEntry(n));
                z.write("pack".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        BrushImport.Result r = BrushImport.add(BrushImport.zip(() -> new FileInputStream(zip)), s, -1, 2, null);

        assertEquals(1, r.addedPictures());
        assertEquals(1, r.kept);
        assertFalse(p.exists());
        assertTrue(new File(s, "brushtex/new.bmp").exists());
    }
}
