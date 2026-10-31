package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScrollBarTest {
    @Test
    void scrollbarThumbRetainsUsefulHitAreaAndFitsShortTracks() {
        assertEquals(12, ScrollBar.thumbLength(100, 10000, 100));
        assertEquals(50, ScrollBar.thumbLength(100, 200, 100));
        assertEquals(5, ScrollBar.thumbLength(5, 10000, 100));
        assertEquals(100, ScrollBar.thumbLength(100, 0, 100));
    }
}
