package com.winlator.saipen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimelapsesDialogTest {
    @Test
    public void stillsAreDecodedOnlyAsBigAsTheCard() {
        assertEquals(1, TimelapsesDialog.sampleSize(300, 300));
        assertEquals(1, TimelapsesDialog.sampleSize(500, 300));
        assertEquals(2, TimelapsesDialog.sampleSize(600, 300));
        assertEquals("a 1200 high drawing on a 300 px card", 4, TimelapsesDialog.sampleSize(1200, 300));
        assertEquals("never smaller than the card", 4, TimelapsesDialog.sampleSize(2399, 300));
        assertEquals("smaller than the card: as it is", 1, TimelapsesDialog.sampleSize(100, 300));
        assertEquals("no size known: as it is", 1, TimelapsesDialog.sampleSize(-1, 300));
        assertEquals(1, TimelapsesDialog.sampleSize(1200, 0));
    }
}
