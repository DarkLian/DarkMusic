package com.darkmusic.client.pip;

import com.darkmusic.client.config.PipSettings;
import com.darkmusic.client.config.PipSpectrumSettings;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipSpectrumSettingsTest {
    @Test
    void independentAppearanceAndRoundTripDoNotChangeMusicPreferences() {
        var music = new Settings();
        var s = music.pip.spectrum;
        s.style = Settings.SpectrumStyle.ROUNDED_BARS;
        s.finish = Settings.SurfaceFinish.FLAT;
        s.position = Settings.ArtworkSpectrumPosition.LEFT;
        s.direction = Settings.SpectrumDirection.DOWNWARD;
        s.colors.preset = VisualColors.Preset.CUSTOM;
        s.colors.start = 0xff0000;
        s.colors.end = 0x00ff00;
        s.quality.glow = false;
        s.quality.detail = 384;
        s.bands = 64;
        s.height = 80;
        s.sensitivity = 2;
        s.smoothing = .9;
        s.opacity = .4;
        var rendering = new Settings();
        s.applyTo(rendering);
        assertEquals(s.style, rendering.spectrumStyle);
        assertEquals(s.finish, rendering.spectrumFinish);
        assertEquals(s.bands, rendering.visualizerBars);
        assertEquals(s.opacity, rendering.spectrumOpacity);
        assertEquals(s.sensitivity, rendering.visualizerSensitivity);
        assertEquals(s.smoothing, rendering.visualizerSmoothing);
        assertSame(s.quality, rendering.visualQuality);
        assertSame(s.colors, rendering.spectrumColors);
        assertNotSame(music.spectrumColors, rendering.spectrumColors);
        assertNotSame(music.visualQuality, rendering.visualQuality);
        assertEquals(Settings.SpectrumStyle.BARS, music.spectrumStyle);
        assertEquals(36, music.visualizerBars);
        assertEquals(1, music.spectrumOpacity);
        assertEquals(VisualColors.Preset.OCEAN_DRIVE, music.spectrumColors.preset);
        var restored = new Gson().fromJson(new Gson().toJson(music), Settings.class);
        restored.validate();
        assertEquals(Settings.ArtworkSpectrumPosition.LEFT, restored.pip.spectrum.position);
        assertEquals(80, restored.pip.spectrum.height);
        assertEquals(.4, restored.pip.spectrum.opacity);
        assertEquals(0xff0000, restored.pip.spectrum.colors.start);
        assertFalse(restored.pip.spectrum.quality.glow);
        restored.spectrumColors.start = 123;
        assertEquals(0xff0000, restored.pip.spectrum.colors.start);
    }

    @Test
    void olderAndNullProfilesGainSafeIndependentDefaults() {
        for (String json : new String[]{"{}", "{\"pip\":{\"spectrum\":null}}", "{\"pip\":{\"spectrum\":{\"colors\":null,\"quality\":null,\"style\":null,\"position\":null,\"direction\":null,\"finish\":null}}}"}) {
            var settings = new Gson().fromJson(json, Settings.class);
            settings.validate();
            assertNotNull(settings.pip.spectrum.colors);
            assertNotNull(settings.pip.spectrum.quality);
            assertEquals(Settings.ArtworkSpectrumPosition.BELOW, settings.pip.spectrum.position);
            assertEquals(40, settings.pip.spectrum.height);
            assertFalse(settings.pip.audioSpectrum);
            assertNotSame(settings.visualQuality, settings.pip.spectrum.quality);
        }
    }

    @Test
    void invalidAndNonFiniteValuesCannotReachRendering() {
        var s = new PipSpectrumSettings();
        s.bands = 999;
        s.height = -1;
        s.opacity = Double.NaN;
        s.sensitivity = Double.POSITIVE_INFINITY;
        s.smoothing = -10;
        s.quality.detail = 9999;
        s.validate();
        assertEquals(64, s.bands);
        assertEquals(8, s.height);
        assertEquals(1, s.opacity);
        assertEquals(1, s.sensitivity);
        assertEquals(0, s.smoothing);
        assertEquals(384, s.quality.detail);
    }

    @Test
    void fourPositionsFollowVideoAndStayVisibleAtScreenEdges() {
        var s = new PipSpectrumSettings();
        var video = new PipSettings.Bounds(200, 150, 180, 100);
        for (var position : Settings.ArtworkSpectrumPosition.values()) {
            s.position = position;
            var b = s.bounds(video, 960, 540);
            switch (position) {
                case ABOVE -> assertEquals(video.y(), b.y() + b.height());
                case BELOW -> assertEquals(video.y() + video.height(), b.y());
                case LEFT -> assertEquals(video.x(), b.x() + b.width());
                case RIGHT -> assertEquals(video.x() + video.width(), b.x());
            }
            assertEquals(s.sideways() ? s.height : video.width(), b.width());
            assertEquals(s.sideways() ? video.height() : s.height, b.height());
            for (int[] size : new int[][]{{320, 240}, {426, 240}, {960, 540}})
                for (int edge = 0; edge < 4; edge++) {
                    int x = edge % 2 == 0 ? 0 : size[0] - 120, y = edge / 2 == 0 ? 0 : size[1] - 80;
                    var atEdge = s.bounds(new PipSettings.Bounds(x, y, 120, 80), size[0], size[1]);
                    assertTrue(atEdge.x() >= 0 && atEdge.y() >= 0);
                    assertTrue(atEdge.x() + atEdge.width() <= size[0]);
                    assertTrue(atEdge.y() + atEdge.height() <= size[1]);
                }
        }
    }
}
