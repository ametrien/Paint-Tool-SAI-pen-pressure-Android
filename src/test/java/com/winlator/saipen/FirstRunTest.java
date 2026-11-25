package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** What a person sees and gets the very first time, before touching anything. */
public class FirstRunTest {

    @Test
    public void theSwitchesStartAsTheOwnerChose() {
        assertTrue("timelapse records from the first start", FirstRun.TIMELAPSE);
        assertFalse("the screen shows first, SAI does not start by itself", FirstRun.AUTOSTART);
        assertTrue("speed logs fill the fast cores table", FirstRun.SPEED_LOG);
        assertFalse(FirstRun.PALM_ANDROID);
        assertFalse(FirstRun.PALM_PEN_NEAR);
        assertFalse(FirstRun.PALM_BIG);
        assertEquals("Screen drawing: Standard (0.4.30)", FrontBuffer.Mode.STANDARD,
            FrontBuffer.Mode.of(FirstRun.SCREEN_DRAWING));
    }

    @Test
    public void sharpnessStartsAtTheDisplaysOwn() {
        assertEquals(1200, SaiLocator.defaultScreenHeight(1200));   // Galaxy Tab S6 Lite
        assertEquals(1440, SaiLocator.defaultScreenHeight(1440));   // S26 Ultra
        assertEquals(1600, SaiLocator.defaultScreenHeight(1600));   // Tab S9 FE+
        assertTrue("the default is one of the choices on screen",
            contains(SaiLocator.screenHeights(1200), SaiLocator.defaultScreenHeight(1200)));
    }

    /**
     * THE TRAP (see FirstRun): the screen and the session each read these
     * switches; a literal default in one of them can disagree with the other.
     */
    @Test
    public void noReaderHasItsOwnDefault() throws IOException {
        File root = sourceRoot();
        Pattern literal = Pattern.compile(
            "getBoolean\\(\\s*(?:\\w+\\.)*(PREF_TIMELAPSE|PREF_AUTOSTART|PREF_SPEED_LOG|PREF_PALM_\\w+|PREF_CHEAT_SHEET_SEEN)\\s*,\\s*(true|false)\\s*\\)");
        List<String> found = new ArrayList<>();
        List<File> files = new ArrayList<>();
        try (Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        }
        assertTrue("found our sources in " + root, files.size() > 20);
        for (File f : files) {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = literal.matcher(text);
            while (m.find()) found.add(f.getName() + ": " + m.group());
        }
        assertEquals("use FirstRun's constant instead: " + found, 0, found.size());
    }

    /** Gradle runs the tests in the app module; the sources are under it. */
    private static File sourceRoot() {
        for (String path : new String[] {"src/main/java/com/winlator", "app/src/main/java/com/winlator"}) {
            File f = new File(path);
            if (f.isDirectory()) return f;
        }
        throw new AssertionError("sources not found from " + new File(".").getAbsolutePath());
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) if (x == v) return true;
        return false;
    }
}
