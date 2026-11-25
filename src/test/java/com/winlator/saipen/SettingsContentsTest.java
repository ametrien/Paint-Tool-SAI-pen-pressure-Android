package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SettingsContentsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file(File settings, String path) throws IOException {
        File f = new File(settings, path);
        f.getParentFile().mkdirs();
        assertTrue(f.createNewFile());
        return f;
    }

    private static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(SettingsContents.name(f));
        return out;
    }

    @Test
    public void picturesOfOneKindSortedByName() throws Exception {
        File s = tmp.getRoot();
        file(s, "brushtex/b.bmp");
        file(s, "brushtex/A.BMP");
        file(s, "brushtex/notes.txt");
        file(s, "papertex/paper.bmp");
        assertEquals(java.util.Arrays.asList("A", "b"), names(SettingsContents.pictures(s, SettingsContents.BRUSH_TEXTURES)));
        assertEquals(java.util.Arrays.asList("paper"), names(SettingsContents.pictures(s, SettingsContents.PAPER_TEXTURES)));
    }

    @Test
    public void brushShapesAreAllThreeFolders() throws Exception {
        File s = tmp.getRoot();
        file(s, "brushfom/blotmap/blot.bmp");
        file(s, "brushfom/bristle/bristle.bmp");
        file(s, "brushfom/brshape/acrylic.bmp");
        assertEquals(java.util.Arrays.asList("acrylic", "blot", "bristle"),
                     names(SettingsContents.pictures(s, SettingsContents.BRUSH_SHAPES)));
    }

    @Test
    public void aMissingFolderIsNoPictures() {
        assertTrue(SettingsContents.pictures(tmp.getRoot(), SettingsContents.SCATTER).isEmpty());
        assertTrue(SettingsContents.tools(tmp.getRoot()).isEmpty());
    }

    private void tool(File settings, String path, String name) throws IOException {
        File f = new File(settings, path);
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), ("tidstr=S:x\nvkc=I:78\nname=U:" + name + "\naddt=U:\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void toolsByPanelNumberWithTheirNames() throws Exception {
        File s = tmp.getRoot();
        tool(s, "custool/nrm/1.saitdat", "Pencil");
        tool(s, "custool/nrm/2.saitdat", "AirBrush");
        tool(s, "custool/nrm/10.saitdat", "Grada-");
        tool(s, "custool/ink/1.saitdat", "Pen");
        tool(s, "custool/shp2/1.saitdat", "Change");
        file(s, "custool/ink/5.saitlnk");
        file(s, "custool/nrm/readme.txt");
        List<SettingsContents.Tool> t = SettingsContents.tools(s);
        assertEquals(5, t.size());
        String[] want = {"nrm Pencil", "nrm AirBrush", "nrm Grada-", "ink Pen", "shp2 Change"};
        for (int i = 0; i < want.length; i++) assertEquals(want[i], t.get(i).group + " " + t.get(i).name);
    }

    @Test
    public void aToolWithNoNameKeepsItsNumber() throws Exception {
        File s = tmp.getRoot();
        file(s, "custool/nrm/7.saitdat");
        assertEquals("7", SettingsContents.tools(s).get(0).name);
    }

    @Test
    public void swatchesAndScratchPadsByName() throws Exception {
        File s = tmp.getRoot();
        file(s, "swatch/Swatches2.saiswat");
        file(s, "swatch/Swatches1.saiswat");
        file(s, "swatch/bak/Swatches1.saiswat");
        file(s, "scratchpad/ScratchPad1.saispad");
        List<File> sw = SettingsContents.swatches(s);
        assertEquals(2, sw.size());
        assertEquals("Swatches1", SettingsContents.bareName(sw.get(0)));
        assertEquals(1, SettingsContents.scratchPads(s).size());
    }

    @Test
    public void extrasOnlyWhatIsThere() throws Exception {
        File s = tmp.getRoot();
        assertTrue(SettingsContents.extras(s).isEmpty());
        file(s, "settings2.ssd");
        file(s, "colhis.saichis");
        file(s, "skin/skin.ini");
        file(s, "bak/settings2.ssd");
        file(s, "swatch/bak/Swatches1.saiswat");
        file(s, "brushfom/brshape/bak/x.bmp");
        List<SettingsContents.Extra> e = SettingsContents.extras(s);
        assertEquals(4, e.size());
        assertEquals(SettingsContents.EXTRA_COLOUR_HISTORY, e.get(0).what);
        assertEquals(SettingsContents.EXTRA_SKIN, e.get(1).what);
        assertEquals(SettingsContents.EXTRA_SETTINGS, e.get(2).what);
        assertEquals(SettingsContents.EXTRA_BACKUPS, e.get(3).what);
        assertEquals(3, e.get(3).count);
    }
}
