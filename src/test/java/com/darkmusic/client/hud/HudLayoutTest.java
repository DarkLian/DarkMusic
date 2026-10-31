package com.darkmusic.client.hud;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudLayoutTest {
    @Test
    void everyClassicHeightReservesSpaceAndArtworkProgressTouchesTheImage() {
        var settings = new Settings();
        settings.validate();
        assertFalse(settings.lyricsEnabled);
        assertEquals(Settings.SurfaceFinish.BEVELED, settings.spectrumFinish);
        assertEquals(Settings.SurfaceFinish.BEVELED, settings.progressFinish);
        assertTrue(settings.visualQuality.frequencySmoothing && settings.visualQuality.peakHold && settings.visualQuality.trails && settings.visualQuality.glow);
        assertEquals(256, settings.visualQuality.detail);
        assertEquals(VisualColors.Preset.OCEAN_DRIVE, settings.spectrumColors.preset);
        for (int height : new int[]{8, 32, 100})
            for (var position : Settings.SpectrumPosition.values()) {
                settings.classicSpectrumHeight = height;
                settings.spectrumPosition = position;
                var layout = HudLayout.content(settings, Integer.MAX_VALUE);
                assertEquals(height, layout.spectrumHeight());
                assertTrue(layout.spectrumY() + height <= layout.height());
                assertTrue(position == Settings.SpectrumPosition.ABOVE
                    ? layout.spectrumY() + height + HudLayout.GAP == layout.mainY()
                    : layout.mainY() + layout.mainHeight() + HudLayout.GAP == layout.spectrumY());
            }
        for (var position : Settings.ArtworkSpectrumPosition.values()) {
            settings.artworkSpectrumPosition = position;
            settings.hudProgress = true;
            var withProgress = ArtworkHudLayout.of(settings, 1920, 1080);
            assertEquals(withProgress.artworkY() + withProgress.artworkHeight(), withProgress.progressY());
            assertTrue(withProgress.progressY() + 3 <= withProgress.height());
            settings.hudProgress = false;
            var without = ArtworkHudLayout.of(settings, 1920, 1080);
            assertEquals(withProgress.artworkWidth(), without.artworkWidth());
            if (position == Settings.ArtworkSpectrumPosition.BELOW)
                assertEquals(withProgress.spectrumY() - 3, without.spectrumY());
        }
        var stored = ConfigStore.JSON.fromJson("{\"lyricsEnabled\":true,\"spectrumFinish\":\"FLAT\",\"visualQuality\":{\"detail\":192},\"spectrumColors\":{\"preset\":\"MOONLIGHT\"}}", Settings.class);
        stored.validate();
        assertTrue(stored.lyricsEnabled);
        assertEquals(Settings.SurfaceFinish.FLAT, stored.spectrumFinish);
        assertEquals(VisualColors.Preset.MOONLIGHT, stored.spectrumColors.preset);
    }

    @Test
    void hudStaysOnScreenAndLegacyConfigurationMigratesWithoutResettingPreferences() {
        var settings = ConfigStore.JSON.fromJson("{\"schemaVersion\":1,\"volume\":0.32,\"hudPosition\":\"BOTTOM_RIGHT\",\"continueAcrossWorlds\":false}", Settings.class);
        settings.validate();
        assertEquals(1, settings.hudX);
        assertEquals(1, settings.hudY);
        assertEquals(.32, settings.volume);
        assertEquals(1, settings.speed);
        assertTrue(settings.hudArtwork);
        for (int width : new int[]{320, 854, 1920})
            for (int height : new int[]{240, 480, 1080})
                for (double scale : new double[]{.5, 1, 2}) {
                    var b = HudLayout.bounds(width, height, 1, 1, scale, true);
                    assertTrue(b.x() >= 8 && b.y() >= 8);
                    assertTrue(b.x() + b.width() <= width - 8);
                    assertTrue(b.y() + b.height() <= height - 8);
                    assertEquals(width - 16 == b.width() ? 0 : 1, HudLayout.normalized(b.x(), width, b.width()), .001);
                }
        settings.hudScale = Double.NaN;
        settings.speed = 9;
        settings.hudX = -3.0;
        settings.validate();
        assertEquals(1, settings.hudScale);
        assertEquals(3, settings.speed);
        assertEquals(0, settings.hudX);
    }

    @Test
    void hudTextScrimFollowsCardTransparencyAndLightness() {
        var settings = new Settings();
        settings.hudBackgroundTransparency = 0;
        settings.hudBackgroundGradient = false;
        settings.hudBackgroundColors.preset = VisualColors.Preset.CUSTOM;
        settings.hudBackgroundColors.start = settings.hudBackgroundColors.end = 0x111420;
        assertEquals(0, HudLayout.textScrim(settings), "An opaque dark card already gives contrast");
        settings.hudBackgroundTransparency = .85;
        int seeThrough = HudLayout.textScrim(settings);
        assertTrue(seeThrough >>> 24 > 0x40, "A mostly transparent card needs a scrim");
        settings.hudBackgroundTransparency = 1;
        assertTrue(HudLayout.textScrim(settings) >>> 24 >= seeThrough >>> 24);
        assertTrue(HudLayout.textScrim(settings) >>> 24 <= 0x60, "The scrim stays soft");
        settings.hudBackgroundTransparency = 0;
        settings.hudBackgroundColors.start = settings.hudBackgroundColors.end = 0xE6E6EE;
        assertTrue(HudLayout.textScrim(settings) >>> 24 > 0, "An opaque light card needs a scrim");
    }
}
