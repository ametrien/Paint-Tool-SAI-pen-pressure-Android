package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.winlator.saipen.DrawingIndex.Entry;
import com.winlator.saipen.DrawingLibrary.Drawing;
import com.winlator.saipen.DrawingLibrary.Piece;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** A drawing and its timelapse stay linked by look when the names part:
 *  the drawing renamed, or a NewCanvas saved under a name nothing saw. */
public class TimelapseLinkByLookTest {
    private static DrawingLibrary.Signature look(int shift) {
        int w = 256, h = 256;
        byte[] px = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            boolean ink = Math.abs((x + shift) % w - y) < w / 8;
            px[i] = px[i + 1] = px[i + 2] = (byte) (ink ? 30 : 245);
            px[i + 3] = (byte) 255;
        }
        return DrawingLibrary.Signature.of(px, w, h, w * 4);
    }

    private static Drawing timelapse(String title, long began, DrawingLibrary.Signature end) {
        Drawing d = new Drawing("id-" + title, title, "f-" + title);
        d.pieces.add(new Piece("p.mp4", began, 100, 256, 256, null, end));
        return d;
    }

    private static Entry file(String name, long modified) {
        return new Entry(new File("/d/" + name + ".sai2"), "", name, modified, 1);
    }

    @Test
    public void renamedDrawingFindsItsTimelapseByLook() {
        Drawing tl = timelapse("NewCanvas1", 1_000_000, look(0));
        Entry pb = file("pb", 2_000_000), other = file("cat", 2_000_000);
        Map<Entry, DrawingLibrary.Signature> looks = new HashMap<>();
        looks.put(pb, look(0));
        looks.put(other, look(128));
        Map<Drawing, Entry> m = DrawingIndex.linkByLook(Arrays.asList(other, pb), Arrays.asList(tl), looks::get);
        assertEquals(pb, m.get(tl));
    }

    @Test
    public void aFileSavedLongBeforeTheTimelapseIsNotIt() {
        Drawing tl = timelapse("NewCanvas2", 10_000_000, look(0));
        Entry old = file("old", 1_000_000);
        boolean[] read = {false};
        Map<Drawing, Entry> m = DrawingIndex.linkByLook(Arrays.asList(old), Arrays.asList(tl),
            e -> { read[0] = true; return look(0); });
        assertTrue(m.isEmpty());
        assertEquals(false, read[0]);   // its picture is not even read
    }

    @Test
    public void eachDrawingAtMostOnce() {
        Drawing a = timelapse("NewCanvas1", 1_000_000, look(0));
        Drawing b = timelapse("NewCanvas2", 1_000_000, look(0));
        Entry pb = file("pb", 2_000_000);
        Map<Drawing, Entry> m = DrawingIndex.linkByLook(Arrays.asList(pb), Arrays.asList(a, b), e -> look(0));
        assertEquals(pb, m.get(a));
        assertNull(m.get(b));
    }

    @Test
    public void unreadablePictureLinksNothing() {
        Drawing tl = timelapse("NewCanvas1", 1_000_000, look(0));
        assertTrue(DrawingIndex.linkByLook(Arrays.asList(file("pb", 2_000_000)), Arrays.asList(tl), e -> null).isEmpty());
    }
}
