package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.UiTheme;
import com.darkmusic.client.hud.ArtworkHudLayout;
import com.darkmusic.client.hud.CircularHudLayout;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CustomizationTest {
    @TempDir
    Path temp;

    @Test
    void colorSpectrumMapsHueSaturationAndBrightnessWithoutLosingHexPrecision() {
        assertEquals(0xFF0000, com.darkmusic.client.gui.ColorSpectrum.rgb(0, 1, 1));
        assertEquals(0x00FF00, com.darkmusic.client.gui.ColorSpectrum.rgb(1f / 3, 1, 1));
        assertEquals(0x0000FF, com.darkmusic.client.gui.ColorSpectrum.rgb(2f / 3, 1, 1));
        assertEquals(0xFFFFFF, com.darkmusic.client.gui.ColorSpectrum.rgb(.75f, 0, 1));
        assertEquals(0, com.darkmusic.client.gui.ColorSpectrum.rgb(.75f, 1, 0));
        for (int color : new int[]{0x123456, 0xFFEECC, 0x578CFF, 0x888888}) {
            float[] hsv = java.awt.Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
            assertEquals(color, com.darkmusic.client.gui.ColorSpectrum.rgb(hsv[0], hsv[1], hsv[2]));
        }
    }

    @Test
    void everyClassicHeightReservesSpaceAndArtworkProgressTouchesTheImage() {
        var settings = new Settings();
        settings.validate();
        assertFalse(settings.lyricsEnabled);
        assertEquals(Settings.SurfaceFinish.BEVELED, settings.spectrumFinish);
        assertEquals(Settings.SurfaceFinish.BEVELED, settings.progressFinish);
        assertEquals(com.darkmusic.client.config.VisualQuality.Preset.ENHANCED, settings.visualQuality.preset());
        assertEquals(com.darkmusic.client.config.VisualColors.Preset.OCEAN_DRIVE, settings.spectrumColors.preset);
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
        assertEquals(com.darkmusic.client.config.VisualColors.Preset.MOONLIGHT, stored.spectrumColors.preset);
    }

    @Test
    void cleanupRemovesOnlyConfirmedMissingFilesFromCurrentRootIncludingFavorites() throws Exception {
        Path root = Files.createDirectory(temp.resolve("music"));
        Path existing = Files.writeString(root.resolve("existing.txt"), "");
        var missing = new PlaylistStore.Entry("here", "deleted.opus");
        var offline = new PlaylistStore.Entry("other", "deleted.opus");
        var unindexed = new PlaylistStore.Entry("here", "existing.txt");
        var entries = List.of(missing, offline, unindexed);
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new PlaylistStore(temp, io, message -> fail(message));
            store.loaded(List.of(new PlaylistStore.Playlist(PlaylistStore.FAVORITES, "Favorite Music", entries),
                new PlaylistStore.Playlist("custom", "Custom", entries)));
            assertTrue(PlaylistStore.missing(temp.resolve("offline"), "here", store.all(), List.of()).isEmpty());
            var result = PlaylistStore.missing(root, "here", store.all(), List.of());
            assertEquals(Set.of(missing), result);
            store.removeMissing(result);
            for (var playlist : store.all()) assertEquals(List.of(offline, unindexed), playlist.entries());
            assertTrue(Files.exists(existing));
        }
    }

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
                    assertEquals(0, HudLayout.lyricsHeight(settings));
                }
        }
    }

    @Test
    void customizationsPersistAndDefaultsKeepExistingAppearance() {
        var settings = new Settings();
        settings.validate();
        assertEquals(0x00111420, HudLayout.background(settings));
        assertEquals(CircularHudLayout.of(true, true), CircularHudLayout.of(true, true, settings.circularSpectrumHeight));
        settings.menuBackgroundColor = 0x224433;
        settings.menuAccentColor = 0xEECCCC;
        settings.hudBackgroundGradient = true;
        settings.hudBackgroundColors.end = 0x884422;
        settings.hudBackgroundTransparency = .5;
        settings.circularSpectrumHeight = 60;
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertTrue(loaded.hudBackgroundGradient);
        assertEquals(0x884422, loaded.hudBackgroundColors.last());
        assertEquals(128, HudLayout.background(loaded) >>> 24);
        try {
            UiTheme.apply(loaded);
            assertEquals(0xFF224433, UiTheme.BACKGROUND);
            assertEquals(0xFFEECCCC, UiTheme.ACCENT);
        } finally {
            UiTheme.apply(new Settings());
        }
        assertTrue(CircularHudLayout.of(true, true, 60).height() > CircularHudLayout.of(true, true).height());
    }

    @Test
    void sourceLinksValidatePersistAndRecoverOlderYoutubeDownloads() throws Exception {
        Path audio = temp.resolve("song.opus");
        for (String unsafe : List.of("file:///C:/private", "javascript:alert(1)", "https://user:secret@example.com", "not a URL"))
            assertEquals("", SourceLinks.safe(unsafe));
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        Files.writeString(temp.resolve(".darkmusic-downloads.json"), "{\"youtube:abcdefghijk:original\":\"song.opus\"}");
        assertEquals(url, SourceLinks.read(audio));
        Path renamed = temp.resolve("renamed.opus");
        SourceLinks.record(renamed, SourceLinks.read(audio));
        Files.delete(temp.resolve(".darkmusic-downloads.json"));
        assertEquals(url, SourceLinks.read(renamed));
        SourceLinks.record(renamed, "file:///invalid");
        assertEquals(url, SourceLinks.read(renamed));
    }
}
