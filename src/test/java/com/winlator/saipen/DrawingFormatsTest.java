package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class DrawingFormatsTest {
    @Test
    public void kindOfAFile() {
        assertEquals("sai2", DrawingFormats.of(new File("a.SAI2")));
        assertEquals("sai", DrawingFormats.of(new File("old.sai")));
        assertEquals("jpg", DrawingFormats.of(new File("photo.jpeg")));
        assertEquals("jpg", DrawingFormats.of(new File("photo.JPG")));
        assertEquals("psb", DrawingFormats.of(new File("big.psb")));
        assertEquals("", DrawingFormats.of(new File("notes.txt")));
        assertEquals("", DrawingFormats.of(new File("sai2")));
        assertEquals("", DrawingFormats.of(new File(".png")));
    }

    @Test
    public void presentInTheChipsOrder() {
        List<File> files = Arrays.asList(new File("a.png"), new File("b.sai2"), new File("c.jpeg"),
                                         new File("d.png"), new File("e.txt"));
        assertEquals(Arrays.asList("sai2", "png", "jpg"), DrawingFormats.present(files));
    }

    @Test
    public void hiddenRoundTrip() {
        assertTrue(DrawingFormats.parse(null).isEmpty());
        assertTrue(DrawingFormats.parse("").isEmpty());
        Set<String> h = DrawingFormats.parse(" PNG ,jpeg,nonsense");
        assertEquals("jpg,png", DrawingFormats.join(h));
        assertEquals(h, DrawingFormats.parse(DrawingFormats.join(h)));
    }

    @Test
    public void shownLeavesOutHiddenKinds() {
        List<File> files = Arrays.asList(new File("a.png"), new File("b.sai2"), new File("c.jpeg"));
        assertEquals(files, DrawingFormats.shown(files, DrawingFormats.parse("")));
        assertEquals(Arrays.asList(new File("b.sai2")), DrawingFormats.shown(files, DrawingFormats.parse("png,jpg")));
    }
}
