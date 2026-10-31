package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicScreenTest {
    @Test
    void wideArtworkTextFollowsTheDrawnImageInsteadOfASquareLetterbox() {
        assertEquals(115, MusicScreen.fittedHeight(1280, 720, 204, 184));
        assertEquals(184, MusicScreen.fittedHeight(500, 500, 204, 184));
        assertEquals(184, MusicScreen.fittedHeight(720, 1280, 204, 184));
        assertEquals(184, MusicScreen.fittedHeight(0, 0, 204, 184));
    }
}
