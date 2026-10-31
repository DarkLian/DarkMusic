package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArtworkHudLayoutTest {
    @Test
    void artworkSpectrumFollowsAspectRatioAndEveryPositionFitsSharedBounds() {
        var settings = new Settings();
        settings.validate();
        settings.hudStyle = Settings.HudStyle.ARTWORK;
        for (var position : Settings.ArtworkSpectrumPosition.values()) {
            settings.artworkSpectrumPosition = position;
            for (int[] image : new int[][]{{1920, 1080}, {1000, 1000}, {600, 1200}})
                for (int height : new int[]{8, 40, 100}) {
                    settings.artworkSpectrumHeight = height;
                    var art = ArtworkHudLayout.of(settings, image[0], image[1]);
                    var content = HudLayout.content(settings, Integer.MAX_VALUE, image[0], image[1]);
                    assertEquals(content.width(), art.width());
                    assertEquals(content.height(), art.height());
                    assertEquals(image[0] / (double) image[1], art.artworkWidth() / (double) art.artworkHeight(), .02);
                    assertTrue(art.spectrumX() + art.spectrumWidth(settings) <= art.width());
                    assertTrue(art.spectrumY() + art.spectrumHeight(settings) <= art.height());
                    if (art.sideways(settings)) {
                        assertEquals(height, art.spectrumWidth(settings));
                        assertEquals(art.artworkHeight(), art.spectrumHeight(settings));
                        assertEquals(art.artworkWidth() + HudLayout.GAP + height, art.width());
                    }
                    assertTrue(art.artworkY() + art.artworkHeight() <= art.height());
                    assertTrue(art.artworkX() + art.artworkWidth() <= art.width());
                }
        }
    }

    @Test
    void artworkCaptionSitsBelowTheArtworkAndCanBeRemoved() {
        var settings = new Settings();
        settings.hudStyle = Settings.HudStyle.ARTWORK;
        for (var position : Settings.ArtworkSpectrumPosition.values()) {
            settings.artworkSpectrumPosition = position;
            settings.artworkText = true;
            var art = ArtworkHudLayout.of(settings, 1920, 1080);
            assertTrue(art.captionY() >= art.progressY() + 3, position.name());
            assertTrue(art.captionY() + ArtworkHudLayout.CAPTION <= art.height(), position.name());
            if (position == Settings.ArtworkSpectrumPosition.BELOW)
                assertTrue(art.spectrumY() >= art.captionY() + ArtworkHudLayout.CAPTION);
            settings.artworkText = false;
            var bare = ArtworkHudLayout.of(settings, 1920, 1080);
            assertEquals(-1, bare.captionY());
            assertEquals(art.height() - ArtworkHudLayout.CAPTION, bare.height(), position.name());
        }
    }
}
