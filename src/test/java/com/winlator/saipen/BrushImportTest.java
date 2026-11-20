package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BrushImportTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A zip of name, text, name, text... with names in `charset`. */
    private File zip(Charset charset, String... namesAndTexts) throws IOException {
        File f = new File(tmp.getRoot(), "pack-" + System.nanoTime() + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f), charset)) {
            for (int i = 0; i < namesAndTexts.length; i += 2) {
                z.putNextEntry(new ZipEntry(namesAndTexts[i]));
                z.write(namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return f;
    }

    private File zip(String... namesAndTexts) throws IOException {
        return zip(StandardCharsets.UTF_8, namesAndTexts);
    }

    private static BrushImport.Source source(File zip) {
        return BrushImport.zip(() -> new FileInputStream(zip));
    }

    private static String read(File dir, String path) throws IOException {
        return new String(Files.readAllBytes(new File(dir, path).toPath()), StandardCharsets.UTF_8);
    }

    private File settingsWith(String... namesAndTexts) throws IOException {
        File s = tmp.newFolder("Documents", "SAIv2", "settings");
        for (int i = 0; i < namesAndTexts.length; i += 2) {
            File f = new File(s, namesAndTexts[i]);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return s;
    }

    /* like the Brush Collection on r/PaintToolSAI (2026-11-20) */
    private File redditLikePack() throws IOException {
        return zip(
            "Paint Tool SAI Brushes/brushtex/+1.bmp", "t1",
            "Paint Tool SAI Brushes/brushtex/Acrylic.bmp", "t2",
            "Paint Tool SAI Brushes/brushtex/bak/old.bmp", "sai's backup",
            "Paint Tool SAI Brushes/papertex/Leaves 02.bmp", "p1",
            "Paint Tool SAI Brushes/scatter/Star.bmp", "s1",
            "Paint Tool SAI Brushes/scatter/Star.ini", "s1 ini",
            "Paint Tool SAI Brushes/brushfom/blotmap/Pastel_blotmap (2).bmp", "b1",
            "Paint Tool SAI Brushes/brushfom/bristle/Bristle 7.bmp", "b2",
            "Paint Tool SAI Brushes/brushfom/bristle/SaiBristleCollectorBackup.20260112.100435.0/x.bmp", "tool backup",
            "Paint Tool SAI Brushes/brushfom/brshape/Water Blur 2.bmp", "b3",
            "Paint Tool SAI Brushes/brushfom/brshape/Water Blur 2.ini", "b3 ini",
            "Paint Tool SAI Brushes/Bitmap fixer/SaiBristleCorrector.exe", "MZ a program",
            "Paint Tool SAI Brushes/Bitmap fixer/history.txt", "notes",
            "__MACOSX/Paint Tool SAI Brushes/brushtex/._+1.bmp", "mac junk");
    }

    @Test
    public void aPackIsCountedByWhatItHolds() throws Exception {
        BrushImport.Plan plan = BrushImport.scan(source(redditLikePack()));
        assertFalse(plan.isWholeSettings());
        assertEquals(2, plan.pictures[BrushImport.BRUSH_TEXTURES]);
        assertEquals(1, plan.pictures[BrushImport.PAPER_TEXTURES]);
        assertEquals(1, plan.pictures[BrushImport.SCATTER]);
        assertEquals(3, plan.brushShapes());
        assertEquals(0, plan.unsorted);
        assertEquals("7 pictures and 2 .ini files", 9, plan.files);
    }

    @Test
    public void aPackGoesIntoSaisOwnFolders() throws Exception {
        File settings = settingsWith("settings2.ssd", "prefs");
        BrushImport.Plan plan = BrushImport.scan(source(redditLikePack()));
        BrushImport.Result r = BrushImport.add(source(redditLikePack()), settings, -1, plan.files, null);
        assertEquals(7, r.addedPictures());
        assertEquals("t1", read(settings, "brushtex/+1.bmp"));
        assertEquals("p1", read(settings, "papertex/Leaves 02.bmp"));
        assertEquals("s1 ini", read(settings, "scatter/Star.ini"));
        assertEquals("b1", read(settings, "brushfom/blotmap/Pastel_blotmap (2).bmp"));
        assertEquals("b2", read(settings, "brushfom/bristle/Bristle 7.bmp"));
        assertEquals("b3 ini", read(settings, "brushfom/brshape/Water Blur 2.ini"));
        /* no program, no notes, no backups, no Mac junk */
        assertFalse(new File(settings, "Bitmap fixer").exists());
        assertFalse(new File(settings, "brushtex/bak").exists());
        assertFalse(new File(settings, "brushtex/._+1.bmp").exists());
        assertFalse(new File(settings, "brushfom/bristle/x.bmp").exists());
        assertFalse(new File(settings, "brushtex/+1.bmp.part").exists());
        assertEquals("prefs", read(settings, "settings2.ssd"));
    }

    @Test
    public void aPictureAlreadyThereIsKept() throws Exception {
        File settings = settingsWith("settings2.ssd", "prefs", "brushtex/+1.bmp", "mine");
        BrushImport.Result r = BrushImport.add(source(redditLikePack()), settings, -1, 9, null);
        assertEquals("mine", read(settings, "brushtex/+1.bmp"));
        assertEquals(1, r.kept);
        assertEquals(6, r.addedPictures());
    }

    @Test
    public void loosePicturesWaitUntilThePersonSaysWhatTheyAre() throws Exception {
        File pack = zip("My Paper Pack/Rough.bmp", "r", "My Paper Pack/Smooth.BMP", "s", "My Paper Pack/read me.txt", "hi");
        BrushImport.Plan plan = BrushImport.scan(source(pack));
        assertEquals(0, plan.sorted());
        assertEquals(2, plan.unsorted);
        assertEquals(2, plan.unsortedFiles);
        File settings = settingsWith("settings2.ssd", "prefs");
        BrushImport.Result r = BrushImport.add(source(pack), settings, BrushImport.PAPER_TEXTURES, plan.unsortedFiles, null);
        assertEquals(2, r.added[BrushImport.PAPER_TEXTURES]);
        assertEquals("r", read(settings, "papertex/Rough.bmp"));
        assertEquals("s", read(settings, "papertex/Smooth.BMP"));
        assertFalse(new File(settings, "papertex/read me.txt").exists());
    }

    @Test
    public void aZipMadeOnAnOldWindowsIsReadToo() throws Exception {
        /* names in the old Windows code page: UTF-8 refuses the í */
        File pack = zip(Charset.forName("IBM437"), "scatter/-íircle A.bmp", "c", "scatter/-íircle A.ini", "ci");
        File settings = settingsWith("settings2.ssd", "prefs");
        BrushImport.Result r = BrushImport.add(source(pack), settings, -1, 2, null);
        assertEquals(1, r.added[BrushImport.SCATTER]);
        assertEquals("c", read(settings, "scatter/-íircle A.bmp"));
    }

    @Test
    public void nothingInAPackCanClimbOutOfSettings() throws Exception {
        File pack = zip("../brushtex/evil.bmp", "x", "brushtex/../../evil.bmp", "x", "/brushtex/ok.bmp", "ok");
        File settings = settingsWith("settings2.ssd", "prefs");
        BrushImport.add(source(pack), settings, -1, 3, null);
        assertEquals("ok", read(settings, "brushtex/ok.bmp"));
        assertFalse(new File(settings.getParentFile(), "evil.bmp").exists());
        assertFalse(new File(settings.getParentFile(), "brushtex").exists());
        assertNull(BrushImport.destination("a/../../x.bmp", BrushImport.PAPER_TEXTURES));
    }

    @Test
    public void aFolderIsReadLikeAZip() throws Exception {
        File dir = tmp.newFolder("Download", "pack");
        new File(dir, "papertex").mkdirs();
        Files.write(new File(dir, "papertex/Linen.bmp").toPath(), "l".getBytes(StandardCharsets.UTF_8));
        File settings = settingsWith("settings2.ssd", "prefs");
        BrushImport.Result r = BrushImport.add(BrushImport.folder(dir), settings, -1, 1, null);
        assertEquals(1, r.added[BrushImport.PAPER_TEXTURES]);
        assertEquals("l", read(settings, "papertex/Linen.bmp"));
    }

    /* ---- a whole settings folder from a PC ---- */

    private File pcSettingsZip() throws IOException {
        return zip(
            "settings/settings2.ssd", "pc prefs",
            "settings/custool/nrm/_1.saitgrp", "name=U:From PC",
            "settings/custool/nrm/6.saitdat", "pc pencil",
            "settings/brushtex/Paper.bmp", "pc paper",
            "settings/bak/settings2.ssd", "sai's backup");
    }

    @Test
    public void aPcsSettingsFolderIsRecognised() throws Exception {
        BrushImport.Plan plan = BrushImport.scan(source(pcSettingsZip()));
        assertTrue(plan.isWholeSettings());
        assertEquals("settings", plan.settingsRoot);
        assertEquals("the backup left out", 4, plan.files);
    }

    @Test
    public void aPcsSettingsFolderReplacesTheTabletsAndTheOldOneIsKept() throws Exception {
        File settings = settingsWith("settings2.ssd", "tablet prefs", "custool/nrm/39.saitdat", "tablet tool");
        BrushImport.Result r = BrushImport.replace(source(pcSettingsZip()), "settings", settings,
                                                   "settings-before-2026-11-20", 4, null);
        assertEquals(4, r.files);
        assertEquals("pc prefs", read(settings, "settings2.ssd"));
        assertEquals("name=U:From PC", read(settings, "custool/nrm/_1.saitgrp"));
        assertFalse("nothing of the tablet's mixed in", new File(settings, "custool/nrm/39.saitdat").exists());
        assertEquals(new File(settings.getParentFile(), "settings-before-2026-11-20"), r.backup);
        assertEquals("tablet tool", read(r.backup, "custool/nrm/39.saitdat"));
        assertFalse(new File(settings.getParentFile(), "settings.importing").exists());
    }

    @Test
    public void aSettingsFolderAtTheTopOfTheZipWorksToo() throws Exception {
        File pack = zip("settings2.ssd", "pc prefs", "custool/nrm/6.saitdat", "pc pencil");
        BrushImport.Plan plan = BrushImport.scan(source(pack));
        assertEquals("", plan.settingsRoot);
        File settings = settingsWith("settings2.ssd", "tablet prefs");
        BrushImport.replace(source(pack), "", settings, "kept", 2, null);
        assertEquals("pc pencil", read(settings, "custool/nrm/6.saitdat"));
    }

    @Test
    public void aSecondReplaceKeepsTheFirstBackupToo() throws Exception {
        File settings = settingsWith("settings2.ssd", "first");
        BrushImport.replace(source(pcSettingsZip()), "settings", settings, "kept", 4, null);
        BrushImport.Result r = BrushImport.replace(source(pcSettingsZip()), "settings", settings, "kept", 4, null);
        assertEquals("first", read(new File(settings.getParentFile(), "kept"), "settings2.ssd"));
        assertEquals(new File(settings.getParentFile(), "kept-2"), r.backup);
    }

    @Test
    public void aBrokenSettingsFolderLeavesTheTabletsAlone() throws Exception {
        File settings = settingsWith("settings2.ssd", "tablet prefs");
        File pack = zip("settings/settings2.ssd", "pc prefs");
        try {
            /* the wrong root: nothing of it is a settings folder */
            BrushImport.replace(source(pack), "elsewhere", settings, "kept", 1, null);
            fail("a folder without settings2.ssd must not replace SAI's");
        }
        catch (IOException expected) {
            /* fine */
        }
        assertEquals("tablet prefs", read(settings, "settings2.ssd"));
        assertFalse(new File(settings.getParentFile(), "kept").exists());
        assertFalse(new File(settings.getParentFile(), "settings.importing").exists());
    }

    @Test
    public void toolPresetsAloneAreNoSettingsFolder() throws Exception {
        /* custool without settings2.ssd: not a whole folder, and presets
         * do not go in one by one */
        File pack = zip("custool/nrm/6.saitdat", "a tool", "brushtex/T.bmp", "t");
        BrushImport.Plan plan = BrushImport.scan(source(pack));
        assertFalse(plan.isWholeSettings());
        assertEquals(1, plan.sorted());
        assertNull(BrushImport.destination("custool/nrm/6.saitdat", -1));
    }

    @Test
    public void whatSaiHasIsCounted() throws Exception {
        File settings = settingsWith("settings2.ssd", "prefs",
            "brushtex/A.bmp", "a", "brushtex/B.BMP", "b", "brushtex/bak/old.bmp", "o",
            "papertex/P.bmp", "p", "scatter/S.bmp", "s", "scatter/S.ini", "si",
            "brushfom/blotmap/X.bmp", "x", "brushfom/brshape/Y.bmp", "y", "brushfom/brshape/Y.ini", "yi",
            "custool/nrm/1.saitdat", "t", "custool/nrm/2.saitdat", "t", "custool/nrm/_1.saitgrp", "g",
            "custool/ink/1.saitdat", "t");
        int[] n = BrushImport.count(settings);
        assertEquals(2, n[BrushImport.BRUSH_TEXTURES]);
        assertEquals(1, n[BrushImport.PAPER_TEXTURES]);
        assertEquals(1, n[BrushImport.SCATTER]);
        assertEquals(1, n[3]);
        assertEquals(0, n[4]);
        assertEquals(1, n[5]);
        assertEquals("tools", 3, n[BrushImport.FOLDERS.length]);
        assertTrue(BrushImport.hasSaiSettings(settings));
        assertEquals(7, BrushImport.count(new File(tmp.getRoot(), "missing")).length);
    }

    @Test
    public void anEmptyOrUnrelatedZipHasNothingForSai() throws Exception {
        BrushImport.Plan plan = BrushImport.scan(source(zip("notes.txt", "hi", "photo.jpg", "x")));
        assertTrue(plan.isEmpty());
    }
}
