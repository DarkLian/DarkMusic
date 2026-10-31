package com.darkmusic.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudTextTest {
    @Test
    void lyricsPlatesFadeAsTheLyricsBackgroundCarriesTheText() {
        assertEquals(HudText.PLATE, HudText.lyricsPlate(0));
        assertTrue(HudText.lyricsPlate(.25) >>> 24 < HudText.PLATE >>> 24);
        assertEquals(0, HudText.lyricsPlate(.5));
        assertEquals(0, HudText.lyricsPlate(1));
    }
}
