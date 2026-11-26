package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaiSettingsFilesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String path, String text) throws Exception {
        File f = new File(tmp.getRoot(), path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private File write(String path, byte[] b) throws Exception {
        File f = new File(tmp.getRoot(), path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
        return f;
    }

    private void tool(String path, String id, String name, int size, String shape, String texture) throws Exception {
        write(path, "tidstr=S:" + id + "\nvkc=I:78\nname=U:" + name + "\naddt=U:\n.\ncursize=I:" + size
            + "\ncurdens=I:80\nfomnam=U:" + shape + "\ntexnam=U:" + texture + "\ntexval=I:95\nhardness=I:30\n.\n.\n--EOF--\n");
    }

    @Test
    public void toolSetsInSaisOrderWithLinks() throws Exception {
        tool("custool/nrm/1.saitdat", "pencil", "Pencil", 90, "arji", "");
        tool("custool/nrm/2.saitdat", "airbrush", "AirBrush", 1200, "", "canvas");
        write("custool/nrm/9.saitdat", "tidstr=S:bucket\nname=U:Bucket\n.\n.\n--EOF--\n");
        write("custool/nrm/_0.saitset", "curgid=I:1\n.\n0=2\n1=1\n.\n--EOF--\n");
        write("custool/nrm/_1.saitgrp", "name=U:Basic\nvkc=I:0\n.\n1=2\n0=1\n.\n--EOF--\n");
        write("custool/nrm/_2.saitgrp", "name=U:Ver.1\n.\n0=9\n.\n--EOF--\n");
        write("custool/ink/7.saitlnk", "tarcat=I:1\ntarid=I:2\n.\n--EOF--\n");
        write("custool/ink/_1.saitgrp", "name=U:Basic\n.\n0=7\n1=99\n.\n--EOF--\n");

        List<SaiSettingsFiles.ToolSet> sets = SaiSettingsFiles.toolSets(tmp.getRoot());
        assertEquals(3, sets.size());
        assertEquals("nrm Ver.1", sets.get(0).layerKind + " " + sets.get(0).name);
        assertEquals("Bucket", sets.get(0).tools.get(0).name);
        assertFalse(sets.get(0).tools.get(0).draws());
        SaiSettingsFiles.ToolSet basic = sets.get(1);
        assertEquals("Basic", basic.name);
        assertEquals("Pencil", basic.tools.get(0).name);
        assertEquals(9.0, basic.tools.get(0).size, 0.001);
        assertEquals("arji", basic.tools.get(0).shape);
        assertEquals(80, basic.tools.get(0).density);
        assertEquals(120.0, basic.tools.get(1).size, 0.001);
        assertEquals("canvas", basic.tools.get(1).texture);
        /* ink's 7 is a link to the AirBrush of normal layers; 99 is missing */
        SaiSettingsFiles.ToolSet ink = sets.get(2);
        assertEquals(1, ink.tools.size());
        assertEquals("AirBrush", ink.tools.get(0).name);
        assertEquals("nrm", ink.tools.get(0).linkedFrom);
    }

    @Test
    public void iniValuesWithoutComments() throws Exception {
        File bmp = write("brushfom/brshape/Snow.bmp", "x");
        write("brushfom/brshape/Snow.ini", ";\n; Initial Parameters\n[Parameters]\n\t;\n\tDensity = 50\n\tAngle = +0\n\tSpacing = 4\n");
        Map<String, String> v = SaiSettingsFiles.ini(SaiSettingsFiles.iniOf(bmp));
        assertEquals(3, v.size());
        assertEquals("50", v.get("Density"));
        assertEquals("+0", v.get("Angle"));
        assertNull(SaiSettingsFiles.iniOf(write("brushtex/plain.bmp", "x")));
    }

    @Test
    public void colourHistoryAsFoundOnADevice() throws Exception {
        byte[] head = "SAI-COLHIS-TYPE0".getBytes(StandardCharsets.US_ASCII);
        /* as on the PC (2026-11-26): the newest colour right after the
         * header (it used to be skipped), black is a colour too */
        byte[] b = new byte[16 + 40 + 2];
        System.arraycopy(head, 0, b, 0, 16);
        int[][] colours = {{0xd4d3, 0xb2af, 0xcbc7}, {0, 0, 0}, {0xffff, 0xffff, 0xffff}, {0xffff, 0xc3bf, 0x0504}};
        for (int i = 0; i < colours.length; i++) {
            int o = 16 + i * 10;
            b[o] = 1;
            for (int c = 0; c < 3; c++) { b[o + 2 + c * 2] = (byte) colours[i][c]; b[o + 3 + c * 2] = (byte) (colours[i][c] >> 8); }
        }
        List<Integer> got = SaiSettingsFiles.colours(write("colhis.saichis", b));
        assertEquals(4, got.size());
        assertEquals(0xffd4b2cb, (int) got.get(0));
        assertEquals(0xff000000, (int) got.get(1));
        assertEquals(0xffffffff, (int) got.get(2));
        assertEquals(0xffffc305, (int) got.get(3));
    }

    @Test
    public void anEmptySwatchHasNoColours() throws Exception {
        byte[] b = new byte[36];
        System.arraycopy("SAI-SWATCH-TYPE0".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 16);
        b[16] = 2; b[20] = 2;
        assertTrue(SaiSettingsFiles.colours(write("swatch/Swatches1.saiswat", b)).isEmpty());
        assertTrue(SaiSettingsFiles.colours(write("other.bin", "not SAI's")).isEmpty());
    }

    @Test
    public void schemeBackupsAndEveryFile() throws Exception {
        File settings = new File(tmp.getRoot(), "SAIv2/settings");
        settings.mkdirs();
        Files.write(new File(settings, "settings2.ssd").toPath(), new byte[16]);
        new File(settings, "skin").mkdirs();
        Files.write(new File(settings, "skin/skin.ini").toPath(), "[Skin]\ncolsel=1\n".getBytes(StandardCharsets.UTF_8));
        new File(settings, "bak").mkdirs();
        Files.write(new File(settings, "bak/colhis.saichis").toPath(), new byte[3]);
        new File(settings, "swatch/bak").mkdirs();
        new File(tmp.getRoot(), "SAIv2/settings-before-2026-11-20").mkdirs();
        new File(tmp.getRoot(), "SAIv2/settings-before-2026-11-24").mkdirs();

        assertEquals(1, SaiSettingsFiles.windowScheme(settings));
        List<SaiSettingsFiles.Backup> bak = SaiSettingsFiles.saiBackups(settings);
        assertEquals(2, bak.size());
        assertEquals("bak", bak.get(0).path);
        assertEquals(1, bak.get(0).files);
        assertEquals("swatch/bak", bak.get(1).path);
        List<SaiSettingsFiles.Backup> before = SaiSettingsFiles.importBackups(settings);
        assertEquals("settings-before-2026-11-24", before.get(0).path);
        List<SaiSettingsFiles.Entry> all = SaiSettingsFiles.allFiles(settings);
        assertEquals(3, all.size());
        assertEquals("bak/colhis.saichis", all.get(0).path);
        assertEquals(16, all.get(1).size);
    }
}
