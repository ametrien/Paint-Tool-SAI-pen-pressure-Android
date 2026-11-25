package com.winlator.saipen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaiLanguageTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** sai2.ini as SAI ships it: UTF-16 LE with a BOM, CRLF. */
    private static byte[] shipped(String text) {
        return new SaiLanguage.Ini(text, true).encode();
    }

    @Test
    public void readsTheLanguageOfTheShippedFile() {
        byte[] d = shipped("[Locale]\r\nlang = en\r\n\r\n[Other]\r\nx = 1\r\n");
        assertEquals((byte) 0xff, d[0]);
        assertEquals((byte) 0xfe, d[1]);
        assertEquals("en", SaiLanguage.Ini.decode(d).language());
    }

    @Test
    public void onlyTheCodeChanges() {
        String before = "[Locale]\r\n  lang = en\r\n\r\n[Other]\r\nlang = zz\r\n";
        SaiLanguage.Ini ini = SaiLanguage.Ini.decode(shipped(before));
        byte[] after = ini.withLanguage("ja").encode();
        assertArrayEquals(shipped("[Locale]\r\n  lang = ja\r\n\r\n[Other]\r\nlang = zz\r\n"), after);
    }

    @Test
    public void aCommentOrLanguageLineIsNotTheLang() {
        SaiLanguage.Ini ini = new SaiLanguage.Ini("[Locale]\n;lang = ja\nlanguage = ja\nlang=en\n", false);
        assertEquals("en", ini.language());
        assertEquals("[Locale]\n;lang = ja\nlanguage = ja\nlang = ja\n", ini.withLanguage("ja").text);
    }

    @Test
    public void aMissingLineOrSectionIsAdded() {
        assertEquals("[Locale]\r\nlang = ja\r\nfoo = 1\r\n",
            new SaiLanguage.Ini("[Locale]\r\nfoo = 1\r\n", true).withLanguage("ja").text);
        assertEquals("[Locale]\nlang = ja\n\n[Other]\n",
            new SaiLanguage.Ini("[Other]\n", false).withLanguage("ja").text);
        assertNull(new SaiLanguage.Ini("[Other]\nlang = ja\n", false).language());
    }

    @Test
    public void byDefaultSaiFollowsTheDevice() {
        assertEquals("ja", SaiLanguage.target(null, "ja"));
        assertEquals("ja", SaiLanguage.target(SaiLanguage.DEVICE, "ja_JP"));
        assertEquals("en", SaiLanguage.target(null, "en"));
        assertEquals("en", SaiLanguage.target(null, "ru"));
        assertEquals("en", SaiLanguage.target(null, "zh"));
        assertEquals("en", SaiLanguage.target(null, null));
    }

    @Test
    public void aPickedLanguageWins() {
        assertEquals("en", SaiLanguage.target("en", "ja"));
        assertEquals("ja", SaiLanguage.target("ja", "de"));
        assertEquals("en", SaiLanguage.target("ru", "de"));   /* unknown code: as the device */
    }

    @Test
    public void applyWritesOnlyWhenItChanges() throws Exception {
        File dir = tmp.newFolder("SAI");
        File ini = new File(dir, "sai2.ini");
        Files.write(ini.toPath(), shipped("[Locale]\r\nlang = en\r\n"));
        assertNull(SaiLanguage.apply(dir, null, "en"));
        assertEquals("ja", SaiLanguage.apply(dir, null, "ja"));
        assertEquals("ja", SaiLanguage.read(dir));
        assertNull(SaiLanguage.apply(dir, "ja", "en"));
        assertEquals("en", SaiLanguage.apply(dir, "en", "ja"));
        assertArrayEquals(shipped("[Locale]\r\nlang = en\r\n"), Files.readAllBytes(ini.toPath()));
    }

    @Test
    public void noIniNoChange() throws Exception {
        File dir = tmp.newFolder("SAI");
        assertNull(SaiLanguage.apply(dir, "ja", "en"));
        assertNull(SaiLanguage.apply(null, "ja", "en"));
        assertNull(SaiLanguage.read(dir));
    }
}
