package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** On the SystemLink part of a real Winlator prefix's system.reg (SAI Pen
 *  0.4.17 on a Galaxy Tab, trimmed to that section). */
public class FontLinkTest {
    private static final List<String> NONE = Collections.emptyList();
    private static final List<String> NOTO = Collections.singletonList(FontLink.NOTO_JP);

    private static String fixture() throws Exception {
        try (InputStream in = FontLinkTest.class.getResourceAsStream("/winlator-SystemLink.reg")) {
            assertNotNull("the fixture is on the test classpath", in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void readsWinesLists() throws Exception {
        Map<String, FontLink.Value> links = FontLink.systemLinks(fixture());
        assertNotNull(links);
        assertEquals("MSGOTHIC.TTC,MS UI Gothic", links.get("Tahoma").list.get(0));
        assertEquals("MICROSS.TTF,Microsoft Sans Serif", links.get("MS UI Gothic").list.get(0));
        assertTrue(links.get("Tahoma").list.size() > 5);
    }

    @Test
    public void noSectionNoChange() {
        String reg = "WINE REGISTRY Version 2\n\n[Software\\\\Other] 1\n\"a\"=\"b\"\n";
        assertNull(FontLink.systemLinks(reg));
        assertSame(reg, FontLink.apply(reg, NOTO, NONE));
    }

    @Test
    public void onPutsNotoFirstAndTahomaBeforeItForMissingNames() throws Exception {
        String wine = fixture();
        Map<String, FontLink.Value> before = FontLink.systemLinks(wine);
        Map<String, FontLink.Value> after = FontLink.systemLinks(FontLink.apply(wine, NOTO, NONE));

        List<String> tahoma = after.get("Tahoma").list;
        assertEquals(FontLink.NOTO_JP, tahoma.get(0));               /* Tahoma exists: no lead */
        assertEquals(before.get("Tahoma").list, tahoma.subList(1, tahoma.size()));

        List<String> gothic = after.get("MS UI Gothic").list;
        assertEquals(FontLink.WINE_TAHOMA, gothic.get(0));            /* THE TRAP 4 */
        assertEquals(FontLink.NOTO_JP, gothic.get(1));
        assertEquals(before.get("MS UI Gothic").list, gothic.subList(2, gothic.size()));

        /* names Wine had no list for get one */
        for (String name : FontLink.SAI_FONT_NAMES) {
            List<String> l = after.get(name).list;
            assertTrue(name, l.contains(FontLink.NOTO_JP));
        }
        /* everything else is untouched */
        assertEquals(before.get("Meiryo").list, after.get("Meiryo").list);
        assertEquals(before.get("Lucida Sans Unicode").list, after.get("Lucida Sans Unicode").list);
    }

    @Test
    public void offGivesWinesOwnListsBackExactly() throws Exception {
        String wine = fixture();
        String on = FontLink.apply(wine, NOTO, NONE);
        String off = FontLink.apply(on, NONE, NONE);
        assertEquals(wine, off);
    }

    @Test
    public void onTwiceChangesNothing() throws Exception {
        String on = FontLink.apply(fixture(), NOTO, NONE);
        assertSame(on, FontLink.apply(on, NOTO, NONE));
    }

    @Test
    public void offOnWinesOwnChangesNothing() throws Exception {
        String wine = fixture();
        assertSame(wine, FontLink.apply(wine, NONE, NONE));
    }

    @Test
    public void switchingToTheirOwnFontDropsNoto() throws Exception {
        String own = FontLink.entry("MitsuFont.ttf", "Mitsu Font");
        List<String> remembered = Collections.singletonList(own);
        String a = FontLink.apply(fixture(), NOTO, remembered);
        String b = FontLink.apply(a, Arrays.asList(own, FontLink.NOTO_JP), remembered);
        List<String> l = FontLink.systemLinks(b).get("MS UI Gothic").list;
        assertEquals(Arrays.asList(FontLink.WINE_TAHOMA, own, FontLink.NOTO_JP), l.subList(0, 3));
        assertEquals(1, Collections.frequency(l, FontLink.NOTO_JP));
        /* off, still remembering their font: Wine's own again */
        assertEquals(fixture(), FontLink.apply(b, NONE, remembered));
    }

    @Test
    public void anUnreadableValueIsLeftAlone() throws Exception {
        String reg = fixture().replaceFirst("\"Tahoma\"=str\\(7\\):\"[^\n]*\"", "\"Tahoma\"=hex(7):41,00,00,00");
        String on = FontLink.apply(reg, NOTO, NONE);
        assertTrue(on.contains("\"Tahoma\"=hex(7):41,00,00,00"));
        assertFalse(FontLink.systemLinks(on).get("Tahoma").readable());
    }

    @Test
    public void theJapaneseNameIsWrittenAsWineWritesIt() throws Exception {
        String on = FontLink.apply(fixture(), NOTO, NONE);
        assertTrue(on.contains("\"\\xff2d\\xff33 \\xff30\\x30b4\\x30b7\\x30c3\\x30af\"=str(7):"));
        assertNotNull(FontLink.systemLinks(on).get("ＭＳ Ｐゴシック"));
        /* and goes away again with off: Wine had no list for it */
        assertFalse(FontLink.apply(on, NONE, NONE).contains("\\xff2d"));
    }

    @Test
    public void newValuesStayInsideTheSection() throws Exception {
        String on = FontLink.apply(fixture(), NOTO, NONE);
        List<String> lines = new ArrayList<>(Arrays.asList(on.split("\n", -1)));
        int section = -1, next = -1, gothic = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontLink\\\\SystemLink]")) section = i;
            else if (section >= 0 && next < 0 && l.startsWith("[")) next = i;
            if (l.startsWith("\"\\xff2d")) gothic = i;
        }
        assertTrue(section >= 0 && next > section);
        assertTrue(gothic > section && gothic < next);
        assertTrue(lines.get(next - 1).isEmpty());   /* the blank line before the next key stays */
    }

    @Test
    public void nothingStoredIsNoto() {
        assertEquals(NOTO, FontLink.fontsFor(null, f -> true));
    }

    @Test
    public void aChosenFontComesFirstWithNotoBehindIt() {
        assertEquals(Arrays.asList("SECCJK-Regular.ttc,SEC CJK JP", FontLink.NOTO_JP),
            FontLink.fontsFor("samsung", f -> true));
    }

    @Test
    public void aFontNotOnTheDeviceIsSkipped() {
        assertEquals(NOTO, FontLink.fontsFor("samsung", f -> !f.startsWith("SEC")));
        assertEquals(NONE, FontLink.fontsFor(null, f -> false));
    }

    @Test
    public void offIsNoFonts() {
        assertEquals(NONE, FontLink.fontsFor(FontLink.OFF, f -> true));
    }

    @Test
    public void switchingBuiltInFontsLeavesNoOldOne() throws Exception {
        String a = FontLink.apply(fixture(), FontLink.fontsFor("samsung", f -> true), NONE);
        String b = FontLink.apply(a, FontLink.fontsFor("noto_serif", f -> true), NONE);
        List<String> l = FontLink.systemLinks(b).get("Tahoma").list;
        assertFalse(l.contains("SECCJK-Regular.ttc,SEC CJK JP"));
        assertEquals("NotoSerifCJK-Regular.ttc,Noto Serif CJK JP", l.get(0));
        assertEquals(fixture(), FontLink.apply(b, NONE, NONE));
    }

    @Test
    public void escapesGoBothWays() {
        String s = "a\\b\"c\0ＭＳ";
        assertEquals(s, FontLink.unescape(FontLink.escape(s)));
        assertEquals("ＭＳ", FontLink.unescape("\\xff2d\\xff33"));
    }
}
